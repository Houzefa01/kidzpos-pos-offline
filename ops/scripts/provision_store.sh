#!/usr/bin/env bash
# ops/scripts/provision_store.sh — Provision interactif d'un nouveau magasin.
#
# Quand l'utiliser : on ajoute le store-sN à la chaîne. Ce script automatise
# les étapes décrites dans ops/runbooks/rolling_deployment.md §4.0 (setup d'un
# nouveau store) :
#   1. Émission d'une clé API per-store via le central (POST /api/sync/keys)
#   2. Génération d'un JWT secret local (32 octets random)
#   3. Création/MAJ du /etc/kidzpos/.env du store
#   4. Smoke test : healthcheck central + push de probe
#   5. (optionnel) Démarrage du backend store
#
# Usage :
#   STORE_ID=s4 STORE_NAME="Magasin Tana 2" \
#   CENTRAL_URL="https://central.kidzpos.local" \
#   CENTRAL_ADMIN_EMAIL=admin@kidzpos \
#   CENTRAL_ADMIN_PASSWORD='...' \
#   ./ops/scripts/provision_store.sh
#
# Variables (toutes via env) :
#   STORE_ID                 obligatoire, ex "s4" (sera persisté en X-Sync-Store-Id)
#   STORE_NAME               obligatoire, label humain pour la clé
#   CENTRAL_URL              obligatoire, URL du central (https://...)
#   CENTRAL_ADMIN_EMAIL      obligatoire, login admin pour /api/sync/keys
#   CENTRAL_ADMIN_PASSWORD   obligatoire (lue sur stdin si vide)
#   STORE_ENV_FILE           défaut: /etc/kidzpos/.env (à créer sur la machine store)
#   START_BACKEND            défaut: false. Si true, lance ./start-server.sh à la fin.
#   DRY_RUN                  défaut: false. Si true, n'écrit rien, juste affiche.

set -euo pipefail

# ──── Pré-flight ───────────────────────────────────────────────────────────────
STORE_ID="${STORE_ID:-}"
STORE_NAME="${STORE_NAME:-}"
CENTRAL_URL="${CENTRAL_URL:-}"
CENTRAL_ADMIN_EMAIL="${CENTRAL_ADMIN_EMAIL:-}"
STORE_ENV_FILE="${STORE_ENV_FILE:-/etc/kidzpos/.env}"
START_BACKEND="${START_BACKEND:-false}"
DRY_RUN="${DRY_RUN:-false}"

ts()  { date -Iseconds; }
log() { printf '%s [provision] %s\n' "$(ts)" "$*"; }
err() { printf '%s [provision] ERROR: %s\n' "$(ts)" "$*" >&2; }

usage() {
  grep -E '^# (Usage|Variables|  )' "$0" | sed 's/^# //'
  exit 1
}

for v in STORE_ID STORE_NAME CENTRAL_URL CENTRAL_ADMIN_EMAIL; do
  if [ -z "${!v}" ]; then
    err "Variable obligatoire manquante : $v"
    usage
  fi
done

command -v curl >/dev/null || { err "curl absent"; exit 1; }
command -v jq >/dev/null || { err "jq absent — installer jq"; exit 1; }
command -v openssl >/dev/null || { err "openssl absent"; exit 1; }

if [ -z "${CENTRAL_ADMIN_PASSWORD:-}" ]; then
  read -rsp "Password admin central ($CENTRAL_ADMIN_EMAIL) : " CENTRAL_ADMIN_PASSWORD
  echo
fi

# ──── 1. Login admin → access token ────────────────────────────────────────────
log "1/5 — Login admin sur $CENTRAL_URL"
LOGIN_RES=$(curl -sf -X POST "$CENTRAL_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e "$CENTRAL_ADMIN_EMAIL" --arg p "$CENTRAL_ADMIN_PASSWORD" \
      '{email:$e, password:$p}')" \
  || { err "Login échec — vérifier email/password + CENTRAL_URL"; exit 1; })

ACCESS_TOKEN=$(echo "$LOGIN_RES" | jq -r '.token // empty')
if [ -z "$ACCESS_TOKEN" ]; then
  err "Pas de token dans la réponse login : $LOGIN_RES"
  exit 1
fi

# ──── 2. Vérifier que le store existe (ou le créer) ────────────────────────────
log "2/5 — Vérification présence du store $STORE_ID"
STORES_RES=$(curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" "$CENTRAL_URL/api/stores")
if ! echo "$STORES_RES" | jq -e ".[] | select(.id==\"$STORE_ID\")" >/dev/null; then
  log "  → store absent, création"
  if [ "$DRY_RUN" = "true" ]; then
    log "  [DRY] POST /api/stores {id=$STORE_ID, name=$STORE_NAME}"
  else
    curl -sf -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
      -H "Content-Type: application/json" \
      -d "$(jq -n --arg id "$STORE_ID" --arg n "$STORE_NAME" \
            '{id:$id, name:$n, location:""}')" \
      "$CENTRAL_URL/api/stores" >/dev/null
    log "  store créé"
  fi
else
  log "  store déjà présent : OK"
fi

# ──── 3. Émission d'une clé API per-store ──────────────────────────────────────
log "3/5 — Émission de la clé API sync pour $STORE_ID"
if [ "$DRY_RUN" = "true" ]; then
  log "  [DRY] POST /api/sync/keys {storeId=$STORE_ID, label=$STORE_NAME provision}"
  API_KEY="<dry-run-placeholder>"
  API_KEY_ID="<dry-run-id>"
else
  KEY_RES=$(curl -sf -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg s "$STORE_ID" --arg l "$STORE_NAME - $(date +%Y%m%d)" \
          '{storeId:$s, label:$l}')" \
    "$CENTRAL_URL/api/sync/keys")
  API_KEY=$(echo "$KEY_RES" | jq -r '.plaintext // empty')
  API_KEY_ID=$(echo "$KEY_RES" | jq -r '.id // empty')
  if [ -z "$API_KEY" ]; then
    err "Pas de plaintext dans la réponse : $KEY_RES"
    exit 1
  fi
  log "  clé créée id=$API_KEY_ID (ne sera plus jamais affichée en clair)"
fi

# ──── 4. Génération JWT secret + écriture .env ────────────────────────────────
log "4/5 — Génération JWT secret + écriture $STORE_ENV_FILE"
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')

ENV_CONTENT=$(cat <<EOF
# Généré par ops/scripts/provision_store.sh le $(date -Iseconds)
# Store: $STORE_ID ($STORE_NAME)

# ──── Identité du nœud ────
KIDZPOS_NODE_ROLE=local
KIDZPOS_NODE_ID=$STORE_ID
KIDZPOS_STORE_ID=$STORE_ID

# ──── Sécurité ────
JWT_SECRET=$JWT_SECRET

# ──── Sync push (vers central) ────
KIDZPOS_SYNC_PUSH_ENABLED=true
KIDZPOS_SYNC_PULL_ENABLED=true
KIDZPOS_SYNC_INBOX_ENABLED=true
KIDZPOS_SYNC_CENTRAL_URL=$CENTRAL_URL
KIDZPOS_SYNC_API_KEY=$API_KEY

# ──── DB locale (à compléter manuellement) ────
# DB_HOST=localhost
# DB_PORT=5432
# DB_NAME=kidzpos
# DB_USER=kidzpos
# DB_PASSWORD=<set-me>

# ──── CORS (caisses sur le LAN du magasin) ────
# CORS_ALLOWED_ORIGINS=http://192.168.X.*:5173
EOF
)

if [ "$DRY_RUN" = "true" ]; then
  log "  [DRY] écrirait dans $STORE_ENV_FILE :"
  echo "$ENV_CONTENT" | sed 's/^/    /'
else
  ENV_DIR=$(dirname "$STORE_ENV_FILE")
  [ -d "$ENV_DIR" ] || sudo mkdir -p "$ENV_DIR"
  if [ -f "$STORE_ENV_FILE" ]; then
    BACKUP="${STORE_ENV_FILE}.$(date +%Y%m%d%H%M%S).bak"
    sudo cp "$STORE_ENV_FILE" "$BACKUP"
    log "  ancien .env sauvegardé en $BACKUP"
  fi
  echo "$ENV_CONTENT" | sudo tee "$STORE_ENV_FILE" >/dev/null
  sudo chmod 600 "$STORE_ENV_FILE"
  log "  écrit avec permission 0600"
fi

# ──── 5. Smoke test ───────────────────────────────────────────────────────────
log "5/5 — Smoke test : push de probe vers le central"
if [ "$DRY_RUN" = "true" ]; then
  log "  [DRY] curl probe vers $CENTRAL_URL/api/sync/push"
else
  PROBE_STATUS=$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST -H "X-Sync-Api-Key: $API_KEY" -H "X-Sync-Store-Id: $STORE_ID" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg n "$STORE_ID-probe" '{nodeId:$n, operations:[]}')" \
    "$CENTRAL_URL/api/sync/push")
  if [ "$PROBE_STATUS" != "200" ]; then
    err "Probe échec (HTTP $PROBE_STATUS) — la clé ne fonctionne pas ?"
    exit 1
  fi
  log "  probe OK (HTTP 200) — la clé est acceptée par le central"
fi

# ──── Récap final ─────────────────────────────────────────────────────────────
cat <<EOF

────────────────────────────────────────────────────────────────────────
✓ Provisioning OK pour $STORE_ID ($STORE_NAME)
────────────────────────────────────────────────────────────────────────
  Clé API id        : $API_KEY_ID  (révocable via DELETE /api/sync/keys/{id})
  Fichier .env      : $STORE_ENV_FILE
  Central URL       : $CENTRAL_URL
  Probe central     : OK

Prochaines étapes manuelles :
  1. Compléter DB_HOST/PORT/USER/PASSWORD dans $STORE_ENV_FILE
  2. Compléter CORS_ALLOWED_ORIGINS avec les IPs réelles des caisses
  3. Démarrer le backend : ./start-server.sh
  4. Vérifier : ./ops/scripts/healthcheck.sh
  5. Programmer le cron backup (pg_backup.sh + offsite_sync.sh)

EOF

if [ "$START_BACKEND" = "true" ] && [ "$DRY_RUN" != "true" ]; then
  log "Démarrage backend (START_BACKEND=true)"
  cd "$(dirname "$0")/../.." && ./start-server.sh
fi
