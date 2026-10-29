# Runbook — Bus SSE bloqué

**Alerte associée** : `SseBusDown` (Prometheus) — déclenchée après 1 min de
`kidzpos_sse_bus_up == 0` (le `SseHealthIndicator` passe à 0 dès que le
dernier broadcast date de plus de 90 s).

**Alerte amont** : `SseBusBroadcastStale` (warning, > 60 s sur 2 min) — sert
de pré-alerte. Si elle apparaît seule, traiter ce runbook en mode prévention.

**Sévérité** : P1 (impact UX temps réel, pas d'impact persistance données).

**Impact business** :
- Les caisses ne reçoivent plus les events `change` du backend (vente
  collègue, mise à jour produit, ajustement stock).
- Elles continuent à fonctionner via le polling du `backendWatcher` toutes
  les 30 s → données rattrapées avec un délai max ~30 s.
- Côté front, la zombie detection SSE se déclenche après 90 s sans message
  et tente une reconnexion exponentielle. Ces tentatives échoueront tant que
  le bus est bloqué et logueront des erreurs dans la console navigateur
  (sans impact bloquant pour le caissier).

---

## 1. Diagnostic en 3 commandes

```bash
# A. État détaillé du bus SSE (authentifié — récupérer un token admin)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/health \
  | jq '.components.sseBus'
# Attendu : {"status":"UP","details":{"subscribers":N,
#   "lastBroadcastAt":"…","secondsSinceLastBroadcast":<=90, …}}
# Si secondsSinceLastBroadcast > 90 → bus bloqué.

# B. Thread "sse-heartbeat" vivant ?
JAVA_PID=$(pgrep -f "kidzpos-backend.*\.jar")
jstack $JAVA_PID | grep -A 5 "sse-heartbeat"
# Attendu : thread RUNNABLE ou TIMED_WAITING (le scheduler attend son tick).
# Anomalie : BLOCKED / WAITING sur un lock → deadlock probable.

# C. Métriques Prometheus brutes
curl -s http://localhost:8080/actuator/prometheus | grep "kidzpos_sse_"
# Vérifier kidzpos_sse_bus_up, _subscribers, _seconds_since_last_broadcast.
```

---

## 2. Cas A — Thread heartbeater absent / mort

Le thread `sse-heartbeat` n'apparaît pas dans `jstack` ou est terminé.

### Causes les plus probables
- Une `RuntimeException` non catchée dans le ticker a tué le scheduler.
  `ScheduledExecutorService` propage et **arrête** la tâche périodique sur
  exception non gérée (par design Java).
- L'instance `EventBus` n'a pas été initialisée (`@PostConstruct` jamais
  appelé → backend partiellement démarré). Vérifier les logs de boot.

### Action

```bash
# 1. Tail logs backend pour chercher l'exception qui a tué le thread
tail -200 logs/backend.log | grep -B 5 "sse-heartbeat"

# 2. Si exception trouvée → patcher EventBus.startHeartbeat pour wrapper
#    le corps du Runnable dans try/catch (cf TODO_FUTURE.md). Recompiler
#    et redéployer.

# 3. Quick fix immédiat (redémarrage) :
./start-server.sh restart
```

---

## 3. Cas B — Thread heartbeater BLOCKED sur un lock

`jstack` montre le thread bloqué sur un `synchronized` ou un wait sur lock.

### Causes les plus probables
- Deadlock entre `broadcaster` (single-thread) et un emitter qui tient un
  lock pendant une écriture lente.
- Saturation thread Tomcat → écriture SSE bloquée → backpressure remonte.

### Action

```bash
# 1. Dump thread complet
jstack $JAVA_PID > /tmp/threaddump-$(date +%s).txt
# 2. Chercher les locks tenus longuement
grep -A 20 "BLOCKED\|deadlock" /tmp/threaddump-*.txt
# 3. Si deadlock confirmé → redémarrage forcé (perte de session SSE OK,
#    les clients reconnectent automatiquement)
./start-server.sh restart
```

---

## 4. Cas C — Heartbeater vivant mais broadcast pas mis à jour

Le thread tick mais `lastBroadcastAt` n'avance pas (cas pathologique :
volatile bug, JVM compromise, horloge système figée).

### Action

```bash
# Vérifier l'horloge système
date -u
chronyc tracking 2>/dev/null || timedatectl

# Si horloge OK mais broadcast figé → redémarrage
./start-server.sh restart
```

---

## 5. Vérification post-fix

```bash
# 1. Bus doit repasser UP en < 90 s
watch -n 5 'curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/actuator/health | jq .components.sseBus.status'

# 2. Alerte Prometheus doit s'éteindre dans Alertmanager
curl -s http://localhost:9093/api/v2/alerts | jq '.[] | select(.labels.alertname=="SseBusDown")'

# 3. Côté caisses : l'icône "Online" doit redevenir verte stable
#    (plus de cycle reconnect SSE visible dans la console navigateur).
```

---

## 6. Post-mortem

Si l'incident s'est reproduit ≥ 2 fois en 7 jours :

- Renforcer `EventBus.startHeartbeat` pour wrapper le ticker dans
  try/catch (anti-thread-death) — cf [TODO_FUTURE.md](../../TODO_FUTURE.md).
- Considérer un watchdog externe : un job cron qui curl `/actuator/health`
  toutes les 30 s et redémarre si DOWN > 5 min.
- Augmenter `kidzpos.health.sse-stale-seconds` si on tolère plus de retard
  (déconseillé sans raison forte — le seuil 90 s est déjà conservateur).
