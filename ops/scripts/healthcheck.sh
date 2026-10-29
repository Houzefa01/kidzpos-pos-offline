#!/usr/bin/env bash
# ops/scripts/healthcheck.sh — Vérification rapide état stack KidzPOS.
#
# Vérifie :
#   1. Backend Spring   http://<host>:8080/actuator/health
#   2. PostgreSQL       connexion + SELECT 1
#   3. Prometheus       http://<host>:9090/-/healthy
#   4. Grafana          http://<host>:3001/api/health
#   5. Alertmanager     http://<host>:9093/-/healthy
#
# Codes de retour :
#   0  → tous services OK
#   1  → au moins un service en mode degraded (non-critique manquant)
#   2  → backend OU Postgres KO (critique)
#
# Usage :
#   ./healthcheck.sh                        # affichage humain
#   ./healthcheck.sh --quiet                # exit code seulement
#   ./healthcheck.sh --json                 # sortie JSON pour scripts

set -uo pipefail

HOST="${HOST:-localhost}"
TIMEOUT="${TIMEOUT:-3}"

QUIET=0
JSON=0
for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    --json) JSON=1 ;;
    -h|--help)
      sed -n '2,17p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
  esac
done

# ──── Couleurs ────
if [ -t 1 ] && [ $QUIET -eq 0 ] && [ $JSON -eq 0 ]; then
  C_GREEN='\033[0;32m'; C_RED='\033[0;31m'; C_YEL='\033[0;33m'; C_RST='\033[0m'
else
  C_GREEN=; C_RED=; C_YEL=; C_RST=
fi

CRITICAL_FAILED=0
DEGRADED_FAILED=0
declare -A RESULTS

check_http() {
  local label="$1" url="$2" critical="$3"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time "$TIMEOUT" "$url" 2>/dev/null || echo "000")
  if [ "$code" = "200" ]; then
    RESULTS[$label]="OK"
    [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_GREEN}✓${C_RST} %-15s %s\n" "$label" "$url ($code)"
    return 0
  else
    RESULTS[$label]="FAIL($code)"
    if [ "$critical" = "true" ]; then
      CRITICAL_FAILED=$((CRITICAL_FAILED + 1))
      [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_RED}✗${C_RST} %-15s %s (CRITICAL — code=%s)\n" "$label" "$url" "$code"
    else
      DEGRADED_FAILED=$((DEGRADED_FAILED + 1))
      [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_YEL}⚠${C_RST} %-15s %s (degraded — code=%s)\n" "$label" "$url" "$code"
    fi
    return 1
  fi
}

check_postgres() {
  local label="postgres"
  local pg_host="${PG_HOST:-${DB_HOST:-localhost}}"
  local pg_port="${PG_PORT:-${DB_PORT:-5432}}"
  local pg_db="${PG_DB:-${DB_NAME:-kidzpos}}"
  local pg_user="${PG_USER:-${DB_USER:-kidzpos}}"
  local pg_password="${PG_PASSWORD:-${DB_PASSWORD:-kidzpos}}"

  if ! command -v psql >/dev/null; then
    RESULTS[$label]="SKIPPED (psql absent)"
    [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_YEL}⚠${C_RST} %-15s psql absent — skipping\n" "$label"
    return 0
  fi

  if PGPASSWORD="$pg_password" psql \
      -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" \
      -tAc "SELECT 1" >/dev/null 2>&1; then
    RESULTS[$label]="OK"
    [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_GREEN}✓${C_RST} %-15s %s@%s:%s/%s\n" "$label" "$pg_user" "$pg_host" "$pg_port" "$pg_db"
    return 0
  else
    RESULTS[$label]="FAIL"
    CRITICAL_FAILED=$((CRITICAL_FAILED + 1))
    [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "  ${C_RED}✗${C_RST} %-15s %s@%s:%s/%s (CRITICAL)\n" "$label" "$pg_user" "$pg_host" "$pg_port" "$pg_db"
    return 1
  fi
}

[ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && echo "🔍 Healthcheck KidzPOS — $(date -Iseconds)"

check_http "backend"      "http://${HOST}:8080/actuator/health"  "true"
check_postgres
check_http "prometheus"   "http://${HOST}:9090/-/healthy"        "false"
check_http "grafana"      "http://${HOST}:3001/api/health"       "false"
check_http "alertmanager" "http://${HOST}:9093/-/healthy"        "false"

if [ $JSON -eq 1 ]; then
  printf '{"timestamp":"%s","critical_failed":%d,"degraded_failed":%d,"results":{' \
    "$(date -Iseconds)" "$CRITICAL_FAILED" "$DEGRADED_FAILED"
  first=1
  for k in backend postgres prometheus grafana alertmanager; do
    [ $first -eq 0 ] && printf ','
    printf '"%s":"%s"' "$k" "${RESULTS[$k]:-unknown}"
    first=0
  done
  printf '}}\n'
fi

if [ $CRITICAL_FAILED -gt 0 ]; then
  [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && echo ""
  [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "${C_RED}❌ CRITICAL : %d service(s) essentiel(s) KO${C_RST}\n" "$CRITICAL_FAILED"
  exit 2
elif [ $DEGRADED_FAILED -gt 0 ]; then
  [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && echo ""
  [ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "${C_YEL}⚠  DEGRADED : %d service(s) ops manquant(s)${C_RST}\n" "$DEGRADED_FAILED"
  exit 1
fi

[ $QUIET -eq 0 ] && [ $JSON -eq 0 ] && printf "${C_GREEN}✅ Tous services OK${C_RST}\n"
exit 0
