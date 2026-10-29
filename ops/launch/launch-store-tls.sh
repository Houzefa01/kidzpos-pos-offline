#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-store-tls.sh — Démarre un nœud STORE (s1) en HTTPS via Caddy.
#
#   Caddy        :4443 (HTTPS, cert local CA)
#   Backend      :8081 (HTTP loopback)
#   Dist         dist-store/  (servi par Caddy)
#   DB           kidzpos_store_s1
#   Sync         push/pull → ${KIDZPOS_SYNC_CENTRAL_URL:-https://localhost} (TLS)
#
# Si le central est en HTTPS avec un cert local CA, le store (Java/HttpClient)
# doit faire confiance à cette CA. Le plus simple : exposer la CA via
# `-Djavax.net.ssl.trustStore` ou utiliser KIDZPOS_SYNC_CENTRAL_URL en HTTP
# loopback si central+store sont sur la même machine (KIDZPOS_SYNC_CENTRAL_URL=
# http://localhost:8080 → court-circuite TLS pour la sync intra-machine).
# Défaut : on garde HTTP loopback pour la sync (intra-machine), HTTPS pour les
# clients navigateur.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_java; require_caddy; resolve_jar
DIST="${DIST_STORE:-$ROOT/dist-store}"
require_dist "$DIST"

# ── Identité / profil ────────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=local
export SERVER_PORT="${SERVER_PORT:-8081}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
export KIDZPOS_NODE_ID="${KIDZPOS_NODE_ID:-store-s1}"
export KIDZPOS_NODE_STORE_ID="${KIDZPOS_NODE_STORE_ID:-s1}"
HTTPS_PORT="${HTTPS_PORT:-4443}"

# ── Secrets ──────────────────────────────────────────────────────────────────
export JWT_SECRET="${JWT_SECRET:-store-s1-jwt-secret-must-be-at-least-32-bytes-long-abc}"
export KIDZPOS_SYNC_API_KEY="${KIDZPOS_SYNC_API_KEY:-test-sync-key-shared-123456789}"
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"

# ── Base de données ──────────────────────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_store_s1}" DB_USER="${DB_USER:-kidzpos}" DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ── Sync vers le central ─────────────────────────────────────────────────────
# Défaut : HTTP loopback (intra-machine, court-circuite TLS et sa CA). En 2
# machines séparées avec central TLS, fournir KIDZPOS_SYNC_CENTRAL_URL=
# https://<ip-central> ET un truststore Java qui contient la CA Caddy.
export KIDZPOS_SYNC_CENTRAL_URL="${KIDZPOS_SYNC_CENTRAL_URL:-http://localhost:8080}"
export KIDZPOS_SYNC_PUSH_ENABLED=true
export KIDZPOS_SYNC_PULL_ENABLED=true
export KIDZPOS_SYNC_INBOX_ENABLED=true
export KIDZPOS_SYNC_INTERVAL_MS="${KIDZPOS_SYNC_INTERVAL_MS:-5000}"
export KIDZPOS_SYNC_PULL_INTERVAL_MS="${KIDZPOS_SYNC_PULL_INTERVAL_MS:-10000}"
export KIDZPOS_SYNC_INBOX_INTERVAL_MS="${KIDZPOS_SYNC_INBOX_INTERVAL_MS:-10000}"

# ── Cookie : HTTPS → Secure=true ─────────────────────────────────────────────
export KIDZPOS_DEBUG_ENABLED="${KIDZPOS_DEBUG_ENABLED:-true}"
export KIDZPOS_AUTH_COOKIE_NAME="${KIDZPOS_AUTH_COOKIE_NAME:-kidzpos_rt_s1}"
export KIDZPOS_AUTH_COOKIE_SAME_SITE="${KIDZPOS_AUTH_COOKIE_SAME_SITE:-Lax}"
export KIDZPOS_AUTH_COOKIE_SECURE="${KIDZPOS_AUTH_COOKIE_SECURE:-true}"

LAN="$(lan_ip)"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://localhost:$HTTPS_PORT,https://127.0.0.1:$HTTPS_PORT,https://$LAN:$HTTPS_PORT}"

if port_busy "$HTTPS_PORT"; then die "port $HTTPS_PORT déjà occupé (store TLS déjà lancé ? './ops/launch/stop.sh')"; fi
if port_busy "$SERVER_PORT"; then die "port backend $SERVER_PORT déjà occupé"; fi

echo "▶ STORE s1 TLS (hors-ligne)  HTTPS=:$HTTPS_PORT (Caddy)  backend=loopback:$SERVER_PORT  DB=$DB_NAME  central=$KIDZPOS_SYNC_CENTRAL_URL  LAN=$LAN"
start_backend store-s1
wait_port "$SERVER_PORT" "store backend (loopback)" || true
start_caddy store-s1 "$HTTPS_PORT" "$SERVER_PORT" "$DIST"
wait_port "$HTTPS_PORT" "store HTTPS (Caddy)" || true
echo "  → local : https://localhost:$HTTPS_PORT"
echo "  → LAN   : https://$LAN:$HTTPS_PORT"
echo "  → admin@kidzpos.com / \$KIDZPOS_ADMIN_PASSWORD   ·   stop: $ROOT/ops/launch/stop.sh"
echo "  ⚠ pour l'accès LAN, le frontend doit être buildé en HTTPS : ./ops/launch/build-lan.sh store --https"
