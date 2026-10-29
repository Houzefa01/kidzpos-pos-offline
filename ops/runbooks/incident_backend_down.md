# Runbook — Backend KidzPOS down

**Alerte associée** : `BackendDown` (Prometheus) — déclenchée après 2 min sans
scrape réussi de `/actuator/prometheus`.

**Sévérité** : P0 (critique, impact ventes immédiat).

**Impact business** : les caisses passent en mode offline (banner "Serveur
injoignable" après 3 s). Les ventes continuent côté caisse (outbox) mais ne
sont pas persistées en DB. Tant que la situation dure, les autres caisses ne
voient pas les ventes de leurs collègues.

---

## 1. Diagnostic en 3 commandes

```bash
# A. Est-ce que le process tourne ?
./start-server.sh status
ps aux | grep "kidzpos-backend.*\.jar" | grep -v grep

# B. Tail des logs récents
tail -50 /chemin/.../logs/backend.log

# C. Healthcheck complet
./ops/scripts/healthcheck.sh
```

Selon le résultat, suivre la branche correspondante ci-dessous.

---

## 2. Cas A — Process absent (crashé / pas démarré)

### Causes les plus probables
- OOMKilled (heap insuffisante)
- Exception fatale au boot (souvent migration Flyway cassée)
- `start-server.sh` jamais relancé après reboot machine

### Investigation
```bash
# Vérifier si le process a OOM
dmesg | grep -i "killed process.*java" | tail -3
sudo journalctl -u "kidzpos*" --since "1 hour ago"     # si systemd unit

# Vérifier les dernières erreurs Java
grep -E "ERROR|FATAL|Caused by:" /chemin/.../logs/backend.log | tail -20
```

### Recovery
```bash
# 1. Si OOM ou exception simple : relancer
./start-server.sh

# 2. Vérifier que ça remonte
sleep 20
./ops/scripts/healthcheck.sh

# 3. Si la 1ère relance échoue, force rebuild (sait corriger les caches Flyway)
./start-server.sh stop
./start-server.sh rebuild

# 4. Surveiller pendant 2 min
tail -f /chemin/.../logs/backend.log
```

**Si la migration Flyway est en cause** : voir `ops/backup/restore.md §4` —
NE PAS lancer plusieurs fois `start-server.sh rebuild`, cela ne corrige PAS
une migration buggée.

**Recovery time attendu** : 30 s à 2 min (cold start Spring Boot).

---

## 3. Cas B — Process présent mais 502/503/timeout

### Causes les plus probables
- Postgres injoignable → Spring n'arrive pas à acquérir une connexion HikariCP
- GC pause permanente (heap saturé)
- Threads pool Tomcat saturé (long request bloquante)
- Hibernate transaction deadlock

### Investigation
```bash
# 1. Le backend répond-il à autre chose que /actuator/health ?
curl -i --max-time 5 http://localhost:8080/api/products
# Si timeout : Tomcat saturé. Si 5xx : voir logs.

# 2. Status DB depuis le host
./ops/scripts/healthcheck.sh
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "SELECT 1;"

# 3. Threads Java
JPID=$(pgrep -f "kidzpos-backend.*\.jar")
jstack "$JPID" | grep -E "BLOCKED|deadlock" | head -20

# 4. Heap / GC
jcmd "$JPID" GC.heap_info
jcmd "$JPID" Thread.print > /tmp/threaddump.txt
```

### Recovery
- **Postgres KO** : voir `runbook` Postgres (pas inclus, traiter en priorité ;
  l'app n'a aucune chance sans DB).
- **Threads saturés** : `kill -3 $JPID` pour générer un thread dump dans les
  logs Spring (n'arrête pas le process), puis `./start-server.sh stop && ./start-server.sh`.
- **Heap saturé** : augmenter `JAVA_OPTS="-Xmx2g"` dans `start-server.sh` puis
  relancer.

**Recovery time attendu** : 1 à 5 min selon cause.

---

## 4. Cas C — Backend OK localement, scrape Prometheus KO

Différencie un vrai incident d'un problème réseau côté ops.

### Investigation
```bash
# 1. Depuis le host : /actuator/prometheus répond ?
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/prometheus

# 2. Depuis le container Prometheus : peut-il atteindre l'host ?
docker exec kidzpos-prometheus wget -q -O - http://host.docker.internal:8080/actuator/health

# 3. Status target côté Prometheus
curl -s 'http://localhost:9090/api/v1/targets' | jq '.data.activeTargets[] | {job, health, lastError}'
```

### Recovery
- Si l'host répond mais pas depuis le container : problème docker network
  ou `host.docker.internal` mal résolu. Relancer la stack ops :
  `docker compose -f ops/docker-compose.yml restart prometheus`
- Si même l'host ne répond pas : voir cas A ou B.

---

## 5. Communication

Pendant l'incident :
1. **Informer les opérateurs** : "Les ventes continuent normalement, elles
   seront enregistrées dès la reprise du serveur."
2. **Estimer le temps** : si > 15 min, prévenir le manager.
3. **Ne PAS** demander aux caisses de redémarrer ni de vider le cache — leur
   outbox locale est la seule trace des ventes en cours.

Après résolution :
1. Vérifier que les outboxes des caisses se vident : Grafana → Business Sync →
   panel "Pression outbox".
2. Comparer le nombre de ventes vs estimation business heures de l'incident.
3. Si écart suspect (perte) : `grep "<replay-paused>" /var/log/...` ou consulter
   `failedReplays` dans l'UI de chaque caisse.

---

## 6. Post-mortem (si downtime > 30 min)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-backend-down.md` :
- Timeline détaillée
- Cause racine (root cause analysis)
- Détection : combien de temps avant qu'une alerte/un opérateur signale ?
- Impact business chiffré
- Actions correctives (à ajouter au backlog avec issue tracker)

---

## 7. Recovery time attendu (résumé)

| Cas | RTO typique | RTO worst case |
|---|---|---|
| Relance simple (cas A) | 30 s | 2 min |
| Threads saturés (cas B) | 2 min | 10 min |
| Migration Flyway cassée | 5 min | 1 h (avec restore DB) |
| Disque DB perdu sans backup | ∞ | NON RÉCUPÉRABLE — voir restore.md |
