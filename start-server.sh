#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# start-server.sh — KidzPOS serveur LAN mono-poste, multi-caisses.
#
# Démarre :
#   • backend Spring Boot     :8080 (toutes interfaces)
#   • frontend statique (SPA) :3000 (toutes interfaces)
#
# Usage :
#   ./start-server.sh           # start (default)
#   ./start-server.sh stop      # stop both
#   ./start-server.sh status    # check both
#   ./start-server.sh rebuild   # force rebuild backend JAR + frontend dist
#
# Configuration :
#   • cp .env.example .env  → adapter les variables
#   • Sinon : valeurs par défaut sécurisées pour démo / dev LAN
# ─────────────────────────────────────────────────────────────────

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# ──── Couleurs ────
C_GREEN='\033[0;32m'; C_YEL='\033[0;33m'; C_RED='\033[0;31m'
C_CYAN='\033[0;36m'; C_BOLD='\033[1m'; C_RST='\033[0m'
say()  { printf "${C_CYAN}→${C_RST} %s\n" "$*"; }
ok()   { printf "${C_GREEN}✅${C_RST} %s\n" "$*"; }
warn() { printf "${C_YEL}⚠${C_RST}  %s\n" "$*"; }
err()  { printf "${C_RED}❌${C_RST} %s\n" "$*" >&2; }

# ──── Charger .env si présent ────
if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
fi

# ──── Détection IP LAN ────
detect_ip() {
  if command -v ip >/dev/null 2>&1; then
    ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1
  elif command -v hostname >/dev/null 2>&1; then
    hostname -I 2>/dev/null | awk '{print $1}'
  elif command -v ifconfig >/dev/null 2>&1; then
    ifconfig 2>/dev/null | grep -E "inet (192|10|172)\." | awk '{print $2}' | head -1
  fi
}
LAN_IP="${LAN_IP:-$(detect_ip)}"
[ -z "$LAN_IP" ] && LAN_IP="127.0.0.1"

# ──── Variables par défaut (overridable via .env) ────
# DB PostgreSQL (DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD) :
#   valeurs par défaut côté backend (cf application.yml). À override via .env en prod.
export SERVER_PORT="${SERVER_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"

# Mots de passe initiaux (utilisés uniquement si la DB est vide au 1er démarrage)
export KIDZPOS_ADMIN_PASSWORD="${KIDZPOS_ADMIN_PASSWORD:-admin123}"
export KIDZPOS_SARAH_PASSWORD="${KIDZPOS_SARAH_PASSWORD:-sarah123}"
export KIDZPOS_KARIM_PASSWORD="${KIDZPOS_KARIM_PASSWORD:-karim123}"

# CORS : autoriser le frontend statique sur :${FRONTEND_PORT} + plages LAN privées
build_cors() {
  local p="$FRONTEND_PORT"
  local origins="http://${LAN_IP}:${p},http://localhost:${p},http://localhost:5173,http://localhost:4173"
  for net in 192.168 10 172.16 172.17 172.18 172.19 172.20 172.21 172.22 172.23 172.24 172.25 172.26 172.27 172.28 172.29 172.30 172.31; do
    origins="${origins},http://${net}.*.*:${p},http://${net}.*.*:5173"
  done
  printf '%s' "$origins"
}
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-$(build_cors)}"

# ──── PID files ────
BACK_PID_FILE="$LOG_DIR/backend.pid"
FRONT_PID_FILE="$LOG_DIR/frontend.pid"

# ──── Sub-commands ────
case "${1:-start}" in
  stop)
    say "Arrêt KidzPOS"
    for f in "$BACK_PID_FILE" "$FRONT_PID_FILE"; do
      if [ -f "$f" ]; then
        pid=$(cat "$f"); kill "$pid" 2>/dev/null && ok "PID $pid arrêté"; rm -f "$f"
      fi
    done
    pkill -f "kidzpos-backend.*\.jar" 2>/dev/null || true
    pkill -f "node.*serve.*dist" 2>/dev/null || true
    exit 0
    ;;
  status)
    if curl -s -f -m 1 "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
      ok "backend  UP  → http://${LAN_IP}:${SERVER_PORT}"
    else
      err "backend  DOWN"
    fi
    if curl -s -f -m 1 "http://localhost:${FRONTEND_PORT}/" >/dev/null 2>&1; then
      ok "frontend UP  → http://${LAN_IP}:${FRONTEND_PORT}"
    else
      err "frontend DOWN"
    fi
    exit 0
    ;;
  rebuild)
    say "Force rebuild"
    rm -rf "$ROOT/backend/target" "$ROOT/dist"
    set -- start
    ;;
  start) ;;
  -h|--help|help)
    sed -n '2,17p' "$0" | sed 's/^# \?//'
    exit 0
    ;;
  *)
    err "Commande inconnue : $1 (utiliser : start | stop | status | rebuild)"
    exit 1
    ;;
esac

# ──── Vérifier dépendances ────
need() {
  command -v "$1" >/dev/null 2>&1 || { err "'$1' manquant ($2)"; exit 1; }
}
need java  "Java 17+ requis : sudo apt install openjdk-17-jre"
need mvn   "Maven requis : sudo apt install maven"
need npm   "Node/npm requis : voir https://nodejs.org"
need curl  "curl requis : sudo apt install curl"

# ──── JWT secret : générer si absent + avertir (au démarrage seulement) ────
if [ -z "${JWT_SECRET:-}" ]; then
  warn "JWT_SECRET non défini — génération aléatoire (les tokens seront invalidés au prochain restart)"
  if command -v openssl >/dev/null 2>&1; then
    JWT_SECRET=$(openssl rand -base64 48)
  else
    JWT_SECRET=$(head -c 64 /dev/urandom | base64 | tr -d '\n')
  fi
  export JWT_SECRET
fi

# ──── 1. Build backend JAR si absent ────
JAR=$(ls -t "$ROOT"/backend/target/kidzpos-backend-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
  say "Build backend (peut prendre 1-2 min la 1ère fois)…"
  ( cd "$ROOT/backend" && mvn -q -DskipTests package )
  JAR=$(ls -t "$ROOT"/backend/target/kidzpos-backend-*.jar | head -1)
  ok "JAR : $JAR"
fi

# ──── 2. npm install si node_modules absent ────
if [ ! -d "$ROOT/node_modules" ] || [ ! -f "$ROOT/node_modules/.package-lock.json" ]; then
  say "npm install (peut prendre 1-2 min la 1ère fois)…"
  ( cd "$ROOT" && npm ci --no-audit --no-fund )
fi

# ──── 3. Build frontend dist/ si absent ────
if [ ! -f "$ROOT/dist/index.html" ]; then
  say "Build frontend…"
  ( cd "$ROOT" && npm run build )
fi

# ──── 4. Vérifier ports libres ────
if curl -s -m 1 "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
  err "Port ${SERVER_PORT} déjà occupé. Lancez './start-server.sh stop' d'abord."
  exit 1
fi
if curl -s -m 1 "http://localhost:${FRONTEND_PORT}/" >/dev/null 2>&1; then
  err "Port ${FRONTEND_PORT} déjà occupé."
  exit 1
fi

# ──── 5. Trap cleanup ────
cleanup() {
  echo ""
  say "Arrêt en cours…"
  [ -n "${BACK_PID:-}" ]  && kill "$BACK_PID"  2>/dev/null || true
  [ -n "${FRONT_PID:-}" ] && kill "$FRONT_PID" 2>/dev/null || true
  rm -f "$BACK_PID_FILE" "$FRONT_PID_FILE"
  wait 2>/dev/null || true
  ok "KidzPOS arrêté"
  exit 0
}
trap cleanup INT TERM

# ──── 6. Démarrer backend ────
say "Démarrage backend (PostgreSQL, port ${SERVER_PORT})…"
java -jar "$JAR" > "$LOG_DIR/backend.log" 2>&1 &
BACK_PID=$!
echo "$BACK_PID" > "$BACK_PID_FILE"

# Wait ready (max 90s — Spring Boot + Flyway peuvent prendre 20-40s au 1er boot)
WAIT=0; MAX=90
until curl -s -f -m 1 "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; do
  WAIT=$((WAIT+1))
  if [ $WAIT -gt $MAX ]; then
    err "Backend timeout après ${MAX}s — voir $LOG_DIR/backend.log"
    tail -20 "$LOG_DIR/backend.log"
    cleanup
  fi
  if ! kill -0 "$BACK_PID" 2>/dev/null; then
    err "Backend a planté — voir $LOG_DIR/backend.log"
    tail -30 "$LOG_DIR/backend.log"
    exit 1
  fi
  sleep 1
done
ok "Backend UP en ${WAIT}s"

# ──── 7. Démarrer frontend statique (SPA mode = -s) ────
say "Démarrage frontend (port ${FRONTEND_PORT})…"
( cd "$ROOT" && npx --yes serve dist -l "tcp://0.0.0.0:${FRONTEND_PORT}" -s ) \
  > "$LOG_DIR/frontend.log" 2>&1 &
FRONT_PID=$!
echo "$FRONT_PID" > "$FRONT_PID_FILE"

# Wait ready (max 30s — la 1ère invocation npx peut télécharger 'serve')
WAIT=0; MAX=30
until curl -s -f -m 1 "http://localhost:${FRONTEND_PORT}/" >/dev/null 2>&1; do
  WAIT=$((WAIT+1))
  if [ $WAIT -gt $MAX ]; then
    err "Frontend timeout après ${MAX}s — voir $LOG_DIR/frontend.log"
    tail -20 "$LOG_DIR/frontend.log"
    cleanup
  fi
  if ! kill -0 "$FRONT_PID" 2>/dev/null; then
    err "Frontend a planté — voir $LOG_DIR/frontend.log"
    tail -20 "$LOG_DIR/frontend.log"
    cleanup
  fi
  sleep 1
done
ok "Frontend UP en ${WAIT}s"

# ──── 8. Banner ────
echo ""
printf "${C_BOLD}╔══════════════════════════════════════════════════════════╗${C_RST}\n"
printf "${C_BOLD}║              KidzPOS — Serveur LAN ACTIF                 ║${C_RST}\n"
printf "${C_BOLD}╠══════════════════════════════════════════════════════════╣${C_RST}\n"
printf "║                                                          ║\n"
printf "║  ${C_CYAN}Caisses${C_RST} (ouvrir dans le navigateur de chaque poste) :   ║\n"
printf "║    → ${C_GREEN}http://%-43s${C_RST} ║\n" "${LAN_IP}:${FRONTEND_PORT}"
printf "║                                                          ║\n"
printf "║  ${C_CYAN}API${C_RST} (debug / monitoring) :                              ║\n"
printf "║    → http://%-44s ║\n" "${LAN_IP}:${SERVER_PORT}/actuator/health"
printf "║                                                          ║\n"
printf "║  ${C_CYAN}Comptes par défaut${C_RST} :                                    ║\n"
printf "║    admin@kidzpos.com / %-32s  ║\n" "${KIDZPOS_ADMIN_PASSWORD}"
printf "║    sarah@kidzpos.com / %-32s  ║\n" "${KIDZPOS_SARAH_PASSWORD}"
printf "║    karim@kidzpos.com / %-32s  ║\n" "${KIDZPOS_KARIM_PASSWORD}"
printf "║                                                          ║\n"
printf "║  ${C_CYAN}Base${C_RST}         : PostgreSQL %-30s ║\n" "(${DB_HOST:-localhost}:${DB_PORT:-5432}/${DB_NAME:-kidzpos})"
printf "║  ${C_CYAN}Logs${C_RST}         : logs/backend.log + logs/frontend.log    ║\n"
printf "║                                                          ║\n"
printf "║  ${C_YEL}Ctrl+C pour arrêter${C_RST}                                     ║\n"
printf "${C_BOLD}╚══════════════════════════════════════════════════════════╝${C_RST}\n"
echo ""

# ──── 9. Suivre les logs (tail bloque jusqu'au Ctrl+C → trap cleanup) ────
tail -F "$LOG_DIR/backend.log" "$LOG_DIR/frontend.log" 2>/dev/null

# Si tail s'arrête (rare) → cleanup quand même
cleanup
