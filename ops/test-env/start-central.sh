#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-central.sh — Démarre un serveur CENTRAL en LOCAL pour test sync.
#
#   Profil       : central
#   Port         : 8080
#   StoreId      : <vide> (le central agrège, pas de magasin attaché)
#   Database     : kidzpos_central (créée par init-test-databases.sql)
#   Logs         : <repo>/logs/central.log
#
# Lance le JAR existant (backend/target/kidzpos-backend-1.0.0.jar).
# Si absent, indique comment le builder.
# ─────────────────────────────────────────────────────────────────────────────
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

JAR=$(ls -t "$ROOT"/backend/target/kidzpos-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "❌ JAR introuvable. Build d'abord :"
    echo "   cd $ROOT/backend && mvn -DskipTests package"
    exit 1
fi

# ─── Secrets (TEST ONLY — jamais en prod) ────────────────────────────────────
export JWT_SECRET="${JWT_SECRET:-test-central-jwt-secret-must-be-at-least-32-bytes-long-xyz}"
# Clé partagée central ↔ stores pour authentifier les push entrants
export KIDZPOS_SYNC_INBOUND_API_KEY="${KIDZPOS_SYNC_INBOUND_API_KEY:-test-sync-key-shared-123456789}"

# ─── Identité et profil ──────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=central
export SERVER_PORT=8080
export KIDZPOS_NODE_ID=central-test
# IMPORTANT : vide → NodeContext.isStoreScoped()=false → agrège tous les stores
export KIDZPOS_NODE_STORE_ID=""

# ─── Database ────────────────────────────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_central}"
export DB_USER="${DB_USER:-kidzpos}"
export DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ─── Debug endpoints activés (kidzpos.debug.enabled) ─────────────────────────
export KIDZPOS_DEBUG_ENABLED=true

# ─── V21-fix : cookie name namespacé pour cohabitation multi-serveur ─────────
# Sans ça, central et store posent le même "kidzpos_rt" sur le même hostname →
# s'écrasent mutuellement → déconnexion en croix au reload.
export KIDZPOS_AUTH_COOKIE_NAME=kidzpos_rt_central

# ─── Cookie cross-origin (test multi-port localhost) ─────────────────────────
# Frontend :3000 → backend :8080 = cross-origin. Avec SameSite=Lax (default),
# le cookie httpOnly refresh n'est PAS envoyé sur les fetch XHR cross-origin →
# /api/auth/refresh 401 → kick logout au F5. SameSite=None+Secure=true sont
# obligatoires pour cross-origin ; Chrome accepte Secure=true sur localhost HTTP.
export KIDZPOS_AUTH_COOKIE_SAME_SITE=None
export KIDZPOS_AUTH_COOKIE_SECURE=true

# ─── Inbox processor sur le central : matérialise les events reçus via push ─
# Le SyncController fait un dual-write (operation_log + sync_inbox) sur les push
# entrants. L'inbox processor consomme sync_inbox et invoque les handlers pour
# créer Sale/StockMovement dans les tables métier du central → "identique"
# entre central.sales et la somme des <store>.sales après sync.
export KIDZPOS_SYNC_INBOX_ENABLED=true
export KIDZPOS_SYNC_INBOX_INTERVAL_MS=5000     # plus rapide qu'en prod (60s) pour test
export KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS=2000

# ─── CORS — auto-détection IP LAN pour test multi-device ─────────────────────
# Respect d'un override externe via CORS_ALLOWED_ORIGINS si déjà setté.
LAN_IP_AUTO=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
[ -z "$LAN_IP_AUTO" ] && LAN_IP_AUTO=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$LAN_IP_AUTO" ] && LAN_IP_AUTO="127.0.0.1"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3000,http://localhost:4001,http://localhost:5173,http://localhost:8080,http://localhost:8081,http://${LAN_IP_AUTO}:3000,http://${LAN_IP_AUTO}:4001,http://${LAN_IP_AUTO}:8080,http://${LAN_IP_AUTO}:8081}"

# ─── Mots de passe initiaux (1er boot uniquement) ────────────────────────────
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"
export KIDZPOS_SARAH_PASSWORD="${KIDZPOS_SARAH_PASSWORD:-sarah123}"
export KIDZPOS_KARIM_PASSWORD="${KIDZPOS_KARIM_PASSWORD:-karim123}"

FRONTEND_PORT=3000
DIST="$ROOT/dist-central"

echo "════════════════════════════════════════════════════════════"
echo "  Starting CENTRAL"
echo "    profile     : central"
echo "    port        : $SERVER_PORT   (API)"
echo "    frontend    : $FRONTEND_PORT  (UI, pré-câblé → :$SERVER_PORT)"
echo "    storeId     : <empty>  (aggregator)"
echo "    database    : $DB_NAME"
echo "    debug API   : http://localhost:$SERVER_PORT/api/debug/status"
echo "    log file    : $LOG_DIR/central.log"
echo "════════════════════════════════════════════════════════════"

java -jar "$JAR" > "$LOG_DIR/central.log" 2>&1 &
BACK_PID=$!
echo "$BACK_PID" > "$LOG_DIR/central.pid"
echo "  Backend PID  : $BACK_PID"

# ─── Frontend (port 3000) — préconfiguré pour API :8080 ──────────────────────
if [ -f "$DIST/index.html" ]; then
    (cd "$ROOT" && npx --yes serve -s dist-central -l "tcp://0.0.0.0:$FRONTEND_PORT") \
        > "$LOG_DIR/central-frontend.log" 2>&1 &
    FRONT_PID=$!
    echo "$FRONT_PID" > "$LOG_DIR/central-frontend.pid"
    echo "  Frontend PID : $FRONT_PID"
    echo ""
    echo "→ UI navigateur  : http://localhost:$FRONTEND_PORT"
else
    echo ""
    echo "⚠ $DIST/ absent — frontend NON démarré."
    echo "  Build d'abord : ./ops/test-env/build-frontends.sh central"
fi

echo "→ Suivre logs    : tail -f $LOG_DIR/central.log $LOG_DIR/central-frontend.log"
echo "→ Stopper        : $ROOT/ops/test-env/stop-all.sh"
