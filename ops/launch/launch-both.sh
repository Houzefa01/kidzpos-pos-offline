#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-both.sh — Démarre le CENTRAL puis le STORE s1 (hors-ligne).
#
#   Central → API :8080 · UI :3000
#   Store   → API :8081 · UI :4001  (push/pull vers le central)
#
# On lance le central d'abord pour que le premier push/pull du store aboutisse
# tout de suite (sinon il retenterait en backoff — sans casse).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

"$HERE/launch-central.sh"
echo ""
"$HERE/launch-store.sh"
echo ""
echo "✓ Central + Store lancés (hors-ligne)."
echo "    Central UI → http://localhost:${CENTRAL_FRONTEND_PORT:-3000}"
echo "    Store   UI → http://localhost:${STORE_FRONTEND_PORT:-4001}"
echo "    Stop       → $HERE/stop.sh"
