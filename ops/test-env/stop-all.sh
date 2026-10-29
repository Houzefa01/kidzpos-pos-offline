#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stop-all.sh — Arrête central + store-s1.
# Lit les PIDs depuis logs/{central,store-s1}.pid si présents,
# sinon fallback à pkill sur le JAR kidzpos-backend.
# ─────────────────────────────────────────────────────────────────────────────
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="$ROOT/logs"

stop_one() {
    local name="$1"
    local pidfile="$LOG_DIR/$name.pid"
    if [ -f "$pidfile" ]; then
        local pid
        pid=$(cat "$pidfile")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" && echo "✓ $name (PID $pid) stopped"
        else
            echo "  $name PID $pid pas vivant — clean stale pidfile"
        fi
        rm -f "$pidfile"
    else
        echo "  $name : pas de PID file ($pidfile)"
    fi
}

stop_one "central"
stop_one "central-frontend"
stop_one "store-s1"
stop_one "store-s1-frontend"

# Filet de sécurité : tuer tout JAR kidzpos-backend résiduel
if pgrep -f "kidzpos-backend.*\.jar" >/dev/null 2>&1; then
    echo "  Cleanup résiduel (backend)…"
    pkill -f "kidzpos-backend.*\.jar" 2>/dev/null || true
fi

# Filet de sécurité : tuer tout serve résiduel sur dist-central/dist-store
if pgrep -f "serve.*dist-(central|store)" >/dev/null 2>&1; then
    echo "  Cleanup résiduel (frontend)…"
    pkill -f "serve.*dist-(central|store)" 2>/dev/null || true
fi

sleep 1

echo ""
LEFTOVERS=$(pgrep -af "kidzpos-backend.*\.jar|serve.*dist-(central|store)" || true)
if [ -n "$LEFTOVERS" ]; then
    echo "⚠ Des process tournent encore :"
    echo "$LEFTOVERS"
else
    echo "✓ Aucun process kidzpos / frontend test actif"
fi
