# Runbook — Sync lag critique (> 30 min)

**Alertes associées** :
- `SyncLagCritical` (Prometheus) — `kidzpos_sync_lag_seconds > 1800` pendant 5 min.
- `SyncLagHigh` (warning) — `> 300s`, traiter selon le même playbook avec une urgence moindre.

**Sévérité** : P0 (critique, risque de perte de données).

**Impact business** : la DB locale du store accumule des ventes/mouvements que
le central ne voit pas. Si le store crashe (HDD, panne courant prolongée), ces
ventes sont définitivement perdues — le central est la seule sauvegarde
hors-site fonctionnelle dans l'architecture actuelle.

---

## 1. Diagnostic en 4 commandes

```bash
# A. Combien d'events sont en retard ? (côté store)
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT COUNT(*), MIN(created_at) AS oldest, MAX(attempts) AS max_retries
   FROM operation_log WHERE synced = false;"

# B. Le central répond-il depuis le store ?
curl -sf -o /dev/null -w "central /api/sync/push status: %{http_code} (%{time_total}s)\n" \
  -X POST -H "X-Sync-Api-Key: $KIDZPOS_SYNC_API_KEY" -H "X-Sync-Store-Id: $STORE_ID" \
  -H "Content-Type: application/json" -d '{"nodeId":"healthcheck","operations":[]}' \
  "${KIDZPOS_SYNC_CENTRAL_URL}/api/sync/push"

# C. Logs SyncPushService récents
grep -E "\[sync-push\]" /chemin/.../logs/backend.log | tail -30

# D. Côté central : healthcheck général
./ops/scripts/healthcheck.sh    # exécuté sur le central
```

---

## 2. Cas A — Central injoignable (réseau/DNS/firewall)

### Symptômes
- Curl en B retourne `couldn't resolve` ou timeout.
- Logs store : `[sync-push] central unreachable: connect timeout` répétés.

### Recovery
```bash
# 1. Vérifier la résolution DNS du central
dig +short "${KIDZPOS_SYNC_CENTRAL_URL#*//}"
ping -c 3 "${KIDZPOS_SYNC_CENTRAL_URL#*//}"

# 2. Vérifier que rien ne bloque (firewall, VPN tombé)
nc -zv "${KIDZPOS_SYNC_CENTRAL_URL#*//}" 443 || nc -zv ... 8080

# 3. Si réseau revient : le scheduler reprend automatiquement (tick 30s).
#    Surveiller le lag retomber via Grafana → Business Sync → "Sync lag".
```

### Bypass d'urgence
Si le réseau ne sera pas rétabli dans l'heure ET que le risque de perte est
inacceptable : copier l'archive courante via canal hors-bande (clé USB,
backup pg_dump + transfert physique au central). Voir `restore.md §5`.

---

## 3. Cas B — Central répond 401/403 (clé API invalide)

### Symptômes
- Curl en B retourne `401` ou `403`.
- Logs store : `[sync-push] central rejected: 401 invalid api key`.

### Causes
- Clé `KIDZPOS_SYNC_API_KEY` revoquée côté central (table `sync_api_keys`,
  colonne `revoked_at`).
- Mauvais `X-Sync-Store-Id` (clé est associée à un autre store).
- Migration V21 incomplète : central en per-store, store envoie legacy.

### Recovery
```bash
# 1. Vérifier l'état de la clé côté central
PGPASSWORD=$DB_PASSWORD psql -h $CENTRAL_DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT store_id, last_used_at, revoked_at FROM sync_api_keys ORDER BY created_at DESC;"

# 2. Si revoquée par erreur : émettre une nouvelle clé pour le store
#    via le script de provisioning (cf ops/scripts/provision_store.sh).

# 3. Mettre à jour le secret côté store et redémarrer
echo "KIDZPOS_SYNC_API_KEY=<nouvelle_clé>" >> /etc/kidzpos/.env
echo "KIDZPOS_SYNC_STORE_ID=$STORE_ID" >> /etc/kidzpos/.env
./start-server.sh restart
```

---

## 4. Cas C — Central répond 5xx (central malade)

### Symptômes
- Curl en B retourne `502/503/504`.
- Multiple stores ont `SyncLagCritical` simultanément → central commun.

### Recovery
Suivre `incident_backend_down.md` **côté central**. Les stores retentent
automatiquement avec backoff exponentiel (cf `kidzpos.sync.push.retry.*`) —
aucune action côté store nécessaire tant que le central est sain.

---

## 5. Cas D — Items FROZEN (jamais retentés)

Quand `KIDZPOS_SYNC_RETRY_MAX_ATTEMPTS > 0` et qu'un item a dépassé ce seuil,
il reste en DB avec `synced=false` mais n'est plus retenté. C'est rare (config
non-default).

### Détection
```bash
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT id, type, attempts, last_error, created_at
   FROM operation_log WHERE synced=false AND attempts >= 5
   ORDER BY created_at LIMIT 20;"
```

### Recovery manuel
```sql
-- Reset les compteurs pour relancer le retry
UPDATE operation_log SET attempts = 0, next_attempt_at = NOW(), last_error = NULL
WHERE synced = false AND attempts >= 5;
```
Le prochain tick (30s) tentera de pousser ces items.

Si l'erreur stockée dans `last_error` indique un payload corrompu :
extraire l'item, corriger manuellement le JSON, ou supprimer après
documentation (`SELECT … INTO log/file ; DELETE FROM operation_log WHERE id=…`).

---

## 6. Communication

Pendant l'incident :
1. Les ventes continuent — la DB locale est prioritaire.
2. Prévenir le manager : "Risque de perte limité si la machine reste OK ; on
   recommande de ne PAS redémarrer la machine du store tant que la sync n'est
   pas rétablie."
3. Documenter l'heure de début pour calcul du fenêtre at-risk.

Après résolution :
1. Lag retombe progressivement (1 tick = 1 batch, max ~200 ops, à 30s d'intervalle).
2. Vérifier `SELECT COUNT(*) FROM operation_log WHERE synced=false` → 0.
3. Comparer côté central : `SELECT COUNT(*) FROM sales WHERE store_id='X' AND date > '<incident_start>'`
   vs côté store, doit matcher.

---

## 7. Post-mortem (obligatoire si lag > 2h)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-sync-lag-critical.md` :
- Timeline (alerte warning → critical → résolution)
- Cause racine
- Nombre d'ops à risque pendant la fenêtre (max lag × throughput moyen)
- Actions correctives :
  - Backup off-site quotidien suffisant ?
  - Healthcheck du central plus agressif ?
  - Réduire `KIDZPOS_SYNC_INTERVAL_MS` ?
