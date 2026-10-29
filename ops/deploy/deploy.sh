#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy.sh — Déploie le CENTRAL sur la VM Oracle/Google depuis ta machine locale.
#
# Étapes :
#   1. Build du JAR (mvn) et du frontend (vite, avec VITE_API_URL=https://<DOMAIN>)
#   2. rsync : JAR → /srv/kidzpos/bin/, dist → /srv/kidzpos/dist/, Caddyfile + .service
#   3. systemctl restart kidzpos.service + reload caddy
#   4. Vérification : curl https://<DOMAIN>/actuator/health
#
# Usage :
#   ./ops/deploy/deploy.sh <ssh-user>@<vm-ip-or-host> <domain>
#
# Pré-requis sur la machine locale : git, npm, mvn, java 17, ssh, rsync.
# Pré-requis sur la VM             : install.sh déjà exécuté.
#
# Exemples :
#   ./ops/deploy/deploy.sh ubuntu@141.94.12.34 central.tondomaine.com
#   ./ops/deploy/deploy.sh opc@vm.oracle.cloud kidzpos.example.com
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <ssh-user>@<vm> <domain>" >&2; exit 1
fi
TARGET="$1"
DOMAIN="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"

log() { echo "▶ $*"; }
remote() { ssh -o StrictHostKeyChecking=accept-new "$TARGET" "$@"; }

log "1/5 build backend (mvn package)"
( cd "$ROOT/backend" && mvn -q -DskipTests package )
JAR="$(ls -t "$ROOT"/backend/target/kidzpos-backend-*.jar | grep -v '\.original$' | head -1)"
[ -f "$JAR" ] || { echo "JAR introuvable"; exit 1; }
echo "   → $JAR  ($(du -h "$JAR" | cut -f1))"

log "2/5 build frontend (vite) câblé sur https://$DOMAIN"
( cd "$ROOT" && VITE_API_URL="https://$DOMAIN" npm run build >/dev/null )
[ -f "$ROOT/dist/index.html" ] || { echo "dist/ vide"; exit 1; }
echo "   → $ROOT/dist/  ($(du -sh "$ROOT/dist" | cut -f1))"

log "3/5 push fichiers vers $TARGET"
# Backend JAR (bundle dans bin/)
rsync -az --info=stats0 "$JAR" "$TARGET:/tmp/kidzpos-backend.jar"
# Frontend (delete : nettoie les vieux assets hashés)
rsync -az --delete --info=stats0 "$ROOT/dist/" "$TARGET:/tmp/kidzpos-dist/"
# Caddyfile + unit systemd
rsync -az --info=stats0 "$HERE/Caddyfile" "$HERE/kidzpos.service" "$TARGET:/tmp/"

log "4/5 install sur la VM (sudo, déplacements + reload)"
remote bash -s -- "$DOMAIN" <<'REMOTE'
set -euo pipefail
DOMAIN="$1"

# JAR
sudo install -o kidzpos -g kidzpos -m 0644 /tmp/kidzpos-backend.jar /srv/kidzpos/bin/kidzpos-backend.jar

# Dist (rsync atomique : on remplace l'ancien dossier)
sudo rsync -a --delete /tmp/kidzpos-dist/ /srv/kidzpos/dist/
sudo chown -R kidzpos:kidzpos /srv/kidzpos/dist
sudo find /srv/kidzpos/dist -type d -exec chmod 755 {} +
sudo find /srv/kidzpos/dist -type f -exec chmod 644 {} +

# Caddyfile (injecte DOMAIN comme variable d'env Caddy via /etc/default/caddy)
sudo install -m 0644 /tmp/Caddyfile /etc/caddy/Caddyfile
if ! grep -q "^DOMAIN=" /etc/default/caddy 2>/dev/null; then
  echo "DOMAIN=$DOMAIN" | sudo tee -a /etc/default/caddy >/dev/null
else
  sudo sed -i "s|^DOMAIN=.*|DOMAIN=$DOMAIN|" /etc/default/caddy
fi
# Caddy lit /etc/default/caddy en tant qu'EnvironmentFile (override unit systemd)
sudo mkdir -p /etc/systemd/system/caddy.service.d
sudo tee /etc/systemd/system/caddy.service.d/override.conf >/dev/null <<EOF
[Service]
EnvironmentFile=-/etc/default/caddy
EOF

# Unit systemd backend
sudo install -m 0644 /tmp/kidzpos.service /etc/systemd/system/kidzpos.service
sudo systemctl daemon-reload

# Caddy d'abord (peut commencer à émettre le cert tant que LE backend ne sert pas encore)
sudo systemctl enable --now caddy
sudo systemctl reload caddy

# Backend
sudo systemctl enable --now kidzpos
sudo systemctl restart kidzpos

# Nettoyage
rm -f /tmp/kidzpos-backend.jar /tmp/Caddyfile /tmp/kidzpos.service
rm -rf /tmp/kidzpos-dist
REMOTE

log "5/5 vérification readiness (attente backend ≤ 120s)"
for i in $(seq 1 60); do
  if curl -sf "https://$DOMAIN/actuator/health" >/dev/null 2>&1; then
    HEALTH="$(curl -s "https://$DOMAIN/actuator/health")"
    echo "   ✓ https://$DOMAIN OK — $HEALTH"
    echo ""
    echo "✅ Déploiement terminé."
    echo "   UI       : https://$DOMAIN"
    echo "   Health   : https://$DOMAIN/actuator/health"
    echo "   Logs     : ssh $TARGET 'sudo journalctl -u kidzpos -f'"
    echo "   Caddy    : ssh $TARGET 'sudo journalctl -u caddy -f'"
    exit 0
  fi
  sleep 2
done
echo "   ⚠ readiness KO après 120s. Diagnostic :"
echo "     ssh $TARGET 'sudo systemctl status kidzpos caddy; sudo journalctl -u kidzpos -n 50'"
exit 1
