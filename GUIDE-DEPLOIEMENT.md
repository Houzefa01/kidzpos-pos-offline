# Guide de déploiement KidzPOS — Central cloud + Stores LAN

> **À l'usage de toute IA / assistant** : ce document est **autoportant**. Il
> décrit le projet, son architecture, et la procédure complète pour le déployer
> en production (central sur VM cloud gratuite + stores sur LAN privés des
> magasins). Tu peux le coller dans ChatGPT, Claude, Gemini, ou tout autre
> assistant pour obtenir de l'aide pendant le déploiement.

---

## Table des matières

1. [Qu'est-ce que KidzPOS](#1-quest-ce-que-kidzpos)
2. [Architecture cible (production)](#2-architecture-cible-production)
3. [Stack technique](#3-stack-technique)
4. [Modèle de synchronisation Store ↔ Central](#4-modèle-de-synchronisation-store--central)
5. [Prérequis](#5-prérequis)
6. [Phase 1 — Déployer le central sur une VM cloud](#6-phase-1--déployer-le-central-sur-une-vm-cloud)
7. [Phase 2 — Provisionner chaque magasin (store)](#7-phase-2--provisionner-chaque-magasin-store)
8. [Phase 3 — Vérifier la synchronisation bout-en-bout](#8-phase-3--vérifier-la-synchronisation-bout-en-bout)
9. [Maintenance & opérations](#9-maintenance--opérations)
10. [Troubleshooting](#10-troubleshooting)
11. [Sécurité](#11-sécurité)
12. [Annexes](#12-annexes)

---

## 1. Qu'est-ce que KidzPOS

**KidzPOS** est une application **point de vente (POS)** pour une **chaîne de
magasins** (contexte Madagascar, devise Ariary). Particularités :

- **Offline-first** : chaque magasin a son propre serveur local. Les caisses
  fonctionnent **même sans internet**. La synchronisation rattrape
  automatiquement au retour de la connexion.
- **Multi-magasin fédéré** : un nœud *central* (cloud) agrège les données de
  tous les magasins ; chaque *store* (nœud magasin local) pousse ses
  changements et reçoit ceux des autres magasins.
- **Multi-caisses par magasin** : plusieurs navigateurs (caisses) sur le LAN
  d'un magasin tapent le même backend store. Synchronisation temps réel via
  SSE.
- **Sécurité** : JWT court (15 min) + refresh token httpOnly (30j), rôles
  ADMIN/EMPLOYEE, soft-delete, idempotence.

**Cas d'usage typique** : chaîne de boutiques, chacune avec 1 à 5 caisses,
manager qui consulte le tableau de bord central depuis n'importe où.

---

## 2. Architecture cible (production)

```
┌────────────────────────────── INTERNET ─────────────────────────────┐
│                                                                       │
│    https://central.tondomaine.com                                    │
│                  ▲                                                    │
│                  │ HTTPS (Let's Encrypt)                              │
│                  │                                                    │
│   ┌──────────────┴───────────────────────┐                           │
│   │  VM publique (Oracle Cloud / Google) │                           │
│   │  ─────────────────────────────────── │                           │
│   │   Caddy :443  ← TLS, reverse proxy   │                           │
│   │     ├─ /          → /srv/kidzpos/dist (SPA React)               │
│   │     ├─ /api/*     → 127.0.0.1:8080                              │
│   │     ├─ /actuator/* → 127.0.0.1:8080                             │
│   │     └─ /v3/api-docs* → 127.0.0.1:8080                           │
│   │                                                                  │
│   │   Spring Boot :8080 (loopback uniquement)                       │
│   │     systemd : kidzpos.service                                   │
│   │     profile : central                                           │
│   │     mémoire : -Xmx768m                                          │
│   │                                                                  │
│   │   PostgreSQL :5432 (loopback) — DB kidzpos_central              │
│   │   ufw : seuls 22, 80, 443 ouverts                               │
│   └──────────────────────────────────────┘                           │
│                       ▲                                              │
│                       │ HTTPS                                        │
│                       │ POST /api/sync/push                          │
│                       │ GET  /api/sync/pull                          │
│                       │ headers : X-Sync-Api-Key + X-Sync-Store-Id   │
│                       │                                              │
└───────────────────────┼──────────────────────────────────────────────┘
                        │
       ┌────────────────┴──────────────────┐
       │                                    │
┌──────▼──────────────────┐      ┌─────────▼──────────────────┐
│ Magasin 1 (LAN privé)   │      │ Magasin 2 (LAN privé)      │
│ ─────────────────────── │      │ ─────────────────────────── │
│  Caddy :443             │      │  Caddy :443                │
│   └→ Spring :8081 (LB)  │      │   └→ Spring :8081 (LB)     │
│   └→ /srv/dist-store    │      │   └→ /srv/dist-store       │
│  PostgreSQL local       │      │  PostgreSQL local          │
│  storeId = s1           │      │  storeId = s2              │
│                         │      │                            │
│  Caisse 1 ─┐            │      │  Caisse 1 ─┐               │
│  Caisse 2 ─┼→ Caddy ←──┐│      │  Caisse 2 ─┼→ Caddy        │
│  Caisse 3 ─┘           ││      │  Caisse 3 ─┘               │
│                         │      │                            │
└─────────────────────────┘      └────────────────────────────┘
       (offline-tolerant : marche sans internet, sync rattrape au retour)
```

### Pourquoi cette architecture

| Choix | Raison |
|---|---|
| Caddy en reverse proxy (front + API même origine) | Plus de CORS, cookie `Secure=true SameSite=Lax`, un seul cert TLS |
| Backend en loopback | Surface d'attaque réduite : seul Caddy peut parler au backend |
| Stores sur LAN privé (pas d'IP publique) | Réduit l'exposition. Push vers central, jamais l'inverse côté HTTP |
| Postgres par nœud | Pas de SPOF DB. Chaque magasin garde ses données même si central down |
| `operation_log` + `sync_inbox` | Idempotence + résilience aux re-jeux + Last-Writer-Wins |

---

## 3. Stack technique

### Frontend (SPA React)

| Outil | Version | Rôle |
|---|---|---|
| React + TypeScript | 18 / 5.8 | Framework UI |
| Vite | 8 | Bundler (port dev 5173, build → `dist/`) |
| Zustand | 5 | State global persisté en localStorage |
| React Router DOM | 6 | Routing avec lazy loading |
| shadcn/ui + Radix UI | — | Composants UI accessibles |
| Tailwind CSS | 3 | Styles utilitaires |
| Zod | 4 | Validation des réponses backend |
| Sonner | — | Toasts |
| Vitest | — | Tests unitaires (144 tests verts) |

### Backend (Spring Boot)

| Outil | Version | Rôle |
|---|---|---|
| Spring Boot | 3.3.4 | Web + Security + Data JPA + Actuator |
| Java | 17 | LTS minimum |
| PostgreSQL | 14+ | Seule DB supportée |
| Flyway | — | Migrations V1–V22 dans `backend/src/main/resources/db/migration/` |
| JJWT | 0.12.6 | JWT 15 min + refresh httpOnly 30j |
| Micrometer + Prometheus | — | Métriques + health indicators custom |
| BCrypt | — | Hash mots de passe |

### Infra

| Outil | Rôle |
|---|---|
| Caddy 2.x | Reverse proxy HTTPS + Let's Encrypt automatique |
| systemd | Supervision des process (kidzpos.service, caddy.service) |
| ufw | Pare-feu Ubuntu |

---

## 4. Modèle de synchronisation Store ↔ Central

### Flux d'écriture (push store → central)

1. Caisse crée une vente / produit / utilisateur
2. Le backend store écrit en DB locale **immédiatement** (offline-first)
3. Une ligne est ajoutée dans `operation_log` (type, payload JSON, storeId, timestamp)
4. `SyncScheduler` (toutes les 5s) batch les ops non envoyées (max 200)
5. POST `https://central.tondomaine.com/api/sync/push` avec headers :
   - `X-Sync-Api-Key: kzp_…`
   - `X-Sync-Store-Id: s1`
6. Le central insère les ops dans `sync_inbox` puis ack ; le store marque
   les rows comme envoyées
7. Backoff exponentiel en cas d'échec, FROZEN après N tentatives (alarme)

### Flux de lecture (pull central → store)

1. `SyncPullScheduler` toutes les 10s sur le store
2. GET `https://central.tondomaine.com/api/sync/pull?cursor=<last_id>`
3. Le central renvoie toutes les ops `id > cursor` (full mesh, V22)
4. Le store insère dans `sync_inbox`. Si une op vient de **soi-même**
   (id existe déjà dans son `operation_log`), elle est marquée `processed=true`
   (acquittement sans rejoue) — fix critique des doubles applications.

### Matérialisation (inbox processor)

`SyncInboxProcessor` (toutes les 10s) consomme `sync_inbox` et applique chaque
event via le bon `InboxHandler` :

- `product.created/updated/deleted`
- `sale.created/voided/refunded`
- `stock.adjusted/transferred`
- `user.created/updated/role-changed`
- `customer.*`, `settings.*`

Chaque handler est **idempotent** (vérif d'existence + LWW timestamp). Retry
auto, quarantaine dans `quarantine_events` après MAX_RETRIES.

### Sécurité de la sync

- **Per-store API keys** : chaque magasin a sa propre clé, hash bcrypt en DB
  (`sync_api_keys`, V21). Émise UNE fois en clair par `POST /api/sync/keys`.
- **Cross-validation** : le central vérifie que `X-Sync-Store-Id` correspond
  bien à la clé fournie → un store compromis ne peut pas usurper un autre.
- **Rate-limit** : 120 req/min par store (configurable).

---

## 5. Prérequis

### Machine locale (ta machine de dev/déploiement)

```bash
# Outils requis
java --version       # 17 ou plus
mvn --version
node --version       # 20 ou plus
npm --version
ssh -V
rsync --version

# Une fois (avec internet) — installer les dépendances du projet
cd /chemin/vers/kidzpos
npm ci
(cd backend && mvn -DskipTests package)
```

### Compte cloud (au choix)

**Recommandé** : **Oracle Cloud Always Free** — VM ARM 4 vCPU + 24 Go RAM
gratuites à vie. https://www.oracle.com/cloud/free/

**Alternative** : **Google Cloud Always Free** — VM `e2-micro` 1 vCPU + 1 Go
RAM gratuit à vie. https://cloud.google.com/free/

**Auto-hébergement** : Raspberry Pi 4 + DNS dynamique DuckDNS.

### Nom de domaine

- **Gratuit** : `kidzpos.duckdns.org` via https://www.duckdns.org
- **Payant (~10€/an)** : domaine perso via OVH, Cloudflare, Namecheap

---

## 6. Phase 1 — Déployer le central sur une VM cloud

### 6.1 Créer la VM

**Sur Oracle Cloud** :

1. https://cloud.oracle.com → *Compute → Instances → Create instance*
2. **Région** : choisir une moins demandée (Marseille, Madrid, Stockholm,
   Mumbai, Singapore) pour éviter « out of capacity » sur ARM
3. **Shape** : *Ampere → VM.Standard.A1.Flex* → **4 OCPU + 24 Go RAM**
4. **Image** : Canonical Ubuntu 22.04 (ou plus récent)
5. **SSH key** : coller `~/.ssh/id_ed25519.pub` (ou en générer une :
   `ssh-keygen -t ed25519 -C "kidzpos-central"`)
6. **Networking** : cocher « Assign a public IPv4 address »
7. Noter l'IP publique affichée

**Sur Google Cloud (alternative e2-micro)** :

1. https://console.cloud.google.com → *Compute Engine → VM instances → Create*
2. **Region** : `us-central1-a` (Iowa, le plus stable pour e2-micro)
3. **Machine type** : *E2 → e2-micro*
4. **Boot disk** : Ubuntu 22.04 LTS Minimal, 30 Go Standard persistent disk
5. **Firewall** : cocher « Allow HTTPS traffic » et « Allow HTTP traffic »
6. Noter l'IP externe affichée

### 6.2 Configurer le DNS

Un A-record `central.tondomaine.com` → IP de la VM.

**DuckDNS (gratuit, le plus simple)** :
1. https://www.duckdns.org → login (GitHub/Google)
2. Créer un sous-domaine `kidzpos.duckdns.org`
3. Coller l'IP de la VM dans le champ « current ip » → *update ip*
4. Vérifier : `dig +short kidzpos.duckdns.org` doit retourner ton IP

**Cloudflare DNS (si tu as un domaine)** :
1. Ajouter un A record `central` → `<ip-vm>`, proxy **désactivé** (gris,
   sinon Caddy ne peut pas faire le challenge Let's Encrypt directement)

### 6.3 Bootstrap de la VM

Depuis ta machine locale :

```bash
# 1. Pousser le dossier deploy/ sur la VM
scp -r ops/deploy ubuntu@<ip-vm>:/tmp/

# 2. Se connecter et installer
ssh ubuntu@<ip-vm>
sudo bash /tmp/deploy/install.sh
```

Le script `install.sh` (idempotent, ~3 minutes) :

1. Installe Java 17, PostgreSQL, Caddy, ufw
2. Active un swap de 1 Go (filet de sécurité OOM)
3. Crée l'utilisateur système `kidzpos` + dossiers `/srv/kidzpos/{bin,dist,logs}`
4. Provisionne la DB `kidzpos_central` + user `kidzpos` avec mot de passe random
5. Configure ufw : seuls 22 (SSH), 80 (Let's Encrypt + redirect), 443 (HTTPS) ouverts
6. Installe le unit systemd `kidzpos.service` (sans le démarrer)
7. Copie `.env.example` → `/srv/kidzpos/.env` en injectant le mot de passe DB

### 6.4 Configurer les secrets

Toujours en SSH sur la VM :

```bash
sudo nano /srv/kidzpos/.env
```

Remplir au minimum :

```bash
DOMAIN=central.tondomaine.com               # le nom DNS configuré ci-dessus
JWT_SECRET=                                  # à générer (voir ci-dessous)
KIDZPOS_ADMIN_PASSWORD=                      # mot de passe initial de admin@kidzpos.com
KIDZPOS_SYNC_INBOUND_API_KEY=                # LAISSER VIDE en multi-store
```

Générer le JWT secret (sur la VM) :

```bash
openssl rand -hex 32
# colle la sortie dans JWT_SECRET=
```

Choisir un `KIDZPOS_ADMIN_PASSWORD` fort (> 16 caractères, non réutilisé).
Il sera utilisé pour le login `admin@kidzpos.com` côté UI.

Sauver, quitter.

> ⚠ **Laisser `KIDZPOS_SYNC_INBOUND_API_KEY` vide** active automatiquement le
> mode per-store : chaque magasin reçoit sa propre clé via
> `POST /api/sync/keys`. C'est le mode recommandé en multi-store.

### 6.5 Déployer depuis ta machine

Quitter la session SSH, retour sur ta machine locale :

```bash
./ops/deploy/deploy.sh ubuntu@<ip-vm> central.tondomaine.com
```

Le script :

1. `mvn -DskipTests package` → JAR backend
2. `VITE_API_URL=https://central.tondomaine.com npm run build` → frontend
3. `rsync` JAR → `/srv/kidzpos/bin/`, dist → `/srv/kidzpos/dist/`,
   Caddyfile → `/etc/caddy/`, unit systemd → `/etc/systemd/system/`
4. Sur la VM : `systemctl restart kidzpos && reload caddy`
5. Boucle sur `curl https://central.tondomaine.com/actuator/health`
   jusqu'à `{"status":"UP"}` (≤ 120s, le temps que Let's Encrypt émette le cert)

### 6.6 Vérifier

```bash
curl https://central.tondomaine.com/actuator/health
# {"status":"UP"}

# Ouvrir https://central.tondomaine.com dans un navigateur
# → login : admin@kidzpos.com / <KIDZPOS_ADMIN_PASSWORD>
```

Le central est en ligne. **Coût mensuel : 0 €** si Oracle Always Free + DuckDNS.

---

## 7. Phase 2 — Provisionner chaque magasin (store)

### 7.1 Créer le magasin côté central

Via l'UI : `Paramètres → Magasins → Nouveau magasin` → nom, code (ex: `s1`),
adresse. Le champ "code" devient le `storeId` du nœud store.

Alternativement, en SQL sur la VM :

```bash
ssh ubuntu@<ip-vm>
sudo -u postgres psql kidzpos_central
INSERT INTO stores (id, name, active, created_at)
VALUES ('s1', 'Magasin Antananarivo', true, now());
\q
```

### 7.2 Émettre une clé API pour ce magasin

Depuis ta machine locale :

```bash
./ops/deploy/issue-store-key.sh \
    https://central.tondomaine.com \
    admin@kidzpos.com '<mot-de-passe-admin>' \
    s1 'Magasin Antananarivo'
```

Sortie :

```
✅ Clé générée — À CONSERVER MAINTENANT
  storeId : s1
  keyId   : 9d2c47e8-…
  KEY     : kzp_2f4b7c9e8a1d6b3c5a8e2f1d3c6a8b9d4e7f2a1b
```

> ⚠ **Cette clé n'est affichée qu'une seule fois.** Le central ne stocke
> qu'un hash bcrypt. Si perdue → révoquer + ré-émettre.

### 7.3 Préparer le serveur du magasin

Sur la machine du magasin (un mini-PC ou un laptop dans la boutique) :

```bash
# 1. Installer les prérequis
sudo apt update
sudo apt install -y openjdk-17-jre-headless postgresql nodejs npm caddy

# 2. Récupérer le code KidzPOS
git clone <repo-url> ~/kidzpos
cd ~/kidzpos

# 3. Configurer PostgreSQL local
sudo -u postgres createuser kidzpos -P            # mot de passe au choix
sudo -u postgres createdb -O kidzpos kidzpos_store_s1

# 4. Build une seule fois (avec internet)
npm ci
(cd backend && mvn -DskipTests package)
```

### 7.4 Configurer les variables d'environnement du store

Créer `~/.kidzpos-store.env` (chmod 600) :

```bash
# ── Identité du magasin ─────────────────────────────────────
KIDZPOS_NODE_ID=store-s1
KIDZPOS_NODE_STORE_ID=s1                          # DOIT correspondre au storeId central

# ── Connexion au central ────────────────────────────────────
KIDZPOS_SYNC_CENTRAL_URL=https://central.tondomaine.com
KIDZPOS_SYNC_API_KEY=kzp_2f4b7c9e8a1d6b3c5a8e2f1d3c6a8b9d4e7f2a1b
KIDZPOS_SYNC_PUSH_ENABLED=true
KIDZPOS_SYNC_PULL_ENABLED=true
KIDZPOS_SYNC_INBOX_ENABLED=true

# ── Base de données locale ──────────────────────────────────
DB_HOST=localhost
DB_PORT=5432
DB_NAME=kidzpos_store_s1
DB_USER=kidzpos
DB_PASSWORD=<mot-de-passe-postgres-créé-ci-dessus>

# ── Secrets locaux ──────────────────────────────────────────
JWT_SECRET=<openssl rand -hex 32>                 # PROPRE au store, ≠ celui du central
KIDZPOS_ADMIN_PASSWORD=<mot-de-passe-admin-local>
```

```bash
chmod 600 ~/.kidzpos-store.env
```

### 7.5 Lancer le store

Deux options.

**Option A — HTTP simple sur le LAN** (caisses tapent `http://<ip-magasin>:4001`) :

```bash
cd ~/kidzpos
./ops/launch/build-lan.sh store              # build le frontend câblé sur l'IP LAN
set -a; source ~/.kidzpos-store.env; set +a
./ops/launch/launch-store.sh
```

Sortie attendue :

```
▶ STORE s1 (hors-ligne)  profile=local  API=:8081  UI=:4001  …
  ✓ store API prêt (port 8081)
  → local : http://localhost:4001
  → LAN   : http://192.168.1.10:4001
```

**Option B — HTTPS local via Caddy** (caisses tapent `https://<ip-magasin>:4443`) :

```bash
cd ~/kidzpos
./ops/launch/build-lan.sh store --https      # build le frontend HTTPS
set -a; source ~/.kidzpos-store.env; set +a
./ops/launch/launch-store-tls.sh
```

Les caisses devront accepter le cert auto-signé Caddy une fois (clic
« continuer »), OU on copie la CA Caddy locale dans le trust store des
caisses (voir [troubleshooting](#10-troubleshooting)).

### 7.6 Démarrage automatique au boot (systemd)

Pour que le store redémarre seul après une coupure de courant :

```bash
sudo tee /etc/systemd/system/kidzpos-store.service <<EOF
[Unit]
Description=KidzPOS store node
After=network-online.target postgresql.service
[Service]
Type=simple
User=$USER
WorkingDirectory=$HOME/kidzpos
EnvironmentFile=$HOME/.kidzpos-store.env
ExecStart=/bin/bash -c 'set -a; source $HOME/.kidzpos-store.env; set +a; $HOME/kidzpos/ops/launch/launch-store.sh'
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now kidzpos-store
```

---

## 8. Phase 3 — Vérifier la synchronisation bout-en-bout

### 8.1 Côté store

```bash
# Vérifier que le push/pull tourne
tail -f ~/kidzpos/logs/store-s1.log | grep -i sync
# [sync-push] sent N ops to central
# [sync-pull] received M new ops from central
```

### 8.2 Côté central

```bash
ssh ubuntu@<ip-vm> 'sudo journalctl -u kidzpos -n 100 | grep -i sync'
# [sync] received N ops from store=s1
# [sync-inbox] processed N ops from store=s1
```

### 8.3 Test fonctionnel

1. Sur le store, créer un produit via l'UI
2. Attendre ~10s
3. Sur le central (`https://central.tondomaine.com`), aller dans *Produits*
4. Le produit doit apparaître

Inversement :

1. Sur le central, ajuster le stock d'un produit
2. Attendre ~10s
3. Sur le store, le stock doit refléter le changement

---

## 9. Maintenance & opérations

### Mises à jour applicatives

**Central** : depuis ta machine locale après `git pull`,

```bash
./ops/deploy/deploy.sh ubuntu@<ip-vm> central.tondomaine.com
```

**Store** : sur chaque machine de magasin,

```bash
cd ~/kidzpos && git pull
(cd backend && mvn -DskipTests package)
./ops/launch/build-lan.sh store
sudo systemctl restart kidzpos-store
```

### Logs en direct

```bash
# Central
ssh ubuntu@<ip-vm> 'sudo journalctl -u kidzpos -f'
ssh ubuntu@<ip-vm> 'sudo journalctl -u caddy -f'

# Store
sudo journalctl -u kidzpos-store -f
# ou
tail -f ~/kidzpos/logs/store-s1.log
```

### Backup PostgreSQL

**Central** (mettre en cron quotidien sur ta machine) :

```bash
ssh ubuntu@<ip-vm> 'sudo -u postgres pg_dump kidzpos_central | gzip' \
  > backups/central-$(date +%F).sql.gz
```

**Store** (cron local sur la machine du magasin) :

```bash
sudo -u postgres pg_dump kidzpos_store_s1 | gzip > ~/backups/store-s1-$(date +%F).sql.gz
```

### Patches OS

```bash
ssh ubuntu@<ip-vm> 'sudo apt update && sudo apt upgrade -y && sudo reboot'
```

### Métriques (optionnel)

Prometheus expose les métriques sur `https://central.tondomaine.com/actuator/prometheus`.
Stack complète Prometheus + Grafana + Alertmanager dispo dans `ops/` du repo
(voir `ops/docker-compose.yml`).

---

## 10. Troubleshooting

| Symptôme | Cause probable | Solution |
|---|---|---|
| `deploy.sh` bloque sur readiness | DNS pas encore propagé | `dig +short central.tondomaine.com` doit retourner l'IP VM |
| Erreur Let's Encrypt « unable to fetch challenge » | Port 80 fermé / DNS faux | `sudo ufw status` doit montrer 80 ALLOW · DNS A-record correct |
| `bind: permission denied` sur :443 | Caddy sans cap_net_bind_service | `sudo setcap cap_net_bind_service=+ep $(command -v caddy)` |
| Login échoue / déconnexion au refresh | Cookie `Secure=true` mais HTTP | Vérifier `KIDZPOS_AUTH_COOKIE_SECURE=true` en HTTPS uniquement |
| Erreur CORS en console navigateur | Frontend buildé sur mauvaise URL | Re-déployer avec `VITE_API_URL=https://<DOMAIN>` |
| Store push échoue en 403 | `X-Sync-Store-Id` ≠ clé | Ré-émettre la clé pour ce storeId |
| Store push échoue en 401 | Clé révoquée / mauvaise | Vérifier `KIDZPOS_SYNC_API_KEY` dans `.env` du store |
| `[sync-push] not configured` | URL ou clé vide | Vérifier `KIDZPOS_SYNC_CENTRAL_URL` + `KIDZPOS_SYNC_API_KEY` |
| Stock divergent entre store/central | Bug de re-jeu (corrigé) | Vérifier `SyncPullService` insère `processed=true` pour ses propres events |
| JVM OOMKilled | Mémoire insuffisante | Réduire `-Xmx` dans `kidzpos.service`, ou activer swap |
| Caddy : `tlsv1 alert internal error` | Hôte non listé dans `tls internal` | Ajouter le FQDN/IP dans `KIDZPOS_HTTPS_ADDRS` (local) |

### Diagnostic réseau

```bash
# Depuis le store, vérifier que le central est joignable
curl -v https://central.tondomaine.com/actuator/health
# Doit afficher TLS handshake OK + JSON UP

# Vérifier la résolution DNS
dig +short central.tondomaine.com
nslookup central.tondomaine.com

# Inspecter le cert Let's Encrypt
openssl s_client -connect central.tondomaine.com:443 -servername central.tondomaine.com < /dev/null | grep -E "subject=|issuer="
```

### Diagnostic application

```bash
# Sur le central : status des services
ssh ubuntu@<ip-vm> 'sudo systemctl status kidzpos caddy postgresql'

# Sur le store : status local
sudo systemctl status kidzpos-store

# Voir les 100 dernières lignes d'erreur
ssh ubuntu@<ip-vm> 'sudo journalctl -u kidzpos --since "10 min ago" -p err'
```

---

## 11. Sécurité

### Secrets

- `/srv/kidzpos/.env` (central) et `~/.kidzpos-store.env` (store) : **chmod 600**
- JWT_SECRET : long (32 bytes hex), différent par nœud
- Mot de passe admin : > 16 caractères, non réutilisé
- Clés API sync : `kzp_…` jamais commitées, jamais loggées en clair

### Pare-feu

- Central VM : ufw → 22, 80, 443 uniquement
- Store : aucune ouverture sur internet (le store sort vers central, pas l'inverse)

### Rotation des clés

```bash
# Lister les clés actives
JWT=$(curl -sf -X POST https://central.tondomaine.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@kidzpos.com","password":"…"}' | jq -r .token)
curl -sf -H "Authorization: Bearer $JWT" https://central.tondomaine.com/api/sync/keys

# Révoquer une clé compromise
curl -sf -X DELETE -H "Authorization: Bearer $JWT" \
     https://central.tondomaine.com/api/sync/keys/<keyId>

# Émettre la nouvelle
./ops/deploy/issue-store-key.sh https://central.tondomaine.com \
    admin@kidzpos.com '<pw>' s1 'rotation 2026-06'
# Mettre à jour KIDZPOS_SYNC_API_KEY dans ~/.kidzpos-store.env du store
# sudo systemctl restart kidzpos-store
```

### Headers HTTP sécurité

Caddy envoie déjà :
- `Strict-Transport-Security`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`

Spring envoie en plus sur `/api/*` :
- `Content-Security-Policy` strict
- `X-XSS-Protection`

### Audit

Les tables suivantes tracent les actions sensibles :
- `auth_events` : login/logout/refresh
- `operation_log` : toutes mutations métier (qui, quoi, quand, où)
- `conflict_log` : résolutions Last-Writer-Wins (anormales ≠ silencieuses)
- `quarantine_events` : events sync irréparables

---

## 12. Annexes

### A. Référence rapide des variables d'environnement

**Central** (`/srv/kidzpos/.env`) :

| Variable | Défaut | Description |
|---|---|---|
| `DOMAIN` | — | Nom de domaine public (ex: central.kidzpos.com) |
| `SPRING_PROFILES_ACTIVE` | `central` | Active le profile central |
| `SERVER_PORT` | `8080` | Port backend (loopback) |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind loopback uniquement |
| `JWT_SECRET` | — | 32 bytes hex |
| `KIDZPOS_ADMIN_PASSWORD` | — | Mot de passe initial admin@kidzpos.com |
| `KIDZPOS_SYNC_INBOUND_API_KEY` | vide | Vide = mode per-store (recommandé) |
| `DB_HOST/PORT/NAME/USER/PASSWORD` | localhost/5432/kidzpos_central/kidzpos/<random> | DB locale |
| `KIDZPOS_AUTH_COOKIE_SECURE` | `true` | HTTPS obligatoire |
| `KIDZPOS_AUTH_COOKIE_SAME_SITE` | `Lax` | Même origine via Caddy |

**Store** (`~/.kidzpos-store.env`) :

| Variable | Défaut | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Active le profile store |
| `SERVER_PORT` | `8081` | Port backend store |
| `KIDZPOS_NODE_ID` | `store-s1` | Identifiant logique du nœud |
| `KIDZPOS_NODE_STORE_ID` | — | storeId (DOIT matcher le central) |
| `KIDZPOS_SYNC_CENTRAL_URL` | — | URL HTTPS du central |
| `KIDZPOS_SYNC_API_KEY` | — | Clé émise par `issue-store-key.sh` |
| `KIDZPOS_SYNC_PUSH_ENABLED` | `true` | Pousse vers central |
| `KIDZPOS_SYNC_PULL_ENABLED` | `true` | Tire depuis central |
| `KIDZPOS_SYNC_INBOX_ENABLED` | `true` | Matérialise les events reçus |

### B. Référence des endpoints

| Path | Méthode | Rôle |
|---|---|---|
| `/api/auth/login` | POST | Login email/password → JWT + refresh cookie |
| `/api/auth/refresh` | POST | Rotation refresh token |
| `/api/auth/me` | GET | User courant |
| `/api/products` | GET/POST/PUT/DELETE | CRUD produits |
| `/api/sales` | GET/POST | Ventes (Page paginée) |
| `/api/stock` | POST | Transferts stock |
| `/api/sync/push` | POST | Store → Central : push d'ops |
| `/api/sync/pull` | GET | Store → Central : pull d'ops |
| `/api/sync/keys` | POST | Admin : émettre une clé per-store |
| `/api/sync/keys/{id}` | DELETE | Admin : révoquer une clé |
| `/api/events/auth` | POST | Handshake SSE (JWT → UUID single-use) |
| `/api/events/stream` | GET | SSE : flux d'events temps réel |
| `/actuator/health` | GET | Healthcheck (public) |
| `/actuator/prometheus` | GET | Métriques (admin) |
| `/v3/api-docs` | GET | OpenAPI 3 spec |

### C. Structure du repo (extrait pertinent)

```
.
├── backend/                          # Spring Boot
│   ├── src/main/java/com/kidzpos/    # Code Java
│   ├── src/main/resources/
│   │   ├── application.yml           # Config par défaut
│   │   ├── application-central.yml   # Profile central
│   │   ├── application-local.yml     # Profile store
│   │   └── db/migration/             # Flyway V1–V22
│   └── target/kidzpos-backend-*.jar  # Artefact build
│
├── src/                              # React SPA
│   ├── App.tsx
│   ├── lib/apiConfig.ts              # Résout VITE_API_URL
│   └── …
├── dist/                             # Build vite (généré)
│
├── ops/
│   ├── launch/                       # Lancement LAN/local
│   │   ├── launch-central.sh         # HTTP
│   │   ├── launch-store.sh
│   │   ├── launch-central-tls.sh     # HTTPS local (Caddy)
│   │   ├── launch-store-tls.sh
│   │   ├── build-lan.sh              # Build frontend câblé sur IP LAN
│   │   ├── Caddyfile                 # Caddy local (tls internal)
│   │   └── README.md
│   │
│   └── deploy/                       # Déploiement cloud (cette doc)
│       ├── install.sh                # Bootstrap VM
│       ├── deploy.sh                 # Push code local → VM
│       ├── issue-store-key.sh        # Génère clé sync per-store
│       ├── Caddyfile                 # Caddy prod (Let's Encrypt)
│       ├── kidzpos.service           # systemd unit backend
│       ├── .env.example              # Template secrets
│       ├── README.md
│       └── STORE-SETUP.md
│
├── CLAUDE.md                         # Instructions projet (architecture détaillée)
├── GUIDE-DEPLOIEMENT.md              # CE DOCUMENT
└── README.md
```

### D. Coût mensuel attendu

| Composant | Coût |
|---|---|
| VM Oracle Cloud Always Free (24 Go RAM) | **0 €/mois à vie** |
| Domaine `*.duckdns.org` | **0 €/mois** |
| Domaine custom (optionnel) | 8-12 €/an |
| Sortie réseau (10 To/mois inclus) | **0 €** dans la limite |
| Backups stockage Cloud | **0 €** (10 Go S3 inclus chez Oracle) |
| **Total** | **0 €/mois** |

### E. Limites connues / dette technique

- **Mono-instance** : pas de cluster pour le central. Pour une chaîne > 50
  magasins, prévoir un upgrade (Redis pour rate-limit partagé, Postgres
  managé répliqué).
- **Pas de multi-store par utilisateur** : chaque user appartient à UN store.
- **Pas d'export comptable PDF automatique** (à faire à la demande via UI).
- **Logs JSON pour Loki/ELK non standardisés** (logs texte actuellement).

### F. Pour aller plus loin

- **Stack observabilité complète** : `ops/docker-compose.yml` lance
  Prometheus + Grafana + Alertmanager + 3 dashboards + 11 runbooks.
- **Tests** : `npm test` (144 tests front), `mvn test` (101 tests back, dont
  9 e2e de sync fédérée).
- **Documentation interne** : `CLAUDE.md` à la racine décrit les conventions
  de code et l'architecture en profondeur.

---

**Fin du guide.** Pour toute question pendant le déploiement, ce document peut
être collé dans n'importe quel assistant IA pour obtenir une aide contextualisée.
