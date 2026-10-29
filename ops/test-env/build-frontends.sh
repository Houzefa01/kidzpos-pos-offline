#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build-frontends.sh — Build dist-central/ + dist-store/ pour le test multi-serveur.
#
# Chaque build embarque son URL API via VITE_API_URL :
#   dist-central/   → http://localhost:8080
#   dist-store/     → http://localhost:8081
#
# Le dist/ original (utilisé par start-server.sh mono-serveur) n'est PAS touché.
#
# Usage :
#   ./ops/test-env/build-frontends.sh          # rebuild les deux
#   ./ops/test-env/build-frontends.sh central  # rebuild uniquement central
#   ./ops/test-env/build-frontends.sh store    # rebuild uniquement store
# ─────────────────────────────────────────────────────────────────────────────
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# npm install si node_modules absent
if [ ! -d node_modules ]; then
    echo "→ npm ci (1ère fois) …"
    npm ci --no-audit --no-fund
fi

build_central() {
    echo "════════════════════════════════════════════════════════════"
    echo "  Building CENTRAL frontend (→ API :8080)"
    echo "════════════════════════════════════════════════════════════"
    rm -rf dist
    VITE_API_URL=http://localhost:8080 npm run build
    rm -rf dist-central
    mv dist dist-central
    echo "✓ dist-central/ prêt"
}

build_store() {
    echo "════════════════════════════════════════════════════════════"
    echo "  Building STORE frontend (→ API :8081)"
    echo "════════════════════════════════════════════════════════════"
    rm -rf dist
    VITE_API_URL=http://localhost:8081 npm run build
    rm -rf dist-store
    mv dist dist-store
    echo "✓ dist-store/ prêt"
}

case "${1:-all}" in
    central) build_central ;;
    store)   build_store ;;
    all|"")  build_central; build_store ;;
    *)
        echo "Usage: $0 [central|store|all]"
        exit 1
        ;;
esac

echo ""
echo "→ Lancer :"
echo "    ./ops/test-env/start-central.sh   # backend :8080 + frontend :3000"
echo "    ./ops/test-env/start-store.sh     # backend :8081 + frontend :3001"
