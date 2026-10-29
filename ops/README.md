# OPS Stack — KidzPOS

Stack d'exploitation (monitoring + alerting + backup + runbooks) pour le POS
KidzPOS. **Tout est local, LAN-only, pas de cloud requis.**

```
ops/
├── docker-compose.yml          # Prometheus + Grafana + Alertmanager + Postgres exporter + Node exporter
├── prometheus/
│   ├── prometheus.yml          # scrape configs
│   └── alerts.yml              # règles d'alerte (4 groups, 11 alertes)
├── grafana/
│   ├── provisioning/           # datasource + dashboards auto-loaded
│   └── dashboards/             # 3 dashboards JSON (System / Business / Security)
├── alertmanager/
│   └── alertmanager.yml        # routing + receivers (webhook placeholder)
├── backup/
│   ├── pg_backup.sh            # pg_dump compressé + rotation + sha256
│   └── restore.md              # 6 procédures de restore documentées
├── runbooks/
│   ├── incident_backend_down.md
│   └── outbox_saturation.md
└── scripts/
    └── healthcheck.sh          # vérif rapide stack (exit code-friendly)
```

---

## Lancement rapide

```bash
# 1) Démarrer la stack ops
docker compose -f ops/docker-compose.yml up -d

# 2) Vérifier
./ops/scripts/healthcheck.sh
# → 5 services attendus : backend, postgres, prometheus, grafana, alertmanager

# 3) Ouvrir Grafana
xdg-open http://localhost:3001
# Login : admin / admin (à changer via GF_ADMIN_PASSWORD env)

# 4) Vérifier les dashboards
# Folder "KidzPOS" → System Health, Business Sync, Security
```

---

## Ports exposés

| Service | Port | URL |
|---|---|---|
| Backend KidzPOS (host) | 8080 | http://localhost:8080/actuator/prometheus |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3001 | http://localhost:3001 |
| Alertmanager | 9093 | http://localhost:9093 |
| Postgres exporter | 9187 | http://localhost:9187/metrics |
| Node exporter | 9100 | http://localhost:9100/metrics |

---

## Configuration runtime

Variables d'env supportées (placer dans `.env` à la racine du projet) :

```bash
# Grafana
GF_ADMIN_USER=admin
GF_ADMIN_PASSWORD=changeme

# Postgres (pour postgres-exporter ET pg_backup.sh)
PG_HOST=localhost
PG_PORT=5432
PG_DB=kidzpos
PG_USER=kidzpos
PG_PASSWORD=kidzpos

# Backup
BACKUP_DIR=/chemin/absolu/backups
BACKUP_RETENTION_DAYS=7
```

---

## Backup PostgreSQL

```bash
# Lancement manuel
./ops/backup/pg_backup.sh

# Cron quotidien 02:00 (à mettre dans crontab -e du user kidzpos)
0 2 * * * /chemin/absolu/ops/backup/pg_backup.sh >> /var/log/kidzpos-backup.log 2>&1

# Restore : voir ops/backup/restore.md
```

⚠️ **Test mensuel de restore obligatoire** (cf `restore.md §5`). Un backup non
testé n'est pas un backup.

---

## Alertes Prometheus configurées

| Alerte | Sévérité | Condition |
|---|---|---|
| `BackendDown` | critical | `up{job="kidzpos-backend"} == 0 for 2m` |
| `DBDown` | critical | `pg_up == 0 for 1m` |
| `RefreshTokenReuse` | critical | `increase(kidzpos_auth_refresh_reuse_detected_total[5m]) > 0` |
| `HighBackend5xxRate` | warning | `5xx rate > 5% for 3m` |
| `HighLatencyP95` | warning | `p95 > 1.5s for 5m` |
| `OutboxSaturation` | warning | `outbox_size > 8000 for 10m` |
| `FailedReplaysRising` | warning | `failed_replays > 0 for 5m` |
| `ReplayStuckInDegraded` | warning | `mode_max == 2 for 15m` |
| `NoReportingCashier` | warning | `reporting_cashiers == 0 for 15m` |
| `LoginRateLimited` | warning | `> 5 hits in 5m` |
| `HighRefreshFailureRate` | warning | `> 50% fail for 10m` |
| `OptimisticLockConflictSpike` | warning | `> 10 conflicts in 5m` |
| `PrometheusTargetMissing` | warning | `up == 0 for 5m` (auto-monitoring) |

---

## Tester une alerte

```bash
# 1. Forcer un BackendDown
./start-server.sh stop
# Attendre 2 min, observer dans Alertmanager :
xdg-open http://localhost:9093

# 2. Forcer un OutboxSaturation côté caisse (Console navigateur)
# Pré-remplir l'outbox sans envoyer :
const fake = Array.from({length: 8500}, (_, i) => ({
  id: `fake-${i}`, ts: Date.now(), path: '/test', method: 'POST',
  body: {}, retries: 0
}));
localStorage.setItem("kidzpos-outbox", JSON.stringify(fake));
window.dispatchEvent(new CustomEvent("outbox:change"));
# Attendre 30s pour que metricsReporter push → puis 10 min pour la durée
# de l'alerte. Observer dans Grafana → Business Sync.

# 3. Tester le webhook critique
curl -X POST http://localhost:9093/api/v2/alerts -H "Content-Type: application/json" \
  -d '[{"labels":{"alertname":"TestCritical","severity":"critical"},"annotations":{"summary":"Test"}}]'
```

---

## Canal d'alerte Telegram (OPS+1.1)

Alertmanager pousse les alertes vers le container `alertmanager-bot`
(`metalmatze/alertmanager-bot:0.4.3`) qui relaie vers Telegram. Pas de
bricolage webhook direct vers l'API Telegram (format JSON incompatible).

### Setup en 4 étapes

```bash
# 1. Créer le bot via @BotFather sur Telegram :
#    /newbot → choisir nom + username → récupérer le token (1234:ABC...)

# 2. Trouver ton chat_id :
#    Ouvrir conversation avec @userinfobot → il affiche ton ID

# 3. Mettre les valeurs dans ops/.env (cf ops/.env.example)
cp ops/.env.example ops/.env
$EDITOR ops/.env       # remplir TELEGRAM_TOKEN et TELEGRAM_ADMIN

# 4. Démarrer (ou redémarrer si déjà up)
docker compose -f ops/docker-compose.yml --env-file ops/.env up -d
```

### S'enregistrer comme destinataire

Côté Telegram, ouvrir le bot que tu viens de créer et taper :
```
/start
```

Le bot répond avec un message de bienvenue et enregistre ton chat_id en
BoltDB. À partir de là, **toutes les alertes** sont envoyées à ce chat.

Plusieurs opérateurs peuvent recevoir : chacun envoie `/start` et leur
chat_id doit être listé dans `TELEGRAM_ADMIN` (séparés par virgule).

### Commandes utiles du bot (Telegram)

```
/start    s'enregistrer comme destinataire
/stop     se désenregistrer
/status   état Alertmanager (alertes actives, silences)
/alerts   liste des alertes actives
/silences liste des silences
/chats    qui reçoit (admin only)
/help     liste complète
```

### Tester en condition réelle

```bash
# Test 1 — alerte synthétique via Alertmanager API directement
curl -X POST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d '[{
    "labels": {"alertname":"TestCritical","severity":"critical","service":"test"},
    "annotations": {"summary":"Test alerte Telegram","description":"Si tu lis ceci, le pipeline marche."}
  }]'
# → message Telegram en < 30 s

# Test 2 — alerte naturelle (BackendDown)
./start-server.sh stop
# Attendre 2 min (durée `for:` de l'alerte) → message Telegram
# Redémarrer : message "RESOLVED" doit suivre 5 min plus tard (resolve_timeout)
./start-server.sh
```

### Modifier le routage / format des messages

Le template par défaut du bot (titre + status + summary + description +
runbook_url) suffit pour 95% des cas. Pour custom :
- Monter un template Go custom via volume + `TEMPLATE_PATHS`
- Ou simplement éditer `ops/alertmanager/alertmanager.yml` pour
  reformater le payload via Alertmanager (recommandé : éviter de toucher
  le bot, garder les défauts).

Puis recharger Alertmanager sans restart :
```bash
docker kill --signal=HUP kidzpos-alertmanager
```

### Sans Telegram configuré

Si `ops/.env` ne contient pas `TELEGRAM_TOKEN` + `TELEGRAM_ADMIN`,
`alertmanager-bot` refuse de démarrer (variable non substituée → erreur
explicite au boot). Les autres services tournent normalement et les
alertes restent visibles via UI Alertmanager :9093.

---

## Diagnostics fréquents

```bash
# Pas de scrape côté Prometheus ?
docker exec kidzpos-prometheus wget -q -O - http://host.docker.internal:8080/actuator/health
# → si OK depuis le container, vérifier "Targets" dans Prometheus UI :9090/targets

# Grafana ne se charge pas / dashboards vides ?
docker logs kidzpos-grafana --tail 50
# Vérifier datasource : Grafana → Configuration → Data sources → Prometheus → "Test"

# Alertmanager ne reçoit pas ?
curl -s http://localhost:9090/api/v1/alerts | jq
docker logs kidzpos-alertmanager --tail 50
```

---

## Runbooks

| Scenario | Fichier |
|---|---|
| Backend ne répond plus | `runbooks/incident_backend_down.md` |
| Outbox saturée | `runbooks/outbox_saturation.md` |

Les autres incidents (Postgres KO, vol de cookie détecté, etc.) sont
référencés dans les `runbook_url:` des règles d'alerte. À enrichir au fil
des incidents réels via post-mortems.

---

## Ce qui N'EST PAS dans cette stack

Volontairement exclu pour préserver la simplicité (cf `design principles §9`) :

- ❌ Kubernetes (Docker compose suffit)
- ❌ Loki / ELK (logs centralisés) — ligne de code = `tail -f` sur l'host
- ❌ Tracing distribué (Jaeger) — corrélation MDC suffisante en mono-backend
- ❌ Backup off-site (S3, etc.) — à brancher au besoin via rsync cron
- ❌ HA / failover — déploiement mono-shop par design

À ajouter quand on passera multi-shops ou exposition publique.
