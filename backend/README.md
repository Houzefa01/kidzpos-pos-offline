# KidzPOS — Guide d'installation complet

Système de caisse multi-magasin **offline-first**, avec backend Spring Boot et frontend React PWA.
Fonctionne en LAN (sans Internet), sur Internet, et même quand le serveur tombe (cache local + outbox + sync auto).

---

## 1. Architecture

```
┌──────────────┐   LAN/Internet    ┌────────────────────┐
│  Caisse 1    │ ───── HTTPS ────► │  Backend Spring    │
│  (PWA React) │ ◄──── SSE ──────  │  + PostgreSQL      │
└──────────────┘                   └────────────────────┘
┌──────────────┐
│  Caisse 2    │ ◄── tous les changements diffusés
│  (PWA React) │     en temps réel via SSE
└──────────────┘
```

- **Frontend** : React + Vite (PWA installable, fonctionne offline)
- **Backend** : Spring Boot 3.3 + JWT + JPA
- **Base** : PostgreSQL 14+ (obligatoire — seul SGBD supporté)
- **Sync temps réel** : Server-Sent Events (`/api/events/stream`)
- **Mode offline** : chaque poste garde un cache localStorage + file d'attente (outbox) qui se vide dès que le serveur revient

---

## 2. Installation locale (LAN du magasin)

### Pré-requis sur le PC "serveur"
- Java 17+ ([https://adoptium.net](https://adoptium.net))
- **PostgreSQL 14+** (obligatoire — le backend refuse de démarrer sans)
- Node 18+ et `bun` ou `npm` (pour builder le frontend une fois)

### 2.1 — Préparer la base PostgreSQL

```bash
sudo -u postgres psql
CREATE DATABASE kidzpos;
CREATE USER kidzpos WITH PASSWORD 'kidzpos';
GRANT ALL PRIVILEGES ON DATABASE kidzpos TO kidzpos;
\q
```

### 2.2 — Lancer le backend

```bash
cd backend
./mvnw spring-boot:run
```

Le backend lit la config DB via les variables `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` (défauts : `localhost:5432/kidzpos` user `kidzpos`).

Le serveur écoute sur `http://0.0.0.0:8080` (toutes interfaces réseau).
Trouvez l'IP locale du PC serveur :
- Windows : `ipconfig` → ex `192.168.1.20`
- Linux/Mac : `ip a` ou `ifconfig`

Vérification : depuis un autre poste du LAN, `http://192.168.1.20:8080/actuator/health` doit répondre `{"status":"UP"}`.

### 2.3 — Variables d'environnement (optionnelles)

```bash
export JWT_SECRET="une-chaine-aleatoire-très-longue-32-octets-min"
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=kidzpos
export DB_USER=kidzpos
export DB_PASSWORD=monMotDePasseFort
export CORS_ALLOWED_ORIGINS="*"   # ou "https://kidzpos.monshop.com"
```

### 2.4 — Builder & héberger le frontend

```bash
# À la racine du projet
bun install
bun run build
# Le dossier dist/ contient les fichiers statiques
```

Servir `dist/` :
- **Simple** : `npx serve dist -l 3000` puis ouvrir `http://192.168.1.20:3000`
- **Production** : nginx ou Caddy (voir section Internet)

### 2.5 — Configurer chaque caisse

Sur chaque poste : ouvrir l'app → **Paramètres** → renseigner l'adresse :
```
http://192.168.1.20:8080
```
Cliquer **Appliquer**. Le pastille vert "Connecté au serveur" apparaît.

**Installer la PWA** : Chrome/Edge → bouton "Installer" dans la barre d'adresse → l'app devient une appli locale, fonctionne hors-ligne.

### 2.6 — Comptes par défaut

| Email | Mot de passe | Rôle |
|---|---|---|
| `admin@kidzpos.com` | `admin123` | ADMIN |
| `sarah@kidzpos.com` | `sarah123` | EMPLOYEE (Magasin A) |
| `karim@kidzpos.com` | `karim123` | EMPLOYEE (Magasin B) |

⚠️ Changez-les immédiatement en production.

---

## 3. Déploiement Internet (multi-magasins distants)

### 3.1 — VPS recommandé
Un petit VPS suffit (1 vCPU, 1 Go RAM) : Hetzner, OVH, DigitalOcean...

### 3.2 — Backend en service systemd

```bash
# Builder le JAR
cd backend && ./mvnw clean package -DskipTests
# → backend/target/kidzpos-backend-1.0.0.jar

# Copier sur le VPS
scp target/kidzpos-backend-1.0.0.jar user@vps:/opt/kidzpos/

# Service systemd
sudo nano /etc/systemd/system/kidzpos.service
```

```ini
[Unit]
Description=KidzPOS Backend
After=network.target postgresql.service

[Service]
User=kidzpos
WorkingDirectory=/opt/kidzpos
Environment="JWT_SECRET=CHANGEZ-MOI-VRAIMENT-LONG-ET-ALEATOIRE"
Environment="DB_PASSWORD=motDePasseFort"
Environment="CORS_ALLOWED_ORIGINS=https://kidzpos.monshop.com"
ExecStart=/usr/bin/java -jar /opt/kidzpos/kidzpos-backend-1.0.0.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now kidzpos
sudo systemctl status kidzpos
```

### 3.3 — Reverse-proxy HTTPS (Caddy, le plus simple)

```caddy
# /etc/caddy/Caddyfile
kidzpos.monshop.com {
    # Frontend statique
    root * /var/www/kidzpos
    file_server
    try_files {path} /index.html

    # API + SSE proxiées vers Spring Boot
    handle /api/* {
        reverse_proxy localhost:8080 {
            flush_interval -1   # IMPORTANT pour SSE
        }
    }
    handle /actuator/* {
        reverse_proxy localhost:8080
    }
}
```

```bash
sudo cp -r dist/* /var/www/kidzpos/
sudo systemctl reload caddy
```

Caddy obtient automatiquement le certificat HTTPS Let's Encrypt.

### 3.4 — Configurer les caisses distantes
Dans **Paramètres** de chaque poste : `https://kidzpos.monshop.com`.

---

## 4. Comportement réseau

| Situation | Comportement |
|---|---|
| Serveur joignable | Toutes les actions vont en base + diffusées par SSE aux autres postes |
| Serveur HS / pas de réseau | Actions stockées en local (cache + outbox), bandeau jaune affiché |
| Serveur revient | Outbox flushée automatiquement, sync silencieuse |
| Modif sur poste A | Postes B, C, D reçoivent la mise à jour en <1s via SSE |
| Pas d'Internet pour le taux Ar/€ | Conserve le dernier taux connu + message visible |

---

## 5. Devise

Par défaut : **Ariary (Ar)**. Bascule Euro depuis Paramètres.
Conversion via `https://api.exchangerate.host` (gratuit, sans clé).
Bouton "Rafraîchir le taux" → essaie le backend, sinon le navigateur, sinon message offline.

---

## 6. Dépannage

| Problème | Solution |
|---|---|
| "Serveur injoignable" malgré backend lancé | Vérifier pare-feu : `sudo ufw allow 8080` |
| CORS bloqué | `CORS_ALLOWED_ORIGINS=*` ou domaine exact |
| SSE coupe toutes les 30s derrière nginx | Désactiver le buffering : `proxy_buffering off; proxy_read_timeout 24h;` |
| JWT invalid | Le secret a changé → tous les postes doivent se reconnecter |

---

## 7. Sauvegarde

- **PostgreSQL** : `pg_dump kidzpos > backup.sql` (cron quotidien recommandé)
- **Frontend** : Paramètres → "Exporter JSON" (sauvegarde locale du poste)

---

## 8. Tests d'intégration (TestContainers)

Les tests `src/test/java/com/kidzpos/**` valident les invariants critiques contre
un PostgreSQL réel démarré à la volée via TestContainers.

**Prérequis** : Docker en cours d'exécution (`docker info` doit répondre).

```bash
# Depuis backend/
mvn verify                                 # tous les tests + checks
mvn test -Dtest=IdempotentReplayTest       # un seul test
mvn test -Dtest='*Concurrent*'             # par pattern
```

Première exécution : Maven télécharge l'image `postgres:15-alpine` (~80 Mo).
Le container est réutilisé entre classes de test → ~5–10s/test après warm-up.

**Cas couverts** :
- `ConcurrentCheckoutTest` — 8 caisses simultanées sur stock=1 → 1 seule vente réussit.
- `IdempotentReplayTest` — double POST adjust/transfer avec même `clientMovementId` → 1 seule application.
- `RefundConcurrencyTest` — 4 refunds simultanés sur la même vente → 1 seul refund créé.

En CI (`.github/workflows/ci.yml`), Docker est natif sur `ubuntu-latest` — aucun
setup additionnel n'est requis. Les tests bloquent la fusion sur 4xx/5xx.

---

## 9. Observabilité (Prometheus)

Endpoint exposé sans auth pour scraping :

```bash
curl http://localhost:8080/actuator/prometheus
```

**Métriques métier custom** :

| Métrique | Sens |
|---|---|
| `kidzpos_stock_idempotent_replay_total` | Replays outbox absorbés (santé de la déduplication stock) |
| `kidzpos_sale_idempotent_replay_total` | Replays de checkout absorbés (idempotence `clientSaleId`) |
| `kidzpos_sale_seq_retry_total` | Collisions sur `uk_sale_store_seq` sous concurrence |
| `kidzpos_optimistic_lock_conflict_total` | Edits produit rejetés (412) faute d'`If-Match` à jour |

Plus toutes les métriques natives Spring Boot (`http_server_requests_seconds`,
JVM, HikariCP). À scraper depuis Prometheus :

```yaml
scrape_configs:
  - job_name: kidzpos
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['kidzpos-backend:8080']
```

⚠️ **Sécurité** : l'endpoint est `permitAll` côté SecurityConfig (EventSource-style
scraping). En prod publique, restreindre au CIDR du scraper via reverse-proxy.

**Logs structurés** : chaque ligne porte `[correlationId] [userId/storeId]` injecté
par `CorrelationIdFilter`. Le client peut passer un `X-Request-Id` pour tracer
de bout en bout — le backend le renvoie en réponse.
