# ops/deploy — Déploiement du CENTRAL sur une VM cloud

Setup complet pour héberger le **central KidzPOS** sur une VM publique
(Oracle Cloud Always Free, Google e2-micro, ou équivalent). Le frontend et
l'API sont servis sous la **même origine HTTPS** par Caddy → zéro CORS, cookie
`Secure=true` + `SameSite=Lax`, Let's Encrypt automatique.

## Architecture cible

```
                        Internet
                           │
                  ┌────────▼─────────┐
                  │   Cloudflare DNS │  (A record : central.tondomaine.com → IP)
                  └────────┬─────────┘
                           │
                  ┌────────▼─────────────────────────────┐
                  │  VM publique (Oracle/Google)         │
                  │                                       │
                  │   Caddy :443  ← Let's Encrypt cert    │
                  │     ├─ /api/*  → 127.0.0.1:8080      │
                  │     └─ /       → /srv/kidzpos/dist    │
                  │                                       │
                  │   Spring Boot 127.0.0.1:8080          │
                  │   PostgreSQL 127.0.0.1:5432           │
                  │   ufw : 22, 80, 443 uniquement        │
                  └───────────────────────────────────────┘
                           ▲
                           │ HTTPS (push/pull /api/sync)
                           │
              ┌────────────┴────────────┐
              │                          │
       ┌──────▼───────┐          ┌──────▼───────┐
       │ Store s1     │          │ Store s2     │
       │ (LAN privé)  │          │ (LAN privé)  │
       │ caisses 1..N │          │ caisses 1..N │
       └──────────────┘          └──────────────┘
```

## Fichiers

| Fichier | Rôle |
|---|---|
| `install.sh` | Bootstrap VM : Java/Postgres/Caddy/swap/ufw/user `kidzpos`/DB/unit systemd |
| `Caddyfile` | Caddyfile prod : same-origin SPA + reverse proxy + Let's Encrypt |
| `kidzpos.service` | Unit systemd du backend (loopback, EnvironmentFile, restart auto) |
| `.env.example` | Template des secrets — install.sh le copie en `/srv/kidzpos/.env` |
| `deploy.sh` | Lance depuis ta machine locale : build mvn+vite, scp, restart |
| `issue-store-key.sh` | Génère une clé d'API de sync pour un store via `/api/sync/keys` |

---

## Procédure end-to-end (15 minutes)

### Étape 1 — Provisionner la VM

**Oracle Cloud Always Free (recommandé, 24 Go RAM gratuits à vie)** :

1. https://www.oracle.com/cloud/free/ → créer un compte
2. Choisir une **région avec capacité ARM** (Marseille, Madrid, Stockholm, Mumbai, Singapore)
3. *Compute → Instances → Create instance*
4. Shape : **Ampere A1.Flex** — 4 OCPU + 24 Go RAM
5. Image : **Ubuntu 22.04** ou plus récent
6. SSH key : coller ta clé publique (`~/.ssh/id_ed25519.pub`)
7. Network : « Assign a public IPv4 address »
8. Récupérer l'IP publique affichée à la fin

**Google Cloud e2-micro (plan B si Oracle KO)** :

1. https://cloud.google.com/free/ → projet
2. *Compute Engine → VM instances → Create*
3. Region : `us-central1` (Iowa) · machine type : `e2-micro`
4. OS : Ubuntu 22.04 · 30 Go Standard persistent disk
5. Firewall : cocher « Allow HTTPS traffic »

### Étape 2 — DNS

Pointer un A-record vers l'IP de la VM. Solutions gratuites :

- **DuckDNS** (https://www.duckdns.org) → `kidzpos.duckdns.org`
- **Cloudflare DNS** si tu as déjà un domaine
- **No-IP**

Vérifier la propagation : `dig +short central.tondomaine.com` doit retourner ton IP.

### Étape 3 — Bootstrap de la VM

```bash
# Depuis ta machine, copier ce dossier sur la VM
scp -r ops/deploy ubuntu@<ip-vm>:/tmp/

# Se connecter à la VM et lancer install.sh
ssh ubuntu@<ip-vm>
sudo bash /tmp/deploy/install.sh
```

L'install.sh installe Java/Postgres/Caddy/swap, crée user `kidzpos`, DB
`kidzpos_central` avec mot de passe random, pose `/srv/kidzpos/.env` et le
unit systemd. Durée : ~3 min.

### Étape 4 — Configurer `/srv/kidzpos/.env` sur la VM

Toujours en SSH sur la VM :

```bash
sudo -u kidzpos nano /srv/kidzpos/.env
```

Renseigner :
```
DOMAIN=central.tondomaine.com
JWT_SECRET=<openssl rand -hex 32>
KIDZPOS_ADMIN_PASSWORD=<mot de passe fort de l'admin>
# KIDZPOS_SYNC_INBOUND_API_KEY=        ← laisser VIDE en multi-store (mode per-store)
```

> 💡 Sur la VM : `openssl rand -hex 32` génère un JWT_SECRET fort.

### Étape 5 — Déployer depuis ta machine locale

```bash
# Sur ta machine (pas la VM)
./ops/deploy/deploy.sh ubuntu@<ip-vm> central.tondomaine.com
```

Le script :
1. Build `mvn package` du JAR
2. Build `vite` du frontend avec `VITE_API_URL=https://central.tondomaine.com`
3. `rsync` JAR + dist + Caddyfile + unit systemd vers la VM
4. `systemctl restart kidzpos` + `reload caddy`
5. Attend `https://central.tondomaine.com/actuator/health` UP

Première fois, Caddy met ~30s à émettre le cert Let's Encrypt — le script
boucle jusqu'à 120s.

### Étape 6 — Vérifier

```bash
curl https://central.tondomaine.com/actuator/health
# {"status":"UP"}

# Ouvrir https://central.tondomaine.com dans un navigateur
# → login : admin@kidzpos.com / <KIDZPOS_ADMIN_PASSWORD>
```

---

## Maintenance courante

```bash
# Mises à jour applicatives (code/JAR/frontend)
./ops/deploy/deploy.sh ubuntu@<ip-vm> central.tondomaine.com

# Logs en direct
ssh ubuntu@<ip-vm> 'sudo journalctl -u kidzpos -f'
ssh ubuntu@<ip-vm> 'sudo journalctl -u caddy -f'

# Backup DB (à mettre en cron)
ssh ubuntu@<ip-vm> 'sudo -u postgres pg_dump kidzpos_central | gzip' \
  > backups/central-$(date +%F).sql.gz

# Patches OS
ssh ubuntu@<ip-vm> 'sudo apt update && sudo apt upgrade -y && sudo reboot'
```

---

## Provisionner un store (génération de la clé de sync)

```bash
# Génère une clé pour le store 's1', l'admin la note immédiatement
./ops/deploy/issue-store-key.sh https://central.tondomaine.com \
    admin@kidzpos.com '<mot-de-passe-admin>' s1 'Magasin Antananarivo'
```

La clé est affichée UNE SEULE FOIS (le central n'en stocke qu'un hash bcrypt).
À reporter dans le `.env` du store. Voir [Configurer un store](../launch/README.md#multi-machine).

---

## Coûts attendus

| Provider | Coût mensuel pour KidzPOS |
|---|---|
| Oracle Cloud Always Free | **0 €/mois** à vie tant qu'on reste dans le quota |
| Google Cloud e2-micro | **0 €/mois** à vie tant qu'on reste dans le quota |
| Domaine `*.duckdns.org` | **0 €** |
| Domaine custom | 8-12 €/an (optionnel) |

→ KidzPOS central hébergé **gratuit à vie** si Oracle/Google + DuckDNS.
