# Runbook — Optimistic lock conflict spike

**Alerte associée** : `OptimisticLockConflictSpike` (warning) —
`>10 conflits en 5 min`.

**Sévérité** : P2 (impact UX limité, mais signale un problème de coordination).

**Impact business** : un client (caisse) reçoit `412 Precondition Failed`
lorsqu'il PUT un produit dont la version locale est stale. L'UI doit
recharger le produit puis re-soumettre. Pas de perte de données, juste de la
frustration utilisateur.

---

## 1. Diagnostic en 3 commandes

```bash
# A. Quel produit (ou type d'entité) est affecté ?
grep -E "ObjectOptimisticLockingFailureException|412 Precondition" \
  /chemin/.../logs/backend.log | tail -30

# B. Quels users / caisses ?
grep "412" /chemin/.../logs/access.log | tail -30 | \
  awk '{print $7, $2}' | sort | uniq -c | sort -rn | head -10

# C. Métriques par endpoint
curl -s http://localhost:8080/actuator/prometheus | \
  grep -E "kidzpos_optimistic_lock_conflict|http_server_requests.*status=\"412\""
```

---

## 2. Cas A — Multiple caisses sur le même produit

### Scénario
Deux opérateurs éditent le même produit (changement de prix lors d'une promo,
réajustement de stock manuel) au même moment.

### Action
- **Pas de bug** : l'optimistic lock fait son travail. Le second éditeur reçoit
  412, l'UI recharge, il re-soumet.
- Si fréquent (équipe nombreuse, gestion produits collaborative) : envisager
  un lock distribué côté UI (badge "Marc édite ce produit", via SSE).

---

## 3. Cas B — Frontend ne respecte pas If-Match

### Symptôme
- Une seule caisse affectée mais 412 répétés.
- Logs : `If-Match header missing` ou `ETag stale`.

### Investigation
```bash
# Vérifier que le frontend envoie bien If-Match
# Ouvrir DevTools → Network → PUT /api/products/X → Request Headers
# Doit contenir : If-Match: W/"..."
```

### Recovery
1. Vérifier `src/store/data.ts` `updateProduct()` — doit lire l'ETag depuis le state.
2. Si la version frontend ne supporte pas If-Match (régression) : rollback.

---

## 4. Cas C — Bulk import sans coordination

### Scénario
Un opérateur lance un bulk import CSV pendant qu'une caisse fait un checkout
sur l'un des produits importés.

### Action
- **Communiquer** : les bulk imports doivent se faire hors heures de pointe.
- **Long terme** : ajouter un mode "maintenance" qui bloque les checkouts
  pendant l'import (overkill pour la taille actuelle).

---

## 5. Cas D — Sync inbox processor conflicte avec UI

### Scénario
Le central pull/inbox applique un `product.updated` venant d'un autre store,
écrasant un edit local en cours côté caisse de ce store.

### Action
- C'est le LWW décrit en V20 — voir `sync_inbox_errors.md §5`.
- Vérifier `conflict_log` :
  ```sql
  SELECT entity_id, local_updated_at, remote_updated_at, reason, created_at
  FROM conflict_log
  WHERE entity_type='product' AND created_at > NOW() - INTERVAL '1 hour'
  ORDER BY created_at DESC LIMIT 20;
  ```

---

## 6. Recovery generic

Aucune action immédiate requise — les conflits se résolvent en UX (re-charge +
re-submit côté caisse). Surveiller que le spike redescend.

Si > 100/heure : enquête approfondie (probable bug applicatif, pas
"coordination").

---

## 7. Post-mortem (uniquement si > 100/heure)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-optimistic-lock.md` :
- Pattern (un seul produit ? toute la catalog ? un seul user ?)
- Action corrective (UI lock distribué, formation, rollback)
