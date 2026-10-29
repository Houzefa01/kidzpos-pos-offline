#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# common.sh — Helpers partagés des scripts de lancement KidzPOS (hors-ligne).
#
# AUCUN appel réseau sortant : on n'utilise QUE le JAR déjà buildé, Postgres
# local, et un serveur statique Node natif. La disponibilité (readiness) est
# testée via /dev/tcp (bash pur) — pas de curl, pas de proxy, pas d'internet.
# ─────────────────────────────────────────────────────────────────────────────
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"
SERVE_STATIC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/serve-static.mjs"

die() { echo "❌ $*" >&2; exit 1; }

# IP LAN primaire (exclut docker0/br-/veth). Surcharge possible via $LAN_IP.
lan_ip() {
  if [ -n "${LAN_IP:-}" ]; then echo "$LAN_IP"; return; fi
  local ip
  ip=$(ip -4 -o addr show scope global 2>/dev/null \
        | awk '$2 !~ /^(docker|br-|veth)/ {print $4}' | cut -d/ -f1 | head -1)
  [ -z "$ip" ] && ip=$(hostname -I 2>/dev/null | awk '{print $1}')
  [ -z "$ip" ] && ip="127.0.0.1"
  echo "$ip"
}

require_java() { command -v java >/dev/null 2>&1 || die "java introuvable (Java 17+ requis)"; }
require_node() { command -v node >/dev/null 2>&1 || die "node introuvable (sert le frontend hors-ligne)"; }

resolve_jar() {
  JAR=$(ls -t "$ROOT"/backend/target/kidzpos-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)
  [ -n "$JAR" ] && [ -f "$JAR" ] || die "JAR backend absent → build une fois (avec internet) : (cd \"$ROOT/backend\" && mvn -DskipTests package)"
}

require_dist() {
  [ -f "$1/index.html" ] || die "frontend '$1' absent → build une fois (avec internet) : \"$ROOT/ops/test-env/build-frontends.sh\""
}

# Port déjà occupé ? (bash pur, pas de réseau externe)
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }

# Attend qu'un port LOCAL accepte les connexions (readiness), max ~120s.
wait_port() { # $1=port $2=label
  for _ in $(seq 1 60); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then exec 3>&- 3<&-; echo "  ✓ $2 prêt (port $1)"; return 0; fi
    sleep 2
  done
  echo "  ⚠ $2 toujours pas prêt après 120s — voir $LOG_DIR"; return 1
}

start_backend() { # $1=nom
  java -jar "$JAR" > "$LOG_DIR/$1.log" 2>&1 &
  echo $! > "$LOG_DIR/$1.pid"
  echo "  backend PID $(cat "$LOG_DIR/$1.pid")  → log: $LOG_DIR/$1.log"
}

start_frontend() { # $1=nom $2=dist $3=port
  node "$SERVE_STATIC" "$2" "$3" > "$LOG_DIR/$1-frontend.log" 2>&1 &
  echo $! > "$LOG_DIR/$1-frontend.pid"
  echo "  frontend PID $(cat "$LOG_DIR/$1-frontend.pid")  → http://localhost:$3"
}

# ── HTTPS / Caddy helpers ────────────────────────────────────────────────────
# En mode TLS, Caddy sert le frontend ET reverse-proxy l'API sous la même
# origine HTTPS. `tls internal` = CA locale Caddy (premier run installe la CA
# système, voir README § HTTPS). AUCUN appel internet : Caddy génère ses certs.
CADDYFILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/Caddyfile"
require_caddy() { command -v caddy >/dev/null 2>&1 || die "caddy introuvable → installer via pacman/apt-get (paquet 'caddy'), aucun download au démarrage"; }

start_caddy() { # $1=nom $2=https_port $3=backend_port $4=dist_dir
  local name="$1" https="$2" be="$3" dist="$4"
  local logf="$LOG_DIR/$name-caddy.log"
  local ip; ip="$(lan_ip)"
  # Liste d'adresses pour lesquelles `tls internal` émet un cert. localhost +
  # 127.0.0.1 + IP LAN couvre les 3 chemins d'accès (loopback local, IP LAN,
  # nom dev). Surcharge via KIDZPOS_HTTPS_ADDRS pour ajouter un FQDN.
  local addrs="${KIDZPOS_HTTPS_ADDRS:-https://localhost:$https, https://127.0.0.1:$https, https://$ip:$https}"
  KIDZPOS_HTTPS_ADDRS="$addrs" \
  KIDZPOS_BACKEND_PORT="$be" \
  KIDZPOS_DIST_DIR="$dist" \
  KIDZPOS_CADDY_LOG="$LOG_DIR/$name-caddy-access.log" \
    caddy run --config "$CADDYFILE" --adapter caddyfile > "$logf" 2>&1 &
  echo $! > "$LOG_DIR/$name-caddy.pid"
  echo "  caddy PID $(cat "$LOG_DIR/$name-caddy.pid")  → https://localhost:$https  · log: $logf"
}
