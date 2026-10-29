#!/usr/bin/env bash
# ops/scripts/test_alert.sh — Test du pipeline d'alerte Telegram.
#
# Usage :
#   ./test_alert.sh                          # alerte synthétique critical (recommandé)
#   ./test_alert.sh --severity warning       # alerte synthétique warning
#   ./test_alert.sh --backend-stop           # vraie alerte BackendDown (2 min attente)
#
# Pré-requis :
#   • Stack OPS up : docker compose -f ops/docker-compose.yml up -d
#   • Bot Telegram configuré + /start envoyé depuis l'app Telegram

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AM_URL="${AM_URL:-http://localhost:9093}"
SEVERITY="critical"
MODE="synthetic"

for arg in "$@"; do
  case "$arg" in
    --severity)        shift; SEVERITY="$1"; shift || true ;;
    --severity=*)      SEVERITY="${arg#*=}" ;;
    --backend-stop)    MODE="backend-stop" ;;
    -h|--help)         sed -n '2,12p' "$0" | sed 's/^# \?//'; exit 0 ;;
  esac
done

ts() { date -Iseconds; }
log() { printf '%s [test_alert] %s\n' "$(ts)" "$*"; }

# ──── Vérif pré-requis ────
log "Vérification stack OPS"
if ! curl -sf "$AM_URL/-/healthy" >/dev/null; then
  log "❌ Alertmanager injoignable : $AM_URL"
  exit 1
fi
if ! docker ps --filter "name=kidzpos-alertmanager-bot" --format '{{.Names}}' | grep -q alertmanager-bot; then
  log "⚠ alertmanager-bot pas démarré — les alertes ne seront PAS push Telegram"
  log "   (UI Alertmanager fonctionnera tout de même : $AM_URL)"
fi

# ──── Mode 1 : alerte synthétique ────
if [ "$MODE" = "synthetic" ]; then
  log "Émission alerte synthétique severity=$SEVERITY"
  curl -s -o /dev/null -w "Alertmanager: %{http_code}\n" \
    -X POST "$AM_URL/api/v2/alerts" \
    -H "Content-Type: application/json" \
    -d @- <<EOF
[{
  "labels": {
    "alertname": "TestAlertFromScript",
    "severity": "$SEVERITY",
    "service": "test",
    "instance": "host.docker.internal:8080"
  },
  "annotations": {
    "summary": "🧪 Test alerte ${SEVERITY} depuis test_alert.sh",
    "description": "Pipeline check : Prometheus n'est PAS impliqué — cette alerte est injectée directement dans Alertmanager. Si tu reçois ceci dans Telegram, la chaîne Alertmanager → alertmanager-bot → Telegram fonctionne.",
    "runbook_url": "file:///ops/runbooks/incident_backend_down.md"
  },
  "generatorURL": "http://localhost:9093/#/alerts"
}]
EOF

  sleep 3
  log "État Alertmanager après émission :"
  curl -s "$AM_URL/api/v2/alerts" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a.get('labels', {}).get('alertname') == 'TestAlertFromScript':
        print(f\"  [{a['status']['state']}] {a['labels']['alertname']} sev={a['labels']['severity']}\")"

  log "✓ Alerte injectée. Vérifier Telegram (devrait arriver en < 30s)."
  log ""
  log "Pour annuler l'alerte (la résoudre immédiatement) :"
  log "  curl -X POST $AM_URL/api/v2/alerts -H 'Content-Type: application/json' \\"
  log "    -d '[{\"labels\":{\"alertname\":\"TestAlertFromScript\",\"severity\":\"$SEVERITY\",\"service\":\"test\"},\"endsAt\":\"$(date -u -d '+1 minute' +%Y-%m-%dT%H:%M:%SZ)\"}]'"
fi

# ──── Mode 2 : vraie alerte BackendDown ────
if [ "$MODE" = "backend-stop" ]; then
  log "Stop du backend KidzPOS → BackendDown firing dans ~2 min"
  "$ROOT/start-server.sh" stop 2>&1 | tail -2

  log "Surveillance pendant 150s (attendu : firing à t≃120s, Telegram à t≃125s)"
  for i in $(seq 1 30); do
    sleep 5
    elapsed=$((i * 5))
    state=$(curl -s "$AM_URL/api/v2/alerts" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a.get('labels', {}).get('alertname') == 'BackendDown':
        print(a['status']['state']); break
" 2>/dev/null || echo "none")
    printf "  t=%3ds  BackendDown=%s\n" "$elapsed" "$state"
    [ "$state" = "active" ] && break
  done

  log "Relance backend → resolved attendu en ~5 min (resolve_timeout)"
  "$ROOT/start-server.sh" 2>&1 | tail -2 &
fi
