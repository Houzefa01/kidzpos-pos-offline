#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-central-tls.sh — Démarre le nœud CENTRAL en HTTPS via Caddy.
#
#   Caddy        :443 (HTTPS, cert local CA)         ← seule porte d'entrée
#   Backend      :8080 (HTTP loopback, ${SERVER_ADDRESS:-127.0.0.1})
#   Dist         dist-central/  (servi par Caddy, MÊME origine que /api)
#   DB           kidzpos_central
#
# Pourquoi Caddy : même origine HTTPS pour le frontend ET l'API → plus de CORS,
# cookie Secure=true + SameSite=Lax (config la plus stricte qui marche).
#
# Démarrage 100% hors-ligne : Caddy génère ses certs en local (`tls internal`).
# Premier lancement : Caddy peut demander sudo pour installer sa CA dans le
# trust store système. Voir README § HTTPS pour les clients LAN distants.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_java; require_caddy; resolve_jar
DIST="${DIST_CENTRAL:-$ROOT/dist-central}"
require_dist "$DIST"

# ── Identité / profil ────────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=central
export SERVER_PORT="${SERVER_PORT:-8080}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"   # loopback : Caddy seul accède au backend
export KIDZPOS_NODE_ID="${KIDZPOS_NODE_ID:-central}"
export KIDZPOS_NODE_STORE_ID=""
HTTPS_PORT="${HTTPS_PORT:-443}"

# ── Secrets (LAN/test — surcharger en prod) ──────────────────────────────────
export JWT_SECRET="${JWT_SECRET:-central-jwt-secret-must-be-at-least-32-bytes-long-xyz}"
export KIDZPOS_SYNC_INBOUND_API_KEY="${KIDZPOS_SYNC_INBOUND_API_KEY:-test-sync-key-shared-123456789}"
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"

# ── Base de données (locale) ─────────────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_central}" DB_USER="${DB_USER:-kidzpos}" DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ── Inbox processor ──────────────────────────────────────────────────────────
export KIDZPOS_SYNC_INBOX_ENABLED=true
export KIDZPOS_SYNC_INBOX_INTERVAL_MS="${KIDZPOS_SYNC_INBOX_INTERVAL_MS:-5000}"
export KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS="${KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS:-2000}"

# ── Cookie : HTTPS → Secure=true, SameSite=Lax (même origine via Caddy) ──────
# Lax suffit ici car frontend + API sont sous la MÊME origine (servies par Caddy).
# Si un jour le front est sur un sous-domaine séparé, basculer en None.
export KIDZPOS_DEBUG_ENABLED="${KIDZPOS_DEBUG_ENABLED:-true}"
export KIDZPOS_AUTH_COOKIE_NAME="${KIDZPOS_AUTH_COOKIE_NAME:-kidzpos_rt_central}"
export KIDZPOS_AUTH_COOKIE_SAME_SITE="${KIDZPOS_AUTH_COOKIE_SAME_SITE:-Lax}"
export KIDZPOS_AUTH_COOKIE_SECURE="${KIDZPOS_AUTH_COOKIE_SECURE:-true}"

# ── CORS : même origine, donc vide par défaut. On laisse loopback pour les ───
# tests curl locaux non-navigateur.
LAN="$(lan_ip)"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://localhost:$HTTPS_PORT,https://127.0.0.1:$HTTPS_PORT,https://$LAN:$HTTPS_PORT}"

if port_busy "$HTTPS_PORT"; then die "port $HTTPS_PORT déjà occupé (central TLS déjà lancé ? './ops/launch/stop.sh')"; fi
if port_busy "$SERVER_PORT"; then die "port backend $SERVER_PORT déjà occupé"; fi

echo "▶ CENTRAL TLS (hors-ligne)  HTTPS=:$HTTPS_PORT (Caddy)  backend=loopback:$SERVER_PORT  DB=$DB_NAME  LAN=$LAN"
start_backend central
wait_port "$SERVER_PORT" "central backend (loopback)" || true
start_caddy central "$HTTPS_PORT" "$SERVER_PORT" "$DIST"
wait_port "$HTTPS_PORT" "central HTTPS (Caddy)" || true
echo "  → local : https://localhost:$HTTPS_PORT"
echo "  → LAN   : https://$LAN:$HTTPS_PORT   (clients : installer la CA locale Caddy, cf README)"
echo "  → admin@kidzpos.com / \$KIDZPOS_ADMIN_PASSWORD   ·   stop: $ROOT/ops/launch/stop.sh"
echo "  ⚠ pour l'accès LAN, le frontend doit être buildé en HTTPS : ./ops/launch/build-lan.sh central --https"
