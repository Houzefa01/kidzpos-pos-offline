#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# install.sh — Bootstrap d'une VM Ubuntu pour héberger le CENTRAL KidzPOS.
#
# Idempotent : peut être relancé sans risque. À exécuter UNE FOIS sur la VM
# (Oracle Cloud Always Free Ampere A1, ou équivalent).
#
# Ce qu'il fait :
#   1. Installe Java 17, PostgreSQL, Caddy
#   2. Active un swap de 1 Go (filet de sécurité OOM)
#   3. Crée l'utilisateur système `kidzpos` + ses dossiers (/srv/kidzpos)
#   4. Provisionne la DB Postgres `kidzpos_central` + user `kidzpos`
#   5. Configure le pare-feu (ufw) : SSH/22 + HTTPS/443 + HTTP/80 uniquement
#   6. Installe l'unit systemd kidzpos.service (sans le démarrer encore)
#
# Pré-requis : Ubuntu 22.04+ ou Debian 12+, accès sudo, internet.
# Usage : sudo bash install.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Lance avec sudo : sudo bash $0" >&2; exit 1
fi

KIDZPOS_USER="${KIDZPOS_USER:-kidzpos}"
KIDZPOS_HOME="${KIDZPOS_HOME:-/srv/kidzpos}"
DB_NAME="${DB_NAME:-kidzpos_central}"
DB_USER="${DB_USER:-kidzpos}"
HERE="$(cd "$(dirname "$0")" && pwd)"

log() { echo "▶ $*"; }

log "1/6 paquets système"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
  openjdk-17-jre-headless postgresql postgresql-contrib \
  debian-keyring debian-archive-keyring apt-transport-https curl gnupg ufw rsync

if ! command -v caddy >/dev/null 2>&1; then
  log "   → installation Caddy (dépôt officiel)"
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq
  apt-get install -y caddy
fi

log "2/6 swap 1 Go (évite l'OOM si JVM bursts)"
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "vm.swappiness=10" > /etc/sysctl.d/99-kidzpos-swappiness.conf
  sysctl -p /etc/sysctl.d/99-kidzpos-swappiness.conf >/dev/null
else
  echo "   swap déjà présent — ignoré"
fi

log "3/6 utilisateur système '$KIDZPOS_USER'"
if ! id "$KIDZPOS_USER" >/dev/null 2>&1; then
  useradd --system --home "$KIDZPOS_HOME" --shell /usr/sbin/nologin "$KIDZPOS_USER"
fi
mkdir -p "$KIDZPOS_HOME"/{bin,dist,logs}
chown -R "$KIDZPOS_USER:$KIDZPOS_USER" "$KIDZPOS_HOME"
chmod 750 "$KIDZPOS_HOME"

log "4/6 PostgreSQL : DB '$DB_NAME' + user '$DB_USER'"
systemctl enable --now postgresql >/dev/null 2>&1 || true
# Génère un mot de passe random pour l'utilisateur DB s'il n'existe pas.
DB_PWFILE="$KIDZPOS_HOME/.db_password"
if [ ! -f "$DB_PWFILE" ]; then
  DB_PASSWORD="$(openssl rand -hex 24)"
  echo "$DB_PASSWORD" > "$DB_PWFILE"
  chown "$KIDZPOS_USER:$KIDZPOS_USER" "$DB_PWFILE"
  chmod 600 "$DB_PWFILE"
fi
DB_PASSWORD="$(cat "$DB_PWFILE")"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" 2>/dev/null | grep -q 1 \
  || sudo -u postgres psql -c "CREATE ROLE $DB_USER WITH LOGIN PASSWORD '$DB_PASSWORD';" >/dev/null
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" 2>/dev/null | grep -q 1 \
  || sudo -u postgres createdb -O "$DB_USER" "$DB_NAME" >/dev/null
sudo -u postgres psql -c "ALTER ROLE $DB_USER WITH PASSWORD '$DB_PASSWORD';" >/dev/null

log "5/6 pare-feu (ufw)"
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null      # SSH
ufw allow 80/tcp >/dev/null      # HTTP (Let's Encrypt challenge + redirect)
ufw allow 443/tcp >/dev/null     # HTTPS (Caddy)
ufw --force enable >/dev/null
echo "   → règles : $(ufw status | grep -E 'ALLOW|DENY' | wc -l) actives"

log "6/6 unit systemd kidzpos.service"
install -m 0644 "$HERE/kidzpos.service" /etc/systemd/system/kidzpos.service
# Pose un .env vide si absent (sera complété par l'opérateur).
ENVFILE="$KIDZPOS_HOME/.env"
if [ ! -f "$ENVFILE" ]; then
  cp "$HERE/.env.example" "$ENVFILE"
  # Injecte le mot de passe DB généré, les autres secrets restent à compléter.
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$DB_PASSWORD|" "$ENVFILE"
  chown "$KIDZPOS_USER:$KIDZPOS_USER" "$ENVFILE"
  chmod 600 "$ENVFILE"
  echo "   → $ENVFILE créé. ⚠ ÉDITER : DOMAIN, JWT_SECRET, KIDZPOS_ADMIN_PASSWORD."
fi
systemctl daemon-reload

cat <<EOF

✅ Installation terminée.

Prochaine étape (depuis ta machine locale) :
  ./ops/deploy/deploy.sh <user>@<ip-vm> <domain>

Avant le premier deploy :
  1. Pointer un DNS A record   central.tondomaine.com → <ip-vm>
  2. Éditer $ENVFILE sur la VM :
        DOMAIN=central.tondomaine.com
        JWT_SECRET=\$(openssl rand -hex 32)
        KIDZPOS_ADMIN_PASSWORD=<mot de passe fort>
        KIDZPOS_SYNC_INBOUND_API_KEY=  (laisser VIDE → bascule en mode per-store)
  3. Le deploy.sh local s'occupe du reste (JAR, frontend, Caddyfile, restart).

EOF
