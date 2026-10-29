#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# issue-store-key.sh — Provisionne une clé d'API de sync pour un store.
#
# La clé est générée par le central (cryptographiquement sûre), retournée UNE
# SEULE FOIS en clair (le central n'en stocke qu'un hash). À reporter dans le
# .env du store : KIDZPOS_SYNC_API_KEY=<clé reçue>.
#
# Usage :
#   ./ops/deploy/issue-store-key.sh https://central.tondomaine.com <admin-email> <password> <store-id> [label]
#
# Exemple :
#   ./ops/deploy/issue-store-key.sh https://central.tondomaine.com \
#       admin@kidzpos.com 'motdepasseADMIN' s1 'Magasin Antananarivo'
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "usage: $0 <central-url> <admin-email> <admin-password> <store-id> [label]" >&2; exit 1
fi
CENTRAL="$1"; EMAIL="$2"; PW="$3"; STORE="$4"; LABEL="${5:-store-$STORE}"

command -v jq >/dev/null 2>&1 || { echo "❌ jq requis (apt install jq)" >&2; exit 1; }

echo "▶ Login en tant que $EMAIL sur $CENTRAL"
TOKEN=$(curl -sf -X POST "$CENTRAL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" | jq -r '.token // .accessToken')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "❌ login échoué"; exit 1; }

echo "▶ Génération de la clé pour storeId=$STORE label='$LABEL'"
RESP=$(curl -sf -X POST "$CENTRAL/api/sync/keys" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"storeId\":\"$STORE\",\"label\":\"$LABEL\"}")

KEY=$(echo "$RESP" | jq -r '.plaintext // .key // .secret')
KID=$(echo "$RESP" | jq -r '.id')
[ -n "$KEY" ] && [ "$KEY" != "null" ] || { echo "❌ pas de clé dans la réponse : $RESP"; exit 1; }

cat <<EOF

✅ Clé générée — À CONSERVER MAINTENANT (le central n'en stockera que le hash).

  storeId : $STORE
  label   : $LABEL
  keyId   : $KID
  KEY     : $KEY

À reporter dans le .env du store :
  KIDZPOS_SYNC_API_KEY=$KEY
  KIDZPOS_NODE_STORE_ID=$STORE
  KIDZPOS_SYNC_CENTRAL_URL=$CENTRAL

EOF
