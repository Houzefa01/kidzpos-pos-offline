# Runbook — Backend 5xx rate / high latency

**Alertes associées** :
- `HighBackend5xxRate` (warning) — taux 5xx > 5% sur 5 min.
- `HighLatencyP95` (warning) — p95 > 1.5s sur 5 min.
- `ReplayStuckInDegraded` (warning) — une caisse n'arrive pas à sortir du
  mode DEGRADED (corrélé aux deux ci-dessus).

**Sévérité** : P1 (impact UX immédiat, risque escalade vers `BackendDown`).

**Impact business** : les caisses entrent en mode dégradé (rps=2, batch=1),
le drain de l'outbox ralentit. Si soutenu, l'outbox grossit et déclenche
`OutboxSaturation`.

---

## 1. Diagnostic en 4 commandes

```bash
# A. Quel endpoint génère les 5xx ?
curl -s http://localhost:8080/actuator/prometheus | \
  grep -E 'http_server_requests_seconds_count.*status="5' | \
  sort -k2 -nr | head -10

# B. Quel endpoint est lent (p95) ?
curl -s http://localhost:8080/actuator/prometheus | \
  grep -E 'http_server_requests_seconds_bucket' | head -30

# C. Logs récents avec stack traces
grep -E "ERROR|Caused by|HTTP 5|Exception" /chemin/.../logs/backend.log | tail -50

# D. Charge système
top -bn1 | head -20
iostat -xz 5 2 | head -30
sudo -u postgres psql -d $DB_NAME -c "
  SELECT pid, query_start, state, wait_event, LEFT(query, 100)
  FROM pg_stat_activity WHERE state != 'idle' AND query_start < now() - interval '5 sec'
  ORDER BY query_start LIMIT 10;"
```

---

## 2. Cas A — Endpoint cassé (5xx 100% sur un path)

### Symptôme
Un seul endpoint à 100% de 5xx, autres à 0% — bug applicatif récent.

### Recovery
1. Identifier la version backend qui a introduit le bug : `git log --oneline -20`.
2. **Rollback** :
   ```bash
   git checkout <previous-tag>
   ./start-server.sh rebuild
   ```
3. Patcher + redéployer.

---

## 3. Cas B — Latence DB élevée

### Symptômes
- p95 > 1.5s sur multiple endpoints simultanément.
- `pg_stat_activity` montre des `wait_event` Lock/IO actifs > 5s.

### Investigation
```sql
-- Top requêtes lentes (active connections)
SELECT pid, now() - query_start AS duration, state, wait_event, LEFT(query, 200)
FROM pg_stat_activity
WHERE state != 'idle' ORDER BY query_start LIMIT 10;

-- Locks bloquants
SELECT blocked.pid AS blocked_pid, blocking.pid AS blocking_pid,
       blocked.query AS blocked_query, blocking.query AS blocking_query
FROM pg_stat_activity blocked
JOIN pg_locks bl ON bl.pid = blocked.pid AND NOT bl.granted
JOIN pg_locks bk ON bk.locktype = bl.locktype AND bk.granted
JOIN pg_stat_activity blocking ON blocking.pid = bk.pid;
```

### Recovery
- Killer les transactions bloquantes si stales :
  ```sql
  SELECT pg_terminate_backend(<pid>);
  ```
- Long terme : vacuum/analyze table, ajouter index manquant
  (`EXPLAIN ANALYZE` sur la requête lente).

---

## 4. Cas C — Saturation CPU / GC

### Symptômes
- `top` montre Java à 100% CPU pendant > 30s.
- Logs : `GC overhead limit exceeded` ou pauses GC > 1s.

### Recovery immédiate
```bash
# 1. Thread dump (n'arrête pas le process)
JPID=$(pgrep -f "kidzpos-backend.*\.jar")
jcmd "$JPID" Thread.print > /tmp/threaddump-$(date +%s).txt
jcmd "$JPID" GC.heap_info

# 2. Heap dump si OOM imminent
jcmd "$JPID" GC.heap_dump /tmp/heapdump-$(date +%s).hprof

# 3. Restart si nécessaire (vide les caches saturés)
./start-server.sh restart
```

### Long terme
- Augmenter `JAVA_OPTS="-Xmx2g -XX:+UseG1GC"` dans `start-server.sh`.
- Profiler avec `async-profiler` pour identifier la hotspot.

---

## 5. Cas D — Saturation Tomcat threads

### Symptôme
- Endpoints retournent timeout au lieu de 5xx.
- `jstack` montre la majorité des threads `http-nio-*` en `WAITING`.

### Recovery
1. Vérifier qu'un endpoint ne bloque pas indéfiniment (ex: appel HTTP externe
   sans timeout — cf `ExchangeController` qui DOIT avoir un timeout).
2. Augmenter le pool si trafic légitime :
   ```yaml
   # application.yml
   server:
     tomcat:
       threads:
         max: 400      # défaut 200
   ```

---

## 6. Cas E — `ReplayStuckInDegraded` côté caisse

Une caisse stuck en DEGRADED indique que SES requêtes vers le backend
échouent ou ralentissent — pas forcément un problème global du backend.

### Investigation
1. Identifier la caisse via `kidzpos_frontend_replay_mode_max{instance="X"}`.
2. Vérifier l'access log backend : trafic depuis cette caisse → status codes.
3. Si la caisse seule est affectée : peut-être un problème réseau local
   (Wi-Fi faible, switch saturé).

### Recovery
- Reboot routeur, vérifier câblage.
- Côté caisse : recharger l'app (F5) si stale.

---

## 7. Communication

- Si latence soutenue > 10 min : prévenir les opérateurs que les opérations
  peuvent paraître lentes.
- Pas de risque de perte de données (outbox + idempotence couvrent).

---

## 8. Post-mortem (si l'incident a déclenché `BackendDown`)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-perf-degradation.md` :
- Cause (DB, GC, Tomcat, bug code)
- Combien de temps en mode dégradé côté caisses
- Actions correctives (index ajouté, heap doublé, code optimisé)
