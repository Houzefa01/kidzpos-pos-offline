#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# launch-both-tls.sh — Démarre CENTRAL puis STORE s1 en HTTPS (hors-ligne).
#
#   Central → HTTPS :443  · backend loopback :8080
#   Store   → HTTPS :4443 · backend loopback :8081 · sync → http://localhost:8080
#
# Pour les clients LAN distants : installer la CA locale Caddy sur chaque
# poste (voir README § HTTPS). Sinon le navigateur affichera "non sécurisé".
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

"$HERE/launch-central-tls.sh"
echo ""
"$HERE/launch-store-tls.sh"
echo ""
echo "✓ Central + Store lancés en HTTPS (hors-ligne)."
echo "    Central UI → https://localhost:${HTTPS_PORT_CENTRAL:-443}"
echo "    Store   UI → https://localhost:${HTTPS_PORT_STORE:-4443}"
echo "    Stop       → $HERE/stop.sh"
