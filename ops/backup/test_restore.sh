#!/usr/bin/env bash
# ops/backup/test_restore.sh — Vérifie qu'un dump est restaurable.
#
# Sans test régulier, "on a un backup" ≠ "on peut restaurer". Ce script tourne
# hebdomadairement en cron :
#   1. Prend le dernier dump local (kidzpos-*.dump le plus récent).
#   2. Restore dans une DB temporaire (Docker postgres jetable).
#   3. Compare row counts entre prod et restore (tables critiques).
#   4. Exit 0 si OK, 1 si écart > 5% ou échec restore.
#
# Cron hebdo (dimanche 03h, après le backup quotidien) :
#   0 3 * * 0 /chemin/.../ops/backup/test_restore.sh \
#             >> /var/log/kidzpos-restore-test.log 2>&1
#
# Pré-requis : docker accessible à l'utilisateur, postgresql-client (pg_restore).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
PROD_HOST="${PG_HOST:-${DB_HOST:-localhost}}"
PROD_PORT="${PG_PORT:-${DB_PORT:-5432}}"
PROD_DB="${PG_DB:-${DB_NAME:-kidzpos}}"
PROD_USER="${PG_USER:-${DB_USER:-kidzpos}}"
PROD_PASSWORD="${PG_PASSWORD:-${DB_PASSWORD:-kidzpos}}"

# Container Postgres jetable
TEST_CONTAINER="kidzpos-restore-test-$$"
TEST_PORT="${RESTORE_TEST_PORT:-55432}"
TEST_DB="kidzpos_restore_test"
TEST_USER="restore"
TEST_PASSWORD="restore"
PG_IMAGE="${RESTORE_TEST_PG_IMAGE:-postgres:15-alpine}"

# Tolérance d'écart row-count entre prod et restore (en %).
# 0% serait trop strict : entre le moment du dump et la comparaison, prod a
# probablement reçu de nouvelles ventes.
DRIFT_TOLERANCE_PCT="${RESTORE_DRIFT_TOLERANCE_PCT:-5}"

ts()  { date -Iseconds; }
log() { printf '%s [restore-test] %s\n' "$(ts)" "$*"; }
err() { printf '%s [restore-test] ERROR: %s\n' "$(ts)" "$*" >&2; }

cleanup() {
  if docker ps -a --format '{{.Names}}' | grep -q "^${TEST_CONTAINER}$"; then
    log "Cleanup : suppression container $TEST_CONTAINER"
    docker rm -f "$TEST_CONTAINER" >/dev/null
  fi
}
trap cleanup EXIT

# ──── Pré-flight ───────────────────────────────────────────────────────────────
command -v docker >/dev/null || { err "docker absent"; exit 1; }
command -v pg_restore >/dev/null || { err "pg_restore absent — installer postgresql-client"; exit 1; }

DUMP=$(find "$BACKUP_DIR" -maxdepth 1 -name 'kidzpos-*.dump' -type f -print0 \
  | xargs -0 ls -t 2>/dev/null | head -1 || true)
if [ -z "$DUMP" ]; then
  err "Aucun dump trouvé dans $BACKUP_DIR"
  exit 1
fi
log "Dump à tester : $DUMP ($(du -h "$DUMP" | cut -f1))"

# Vérifier checksum
SHA="${DUMP}.sha256"
if [ -f "$SHA" ]; then
  if ! (cd "$BACKUP_DIR" && sha256sum -c --quiet "$(basename "$SHA")"); then
    err "Checksum INVALIDE — dump corrompu"
    exit 1
  fi
  log "Checksum OK"
fi

# ──── Démarrage container jetable ──────────────────────────────────────────────
log "Démarrage Postgres jetable ($PG_IMAGE) sur port $TEST_PORT"
docker run -d --rm \
  --name "$TEST_CONTAINER" \
  -e POSTGRES_USER="$TEST_USER" \
  -e POSTGRES_PASSWORD="$TEST_PASSWORD" \
  -e POSTGRES_DB="$TEST_DB" \
  -p "$TEST_PORT:5432" \
  "$PG_IMAGE" >/dev/null

# Wait ready
log "Attente Postgres ready..."
for i in $(seq 1 30); do
  if docker exec "$TEST_CONTAINER" pg_isready -U "$TEST_USER" -d "$TEST_DB" >/dev/null 2>&1; then
    break
  fi
  sleep 1
  if [ "$i" = "30" ]; then
    err "Postgres jetable ne devient pas ready"
    exit 1
  fi
done

# ──── Restore ──────────────────────────────────────────────────────────────────
log "Restore en cours..."
START_T=$(date +%s)
export PGPASSWORD="$TEST_PASSWORD"
if ! pg_restore --host=localhost --port="$TEST_PORT" \
                --username="$TEST_USER" --dbname="$TEST_DB" \
                --no-owner --no-privileges \
                --exit-on-error \
                "$DUMP" 2>&1 | tail -5; then
  err "pg_restore a échoué"
  exit 1
fi
unset PGPASSWORD
DURATION=$(( $(date +%s) - START_T ))
log "Restore OK en ${DURATION}s"

# ──── Validation : row counts vs prod ──────────────────────────────────────────
# Tables critiques à comparer. Ajouter ici si on en ajoute de nouvelles.
TABLES=("sales" "sale_items" "products" "stock_movements" "customers" "users" "stores")

# Lecture prod
log "Lecture row counts prod..."
declare -A PROD_COUNTS
export PGPASSWORD="$PROD_PASSWORD"
for t in "${TABLES[@]}"; do
  PROD_COUNTS[$t]=$(psql -h "$PROD_HOST" -p "$PROD_PORT" -U "$PROD_USER" -d "$PROD_DB" \
                    -tAc "SELECT COUNT(*) FROM $t" 2>/dev/null || echo "0")
done
unset PGPASSWORD

# Lecture restore
log "Lecture row counts restore..."
declare -A RESTORE_COUNTS
export PGPASSWORD="$TEST_PASSWORD"
for t in "${TABLES[@]}"; do
  RESTORE_COUNTS[$t]=$(psql -h localhost -p "$TEST_PORT" -U "$TEST_USER" -d "$TEST_DB" \
                       -tAc "SELECT COUNT(*) FROM $t" 2>/dev/null || echo "0")
done
unset PGPASSWORD

# Comparaison
FAIL=0
printf '\n%-20s %15s %15s %10s\n' "Table" "Prod" "Restore" "Drift"
printf '%-20s %15s %15s %10s\n' "─────" "────" "───────" "─────"
for t in "${TABLES[@]}"; do
  p=${PROD_COUNTS[$t]}
  r=${RESTORE_COUNTS[$t]}
  if [ "$p" = "0" ] && [ "$r" = "0" ]; then
    printf '%-20s %15s %15s %10s\n' "$t" "$p" "$r" "OK (vide)"
    continue
  fi
  if [ "$p" = "0" ]; then
    printf '%-20s %15s %15s %10s\n' "$t" "$p" "$r" "?"
    continue
  fi
  # Le restore doit être <= prod (prod a évolué depuis le dump).
  # On accepte un écart jusqu'à DRIFT_TOLERANCE_PCT.
  drift=$(awk -v p="$p" -v r="$r" 'BEGIN{printf "%.1f", ((p - r) / p) * 100}')
  if awk -v d="$drift" -v t="$DRIFT_TOLERANCE_PCT" 'BEGIN{exit !(d > t)}'; then
    printf '%-20s %15s %15s %9s%% ⚠\n' "$t" "$p" "$r" "$drift"
    FAIL=$((FAIL + 1))
  else
    printf '%-20s %15s %15s %9s%%\n' "$t" "$p" "$r" "$drift"
  fi
done
echo

# ──── Métriques Prometheus (textfile collector) ────────────────────────────────
TEXTFILE_DIR="${TEXTFILE_COLLECTOR_DIR:-/var/lib/prometheus/textfile}"
if [ -d "$TEXTFILE_DIR" ] && [ -w "$TEXTFILE_DIR" ]; then
  cat > "$TEXTFILE_DIR/kidzpos_backup_restore_test.prom.$$" <<EOF
# HELP kidzpos_backup_restore_test_last_success_timestamp_seconds Last successful restore test
# TYPE kidzpos_backup_restore_test_last_success_timestamp_seconds gauge
kidzpos_backup_restore_test_last_success_timestamp_seconds $([ "$FAIL" -eq 0 ] && date +%s || echo 0)
# HELP kidzpos_backup_restore_test_drift_tables Number of tables with drift > tolerance
# TYPE kidzpos_backup_restore_test_drift_tables gauge
kidzpos_backup_restore_test_drift_tables $FAIL
# HELP kidzpos_backup_restore_test_duration_seconds Restore duration
# TYPE kidzpos_backup_restore_test_duration_seconds gauge
kidzpos_backup_restore_test_duration_seconds $DURATION
EOF
  mv "$TEXTFILE_DIR/kidzpos_backup_restore_test.prom.$$" "$TEXTFILE_DIR/kidzpos_backup_restore_test.prom"
fi

if [ "$FAIL" -gt 0 ]; then
  err "$FAIL table(s) avec drift > ${DRIFT_TOLERANCE_PCT}% → backup DOUTEUX"
  exit 1
fi

log "Test restore OK : ${#TABLES[@]} tables vérifiées, drift < ${DRIFT_TOLERANCE_PCT}%"
exit 0
