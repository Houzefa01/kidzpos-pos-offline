#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# install.sh — Installation des prérequis KidzPOS sur Arch Linux.
#
# Idempotent : ré-exécutable sans risque. Installe via pacman, init Postgres
# si jamais lancé, crée la DB + user, pose les caps réseau pour Caddy.
#
# Une fois lancé, tu peux utiliser directement les scripts bash dans
# ops/launch/ (ils marchent nativement sur Arch).
#
# Usage : sudo bash ops/launch/arch/install.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Lance avec sudo : sudo bash $0" >&2; exit 1
fi

log() { echo "▶ $*"; }
REAL_USER="${SUDO_USER:-$USER}"

log "1/6 mise à jour pacman + paquets de base"
pacman -Sy --noconfirm --needed \
  jdk17-openjdk maven \
  postgresql \
  caddy \
  nodejs npm \
  curl jq rsync

log "2/6 initialisation PostgreSQL (si jamais lancé)"
if [ ! -d /var/lib/postgres/data/base ]; then
  sudo -u postgres initdb -D /var/lib/postgres/data --locale=C.UTF-8 --encoding=UTF8
fi
systemctl enable --now postgresql

# Patiente jusqu'à 30s que Postgres accepte les connexions.
for _ in $(seq 1 15); do
  sudo -u postgres pg_isready -q && break
  sleep 2
done

log "3/6 DB kidzpos + role kidzpos"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='kidzpos'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE ROLE kidzpos WITH LOGIN PASSWORD 'kidzpos';"
for db in kidzpos kidzpos_central kidzpos_store_s1; do
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 \
    || sudo -u postgres createdb -O kidzpos "$db"
done

log "4/6 capacité réseau Caddy (autorise bind <1024 sans sudo)"
setcap cap_net_bind_service=+ep "$(command -v caddy)" 2>/dev/null || true

log "5/6 pare-feu (info)"
if command -v ufw >/dev/null 2>&1; then
  echo "   ufw détecté : penser à autoriser les ports utilisés (3000/4001 ou 443/4443)"
fi
if command -v firewall-cmd >/dev/null 2>&1; then
  echo "   firewalld détecté : zone 'home' typiquement OK pour le LAN privé"
fi

log "6/6 vérifications"
java --version | head -1
mvn --version | head -1
node --version
npm --version
caddy version | head -1
sudo -u postgres psql -tAc "SELECT version();" | head -1

cat <<EOF

✅ Installation Arch Linux terminée.

Prochaines étapes :
  cd "$(pwd)"
  sudo -u $REAL_USER npm ci
  sudo -u $REAL_USER bash -c '(cd backend && mvn -DskipTests package)'
  ./ops/launch/launch-both.sh         # HTTP local
  # ou
  ./ops/launch/launch-both-tls.sh     # HTTPS local (Caddy)

Stop : ./ops/launch/stop.sh
Logs : tail -f logs/central.log logs/store-s1.log

EOF
