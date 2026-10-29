#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-store.sh — Démarre un nœud STORE (s1), 100% HORS-LIGNE.
#
#   API :8081 (profile local, storeId=s1) + UI :4001 (servie par Node natif)
#   DB  : kidzpos_store_s1 (Postgres local)
#   Sync push/pull vers le central (http://localhost:8080) + inbox local.
#
# La sync est de la mise en réseau LOCALE (store ↔ central sur la même machine /
# le même LAN). Le DÉMARRAGE lui-même ne requiert aucun internet : si le central
# est injoignable, le push/pull se contente de retenter (backoff) — le store
# démarre et fonctionne quand même.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_java; require_node; resolve_jar
DIST="${DIST_STORE:-$ROOT/dist-store}"
require_dist "$DIST"

# ── Identité / profil ────────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=local
export SERVER_PORT="${SERVER_PORT:-8081}"
export KIDZPOS_NODE_ID="${KIDZPOS_NODE_ID:-store-s1}"
export KIDZPOS_NODE_STORE_ID="${KIDZPOS_NODE_STORE_ID:-s1}"   # store-scoped → isolation magasin
FRONTEND_PORT="${FRONTEND_PORT:-4001}"

# ── Secrets (LAN/test — surcharger en prod) ──────────────────────────────────
export JWT_SECRET="${JWT_SECRET:-store-s1-jwt-secret-must-be-at-least-32-bytes-long-abc}"
export KIDZPOS_SYNC_API_KEY="${KIDZPOS_SYNC_API_KEY:-test-sync-key-shared-123456789}"
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"

# ── Base de données (locale) ─────────────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_store_s1}" DB_USER="${DB_USER:-kidzpos}" DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ── Sync vers le central (LAN local) ─────────────────────────────────────────
export KIDZPOS_SYNC_CENTRAL_URL="${KIDZPOS_SYNC_CENTRAL_URL:-http://localhost:8080}"
export KIDZPOS_SYNC_PUSH_ENABLED=true
export KIDZPOS_SYNC_PULL_ENABLED=true
export KIDZPOS_SYNC_INBOX_ENABLED=true
export KIDZPOS_SYNC_INTERVAL_MS="${KIDZPOS_SYNC_INTERVAL_MS:-5000}"
export KIDZPOS_SYNC_PULL_INTERVAL_MS="${KIDZPOS_SYNC_PULL_INTERVAL_MS:-10000}"
export KIDZPOS_SYNC_INBOX_INTERVAL_MS="${KIDZPOS_SYNC_INBOX_INTERVAL_MS:-10000}"

# ── Cookie : LAN HTTP par défaut (cf launch-central.sh) ──────────────────────
export KIDZPOS_DEBUG_ENABLED="${KIDZPOS_DEBUG_ENABLED:-true}"
export KIDZPOS_AUTH_COOKIE_NAME="${KIDZPOS_AUTH_COOKIE_NAME:-kidzpos_rt_s1}"
export KIDZPOS_AUTH_COOKIE_SAME_SITE="${KIDZPOS_AUTH_COOKIE_SAME_SITE:-Lax}"
export KIDZPOS_AUTH_COOKIE_SECURE="${KIDZPOS_AUTH_COOKIE_SECURE:-false}"

# ── CORS : localhost + IP LAN (frontend + API) ───────────────────────────────
LAN="$(lan_ip)"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:$FRONTEND_PORT,http://127.0.0.1:$FRONTEND_PORT,http://localhost:$SERVER_PORT,http://$LAN:$FRONTEND_PORT,http://$LAN:$SERVER_PORT,http://192.168.*.*:$FRONTEND_PORT,http://10.*.*.*:$FRONTEND_PORT,http://172.*.*.*:$FRONTEND_PORT}"

if port_busy "$SERVER_PORT"; then die "port $SERVER_PORT déjà occupé (store déjà lancé ? './ops/launch/stop.sh')"; fi

echo "▶ STORE s1 (hors-ligne)  profile=local  API=:$SERVER_PORT  UI=:$FRONTEND_PORT  DB=$DB_NAME  central=$KIDZPOS_SYNC_CENTRAL_URL  LAN=$LAN"
start_backend store-s1
start_frontend store-s1 "$DIST" "$FRONTEND_PORT"
wait_port "$SERVER_PORT" "store API" || true
echo "  → local : http://localhost:$FRONTEND_PORT"
echo "  → LAN   : http://$LAN:$FRONTEND_PORT   (depuis les autres machines)   API: http://$LAN:$SERVER_PORT"
echo "  → admin@kidzpos.com / \$KIDZPOS_ADMIN_PASSWORD   ·   stop: $ROOT/ops/launch/stop.sh"
echo "  ⚠ pour l'accès LAN, le frontend doit être buildé sur l'IP : ./ops/launch/build-lan.sh store"
