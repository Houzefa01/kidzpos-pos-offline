#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build-lan.sh — (Re)build des frontends câblés sur une adresse ATTEIGNABLE
# depuis les autres machines du LAN (IP, pas localhost).
#
# Pourquoi : `VITE_API_URL` est figé au build et prioritaire. S'il vaut
# "http://localhost:8080", le navigateur d'une AUTRE machine appelle SON propre
# localhost → échec. On rebuild donc en pointant l'IP LAN de ce serveur.
#
# Build = local (Vite + node_modules déjà présents) → AUCUN internet requis.
#
# Usage :
#   ./ops/launch/build-lan.sh [central|store|both] [IP_LAN] [--https]
#
# Modes :
#   HTTP (défaut)  →  VITE_API_URL=http://<IP>:8080  (ou :8081 pour le store)
#   --https        →  VITE_API_URL=https://<IP>      (ou https://<IP>:4443)
#                     ↳ même origine Caddy → plus de CORS, cookie Secure=true
#
# Surcharge fine par env (utile en topologie 2 machines) :
#   CENTRAL_API_URL   (défaut calculé selon le mode)
#   STORE_API_URL     (défaut calculé selon le mode)
#
# Exemples :
#   ./ops/launch/build-lan.sh both                       # HTTP, IP auto
#   ./ops/launch/build-lan.sh both 192.168.1.10 --https  # HTTPS, IP explicite
#   ./ops/launch/build-lan.sh central --https            # central HTTPS, IP auto
#   STORE_API_URL=https://192.168.1.20 ./ops/launch/build-lan.sh store --https
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

TARGET="both"
IP=""
HTTPS=0
for arg in "$@"; do
  case "$arg" in
    --https) HTTPS=1 ;;
    central|store|both) TARGET="$arg" ;;
    *) IP="$arg" ;;  # premier argument non reconnu = IP
  esac
done
[ -z "$IP" ] && IP="$(lan_ip)"

if [ "$HTTPS" -eq 1 ]; then
  CENTRAL_DEFAULT="https://$IP"          # Caddy central : port 443 (omis)
  STORE_DEFAULT="https://$IP:4443"       # Caddy store
else
  CENTRAL_DEFAULT="http://$IP:8080"
  STORE_DEFAULT="http://$IP:8081"
fi
CENTRAL_API_URL="${CENTRAL_API_URL:-$CENTRAL_DEFAULT}"
STORE_API_URL="${STORE_API_URL:-$STORE_DEFAULT}"

command -v npm >/dev/null 2>&1 || die "npm requis pour (re)builder le frontend"
[ -d "$ROOT/node_modules" ] || die "node_modules absent → 'npm ci' une fois (avec internet)"

build_one() { # $1=nom_dist $2=api_url
  echo "  → build $1 câblé sur $2"
  ( cd "$ROOT" && VITE_API_URL="$2" npm run build >/dev/null )
  rm -rf "$ROOT/$1"
  cp -r "$ROOT/dist" "$ROOT/$1"
}

MODE=$([ "$HTTPS" -eq 1 ] && echo "HTTPS (Caddy)" || echo "HTTP")
echo "▶ build-lan [$MODE] : IP=$IP  central=$CENTRAL_API_URL  store=$STORE_API_URL"
case "$TARGET" in
  central) build_one dist-central "$CENTRAL_API_URL" ;;
  store)   build_one dist-store   "$STORE_API_URL" ;;
  both)    build_one dist-central "$CENTRAL_API_URL"; build_one dist-store "$STORE_API_URL" ;;
  *) die "cible inconnue '$TARGET' (central|store|both)" ;;
esac
LAUNCH=$([ "$HTTPS" -eq 1 ] && echo "launch-both-tls.sh" || echo "launch-both.sh")
echo "✓ build terminé. Lancer : ./ops/launch/$LAUNCH"
