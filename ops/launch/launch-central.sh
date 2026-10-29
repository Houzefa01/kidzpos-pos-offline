#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-central.sh — Démarre le nœud CENTRAL, 100% HORS-LIGNE.
#
#   API :8080 (profile central) + UI :3000 (servie par Node natif)
#   DB  : kidzpos_central (Postgres local)
#   Inbox processor activé (matérialise les events poussés par les stores).
#
# Aucun accès internet : JAR local + Postgres local + serveur statique natif.
# Tout est surchargeable par variable d'env (SERVER_PORT, FRONTEND_PORT, DB_*, …).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_java; require_node; resolve_jar
DIST="${DIST_CENTRAL:-$ROOT/dist-central}"
require_dist "$DIST"

# ── Identité / profil ────────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=central
export SERVER_PORT="${SERVER_PORT:-8080}"
export KIDZPOS_NODE_ID="${KIDZPOS_NODE_ID:-central}"
export KIDZPOS_NODE_STORE_ID=""                       # vide → agrège tous les stores
FRONTEND_PORT="${FRONTEND_PORT:-3000}"

# ── Secrets (LAN/test — surcharger en prod) ──────────────────────────────────
export JWT_SECRET="${JWT_SECRET:-central-jwt-secret-must-be-at-least-32-bytes-long-xyz}"
export KIDZPOS_SYNC_INBOUND_API_KEY="${KIDZPOS_SYNC_INBOUND_API_KEY:-test-sync-key-shared-123456789}"
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"

# ── Base de données (locale) ─────────────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_central}" DB_USER="${DB_USER:-kidzpos}" DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ── Inbox processor (matérialisation des push entrants) ──────────────────────
export KIDZPOS_SYNC_INBOX_ENABLED=true
export KIDZPOS_SYNC_INBOX_INTERVAL_MS="${KIDZPOS_SYNC_INBOX_INTERVAL_MS:-5000}"
export KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS="${KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS:-2000}"

# ── Cookie : LAN HTTP par défaut (Secure=true casserait le cookie hors localhost) ──
# Lax + Secure=false fonctionne en localhost ET en IP LAN sur HTTP. Pour une prod
# HTTPS derrière reverse-proxy : exporter SAME_SITE=None + SECURE=true.
export KIDZPOS_DEBUG_ENABLED="${KIDZPOS_DEBUG_ENABLED:-true}"
export KIDZPOS_AUTH_COOKIE_NAME="${KIDZPOS_AUTH_COOKIE_NAME:-kidzpos_rt_central}"
export KIDZPOS_AUTH_COOKIE_SAME_SITE="${KIDZPOS_AUTH_COOKIE_SAME_SITE:-Lax}"
export KIDZPOS_AUTH_COOKIE_SECURE="${KIDZPOS_AUTH_COOKIE_SECURE:-false}"

# ── CORS : autorise localhost ET l'IP LAN (frontend + API) pour le multi-machine ──
LAN="$(lan_ip)"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:$FRONTEND_PORT,http://127.0.0.1:$FRONTEND_PORT,http://localhost:$SERVER_PORT,http://$LAN:$FRONTEND_PORT,http://$LAN:$SERVER_PORT,http://192.168.*.*:$FRONTEND_PORT,http://10.*.*.*:$FRONTEND_PORT,http://172.*.*.*:$FRONTEND_PORT}"

if port_busy "$SERVER_PORT"; then die "port $SERVER_PORT déjà occupé (central déjà lancé ? './ops/launch/stop.sh')"; fi

echo "▶ CENTRAL (hors-ligne)  profile=central  API=:$SERVER_PORT  UI=:$FRONTEND_PORT  DB=$DB_NAME  LAN=$LAN"
start_backend central
start_frontend central "$DIST" "$FRONTEND_PORT"
wait_port "$SERVER_PORT" "central API" || true
echo "  → local : http://localhost:$FRONTEND_PORT"
echo "  → LAN   : http://$LAN:$FRONTEND_PORT   (depuis les autres machines)   API: http://$LAN:$SERVER_PORT"
echo "  → admin@kidzpos.com / \$KIDZPOS_ADMIN_PASSWORD   ·   stop: $ROOT/ops/launch/stop.sh"
echo "  ⚠ pour l'accès LAN, le frontend doit être buildé sur l'IP : ./ops/launch/build-lan.sh central"
