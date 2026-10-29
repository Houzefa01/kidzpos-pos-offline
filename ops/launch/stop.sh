#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stop.sh — Arrête central + store (backends + frontends) lancés par ops/launch.
# ─────────────────────────────────────────────────────────────────────────────
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="$ROOT/logs"

for name in central central-frontend central-caddy store-s1 store-s1-frontend store-s1-caddy; do
  pidfile="$LOG_DIR/$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null && echo "  ✓ $name (PID $pid) arrêté"
    fi
    rm -f "$pidfile"
  fi
done

# Filet de sécurité : tuer d'éventuels résidus (JAR backend + serveur statique).
pkill -f "kidzpos-backend-.*\.jar" 2>/dev/null && echo "  ✓ backend(s) résiduel(s) tué(s)" || true
pkill -f "ops/launch/serve-static.mjs" 2>/dev/null && echo "  ✓ frontend(s) statique(s) tué(s)" || true
pkill -f "caddy run --config .*ops/launch/Caddyfile" 2>/dev/null && echo "  ✓ caddy résiduel(s) tué(s)" || true

echo "✓ Arrêt terminé."
