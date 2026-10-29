# Runbook — Sync inbox errors / quarantine spike

**Alertes associées** :
- `SyncInboxErrorRateHigh` (warning) — >20% d'erreurs sur un type d'event sur 10 min.
- `SyncInboxQuarantineSpike` (warning) — >3 quarantines en 10 min.

**Sévérité** : P1 (impact différé, mais accumule des events non appliqués).

**Impact business** : les events erronés restent en `sync_inbox` avec
`processed=false`, retentés `MAX_RETRIES=5` fois puis déplacés vers
`quarantine_events`. Le state central diverge de la réalité magasin (ex: un
checkout non répliqué → le dashboard central sous-compte les ventes).

---

## 1. Diagnostic en 4 commandes

```bash
TYPE="<type-from-alert>"   # ex: "product.updated"

# A. Combien d'events en échec actuellement
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT type, COUNT(*), MIN(created_at), MAX(retry_count)
   FROM sync_inbox WHERE processed=false AND retry_count > 0
   GROUP BY type ORDER BY COUNT(*) DESC;"

# B. Dernières erreurs pour le type en cause
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT id, retry_count, last_error, created_at
   FROM sync_inbox WHERE type='$TYPE' AND processed=false
   ORDER BY retry_count DESC, created_at DESC LIMIT 10;"

# C. Quarantine (events abandonnés)
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT id, event_type, quarantine_reason, last_error, store_id, created_at
   FROM quarantine_events WHERE event_type='$TYPE'
   ORDER BY created_at DESC LIMIT 10;"

# D. Payload représentatif (échantillon)
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT payload FROM sync_inbox WHERE type='$TYPE' AND processed=false
   ORDER BY retry_count DESC LIMIT 1;" | head -50
```

---

## 2. Cas A — Type non-whitelisté (handler manquant)

### Symptômes
- `last_error` contient "no handler for type=..." ou similaire.
- Type récent (nouvelle feature déployée sur stores mais pas sur central).

### Recovery
1. **Implémenter le handler côté central** (`@Component` implémentant `InboxHandler`).
2. Redéployer le central.
3. **Replay les events quarantinés** :
   ```bash
   for id in $(psql -tAc "SELECT id FROM quarantine_events WHERE event_type='$TYPE' AND replayed_at IS NULL"); do
     curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
       "$CENTRAL_URL/api/sync/admin/quarantine/$id/replay"
   done
   ```

### Prévention
Ajouter au pipeline CI : test qui vérifie que tous les types émis par
`OperationLogService.record()` ont un handler côté central
(grep `record\(".*"` vs `@Component class .*Handler.*type=".*"`).

---

## 3. Cas B — Payload corrompu côté émetteur (schema drift)

### Symptômes
- `last_error` mentionne `JsonProcessingException`, `null pointer`, ou
  `validation failed: field X`.
- Le store émetteur est sur une version backend différente (skew).

### Investigation
```sql
-- Quel store émet ces payloads ?
SELECT store_id, COUNT(*) FROM sync_inbox
WHERE type='<TYPE>' AND processed=false AND retry_count > 0
GROUP BY store_id ORDER BY 2 DESC;
```

### Recovery
1. **Identifier la version backend du store fautif** via son `/actuator/info`.
2. **Aligner les versions** (cf `rolling_deployment.md`).
3. **Décider** : replay (si le handler central tolère le payload V21) ou
   purge (si payload V20 invalide en V21).

### Replay sélectif
```sql
-- Reset les retry pour relancer l'apply (handler doit tolérer ou avoir été patché)
UPDATE sync_inbox SET retry_count = 0, last_attempt_at = NULL, last_error = NULL
WHERE type = '<TYPE>' AND processed = false;
```

### Purge documentée
```sql
-- ATTENTION : suppression définitive. Logger d'abord.
\copy (SELECT * FROM sync_inbox WHERE type='<TYPE>' AND retry_count >= 5)
  TO '/tmp/purge_inbox_<DATE>.csv' WITH CSV HEADER;
DELETE FROM sync_inbox WHERE type='<TYPE>' AND retry_count >= 5;
```

---

## 4. Cas C — Bug applicatif dans le handler

### Symptômes
- `last_error` = stack trace Java pointant un fichier `*Handler.java`.
- Tous les stores affectés (pas un seul).

### Recovery
1. **Patch + déploiement** du handler côté central.
2. Reset retry count pour replay (cf cas B).
3. **Ajouter un test** dans `backend/src/test/java/com/kidzpos/sync/`.

---

## 5. Cas D — Conflit métier non-bug (LWW déclenché)

### Symptômes
- `last_error` = `stale update detected (local newer)`.
- Pas réellement une erreur — `conflict_log` enregistre, c'est attendu.

### Action
Aucune action : c'est le comportement LWW documenté. Si volume anormal
(>10/jour pour un type donné) : enquête métier sur la coordination
multi-store (qui modifie ce produit en parallèle ?).

---

## 6. Communication

Pendant l'incident :
- Aucune communication aux opérateurs caisses (transparent côté UI).
- Notifier l'équipe dev si type récent (likely Cas A).

Après résolution :
- Confirmer que `sync_inbox WHERE processed=false` retombe.
- Auditer `conflict_log` pour comprendre les divergences.

---

## 7. Post-mortem

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-inbox-errors-<TYPE>.md` :
- Cas (A/B/C/D)
- Nombre d'events affectés et action prise (replay/purge)
- Corrections (handler ajouté ? test ajouté ? procédure rolling deploy ?)
