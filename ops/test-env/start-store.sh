#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-store.sh — Démarre un serveur STORE s1 en LOCAL pour test sync.
#
#   Profil           : local
#   Port             : 8081
#   StoreId          : s1
#   Push/Pull cible  : http://localhost:8080  (le central)
#   Database         : kidzpos_store_s1
#   Logs             : <repo>/logs/store-s1.log
#
# Cadences sync RÉDUITES (5s push / 10s pull) pour itération rapide en test.
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

# ─── Secrets (différent du central pour isoler les JWTs) ─────────────────────
export JWT_SECRET="${JWT_SECRET:-test-store-s1-jwt-secret-must-be-at-least-32-bytes-long-abc}"
# MÊME clé que central → permet d'authentifier les push entrants côté central
export KIDZPOS_SYNC_API_KEY="${KIDZPOS_SYNC_API_KEY:-test-sync-key-shared-123456789}"

# ─── Identité et profil ──────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE=local
export SERVER_PORT=8081
export KIDZPOS_NODE_ID=store-s1-test
# IMPORTANT : "s1" → NodeContext.isStoreScoped()=true → isolation magasin active
export KIDZPOS_NODE_STORE_ID=s1

# ─── Sync : pointe vers le central local ─────────────────────────────────────
export KIDZPOS_SYNC_CENTRAL_URL="${KIDZPOS_SYNC_CENTRAL_URL:-http://localhost:8080}"
export KIDZPOS_SYNC_PUSH_ENABLED=true
export KIDZPOS_SYNC_PULL_ENABLED=true
export KIDZPOS_SYNC_INBOX_ENABLED=true

# ─── Cadences accélérées pour itération test ─────────────────────────────────
# Production : push 30s, pull 60s, inbox 60s
# Test       : push  5s, pull 10s, inbox 10s
export KIDZPOS_SYNC_INTERVAL_MS=5000
export KIDZPOS_SYNC_INITIAL_DELAY_MS=2000
export KIDZPOS_SYNC_PULL_INTERVAL_MS=10000
export KIDZPOS_SYNC_PULL_INITIAL_DELAY_MS=3000
export KIDZPOS_SYNC_INBOX_INTERVAL_MS=10000
export KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS=5000

# ─── Database (séparée du central) ───────────────────────────────────────────
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-kidzpos_store_s1}"
export DB_USER="${DB_USER:-kidzpos}"
export DB_PASSWORD="${DB_PASSWORD:-kidzpos}"

# ─── Debug endpoints activés ─────────────────────────────────────────────────
export KIDZPOS_DEBUG_ENABLED=true

# ─── V21-fix : cookie name namespacé (cf start-central.sh) ───────────────────
export KIDZPOS_AUTH_COOKIE_NAME=kidzpos_rt_s1

# ─── Cookie cross-origin (test multi-port localhost) ─────────────────────────
# Frontend :4001 → backend :8081 = cross-origin. Cf justification dans
# start-central.sh — sans SameSite=None+Secure=true le refresh casse au F5.
export KIDZPOS_AUTH_COOKIE_SAME_SITE=None
export KIDZPOS_AUTH_COOKIE_SECURE=true

# ─── CORS — auto-détection IP LAN pour test multi-device ─────────────────────
# Note : port 3001 évité car souvent utilisé par Grafana — on emploie 4001 ici.
LAN_IP_AUTO=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
[ -z "$LAN_IP_AUTO" ] && LAN_IP_AUTO=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$LAN_IP_AUTO" ] && LAN_IP_AUTO="127.0.0.1"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3000,http://localhost:4001,http://localhost:5173,http://localhost:8080,http://localhost:8081,http://${LAN_IP_AUTO}:3000,http://${LAN_IP_AUTO}:4001,http://${LAN_IP_AUTO}:8080,http://${LAN_IP_AUTO}:8081}"

# ─── Mots de passe initiaux ──────────────────────────────────────────────────
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"
export KIDZPOS_SARAH_PASSWORD="${KIDZPOS_SARAH_PASSWORD:-sarah123}"
export KIDZPOS_KARIM_PASSWORD="${KIDZPOS_KARIM_PASSWORD:-karim123}"

FRONTEND_PORT=4001   # 3001 souvent occupé par Grafana → on prend 4001
DIST="$ROOT/dist-store"

echo "════════════════════════════════════════════════════════════"
echo "  Starting STORE s1"
echo "    profile     : local"
echo "    port        : $SERVER_PORT   (API)"
echo "    frontend    : $FRONTEND_PORT  (UI, pré-câblé → :$SERVER_PORT)"
echo "    storeId     : $KIDZPOS_NODE_STORE_ID"
echo "    central url : $KIDZPOS_SYNC_CENTRAL_URL"
echo "    database    : $DB_NAME"
echo "    push every  : ${KIDZPOS_SYNC_INTERVAL_MS}ms"
echo "    pull every  : ${KIDZPOS_SYNC_PULL_INTERVAL_MS}ms"
echo "    debug API   : http://localhost:$SERVER_PORT/api/debug/status"
echo "    log file    : $LOG_DIR/store-s1.log"
echo "════════════════════════════════════════════════════════════"

java -jar "$JAR" > "$LOG_DIR/store-s1.log" 2>&1 &
BACK_PID=$!
echo "$BACK_PID" > "$LOG_DIR/store-s1.pid"
echo "  Backend PID  : $BACK_PID"

# ─── Frontend (port 3001) — préconfiguré pour API :8081 ──────────────────────
if [ -f "$DIST/index.html" ]; then
    (cd "$ROOT" && npx --yes serve -s dist-store -l "tcp://0.0.0.0:$FRONTEND_PORT") \
        > "$LOG_DIR/store-s1-frontend.log" 2>&1 &
    FRONT_PID=$!
    echo "$FRONT_PID" > "$LOG_DIR/store-s1-frontend.pid"
    echo "  Frontend PID : $FRONT_PID"
    echo ""
    echo "→ UI navigateur  : http://localhost:$FRONTEND_PORT"
else
    echo ""
    echo "⚠ $DIST/ absent — frontend NON démarré."
    echo "  Build d'abord : ./ops/test-env/build-frontends.sh store"
fi

echo "→ Suivre logs    : tail -f $LOG_DIR/store-s1.log $LOG_DIR/store-s1-frontend.log"
echo "→ Stopper        : $ROOT/ops/test-env/stop-all.sh"
