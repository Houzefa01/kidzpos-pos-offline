# Runbook — Outbox saturation

**Alerte associée** : `OutboxSaturation` (Prometheus) — `kidzpos_frontend_outbox_size > 8000 for 10m`.

**Sévérité** : P1 (warning, dégradation latence + risque de perte si non traité).

**Impact business** : à 8000 entrées en attente, la caisse concernée prend
~ 17 min minimum à drainer à 5 rps. À 10000, le drain entre en **pause** (HARD_LIMIT).
Au-delà de **12000** (cap outbox), les entrées les plus récentes écrasent les
plus anciennes — **perte silencieuse**.

---

## 1. Détection rapide

```bash
# Vue Grafana
open "http://<host>:3001/d/kidzpos-business"   # panel "Outbox total"

# Ou directement Prometheus
curl -s "http://<host>:9090/api/v1/query?query=kidzpos_frontend_outbox_size" \
  | jq '.data.result[].value[1]'

# Logs backend pour repérer qui est en train de drain
grep "Idempotent stock replay" /chemin/.../logs/backend.log | tail -20
```

---

## 2. Diagnostic — 3 questions

### Q1. La file est-elle en train de redescendre ?

```bash
# Calcul de la pente sur 5 min
curl -s "http://<host>:9090/api/v1/query?query=deriv(kidzpos_frontend_outbox_size[5m])" \
  | jq '.data.result[].value[1]'
```

- **Pente négative** (file descend) → le replay fonctionne, attendre. À 5 rps,
  ~ 300 ops/min se vident. Si pente < -3 (entries/sec), le système se résorbe.
- **Pente ~ 0** → le drain est BLOQUÉ. Aller à Q2.
- **Pente positive** → la caisse produit plus vite que le backend ingère. Cause
  rare en pratique (POS = 5-10 ops/min max). Voir Q3.

### Q2. Le replay est-il pausé (HARD_LIMIT atteint) ?

```bash
# Si outbox > 10000, drainQueue est court-circuitée
curl -s "http://<host>:9090/api/v1/query?query=kidzpos_frontend_outbox_size" \
  | jq '.data.result[].value[1]'
```

Si > 10000 → **bug connu** : la pause se déclenche au-dessus de 10000 mais
n'autorise pas de drain dégradé. Sortie possible :

1. Demander à l'opérateur d'ouvrir le badge "N action(s) non synchronisée(s)"
   dans la bannière.
2. **Acquitter manuellement** quelques entrées via "Tout acquitter" dans le
   FailedReplaysButton — ça les retire de failedReplays (pas de l'outbox).
3. Si la file ne baisse pas, **dernier recours côté caisse** :
   ```js
   // Console navigateur de la caisse concernée
   // 1. Sauvegarder l'outbox actuelle
   const backup = localStorage.getItem("kidzpos-outbox");
   // Copier ce JSON ailleurs pour audit / réinjection manuelle
   
   // 2. Tronquer aux 5000 plus récentes (revient sous HARD_LIMIT)
   const all = JSON.parse(backup);
   localStorage.setItem("kidzpos-outbox", JSON.stringify(all.slice(-5000)));
   window.dispatchEvent(new CustomEvent("outbox:change"));
   ```
   ⚠️ Cette manipulation **PERD** les 3000 entrées les plus anciennes. À ne faire
   que si :
   - on a un backup JSON copié quelque part
   - les ventes correspondantes sont déjà passées au registre papier
   - ou on accepte la perte (cas exceptionnel).

### Q3. Le backend ralentit-il ?

```bash
# p95 latency backend
curl -s "http://<host>:9090/api/v1/query?query=histogram_quantile(0.95,sum%20by%20(le)%20(rate(http_server_requests_seconds_bucket%5B5m%5D)))" \
  | jq '.data.result[].value[1]'

# Si > 1.5s soutenu, le throttle adaptatif (P5) a passé le replay en RECOVERY
# ou DEGRADED → débit réduit à 2-3 rps → la file se vide moins vite.
```

- p95 > 1.5 s → identifier la cause backend (DB locks ? GC ? saturation CPU host ?)
  cf `runbooks/incident_backend_down.md` cas B.
- p95 OK → le throttle est en NORMAL, la file devrait se vider à 5-15 rps.

---

## 3. Mitigation immédiate

### 3.1 — Augmenter temporairement la capacité d'ingestion backend

Si la cause est backend saturé :

```bash
# Augmenter HikariCP pool (par défaut 10)
# Éditer backend/src/main/resources/application.yml :
#   spring.datasource.hikari.maximum-pool-size: 20
# Puis :
./start-server.sh rebuild
```

### 3.2 — Vérifier qu'aucun item ne déclenche un retry boucle

```bash
# Items avec failureCount élevé dans l'outbox client (à demander à l'opérateur via console)
JSON.parse(localStorage.getItem("kidzpos-outbox"))
  .filter(e => (e.failureCount ?? 0) > 10)
  .map(e => ({id: e.id, path: e.path, failureCount: e.failureCount, lastError: e.lastError}))
```

Si beaucoup d'items à failureCount > 10 sur le même path : un endpoint
spécifique pose problème. Diagnostiquer via `tail -f backend.log | grep <path>`.

### 3.3 — Forcer le drain depuis la console (caisse concernée)

```js
// Console navigateur — recharge syncService et déclenche un drain manuel.
const { syncService } = await import("/src/lib/syncService.ts");
await syncService.replay();
```

À utiliser SI le state machine est bloqué en DEGRADED + backend healthy. La
file devrait se débloquer naturellement après quelques drains réussis (3+ pour
sortir de DEGRADED → RECOVERY).

---

## 4. Éviter la perte de données

Tant que la file < 12000, l'éviction FIFO n'a pas commencé. Garder cette marge :

1. Mesurer le débit d'entrées (`rate(...)`) pour estimer le temps avant
   saturation.
2. Si on prévoit > 12000 : faire un **export JSON manuel de l'outbox** avant
   éviction :
   ```js
   const backup = localStorage.getItem("kidzpos-outbox");
   // Télécharger
   const blob = new Blob([backup], {type: "application/json"});
   const url = URL.createObjectURL(blob);
   const a = document.createElement("a");
   a.href = url;
   a.download = `outbox-${Date.now()}.json`;
   a.click();
   ```
3. Ces fichiers JSON peuvent être réinjectés plus tard via une opération
   ad-hoc côté backend (script Python qui replay les mutations en utilisant
   les `clientSaleId`/`clientMovementId` — l'idempotence absorbera les
   doublons).

---

## 5. Post-incident

- Confirmer dans Grafana → Business Sync que la file est revenue sous 1000.
- Vérifier qu'aucune vente n'a été perdue : comparer `SELECT COUNT(*) FROM
  sales WHERE date > '<début incident>'` au registre papier des caisses.
- Si perte avérée, post-mortem obligatoire (cf `incident_backend_down.md §6`).

---

## 6. Action de fond (à backlog)

L'overflow guard P5 est binaire (drain pause vs drain libre). À long terme :
- Permettre un drain dégradé (1 rps, batch=1) même au-dessus de HARD_LIMIT
  pour dégonfler progressivement la file.
- Ajouter une alerte distincte pour `kidzpos_frontend_outbox_size > 11500` →
  "imminent data loss".
- UI : afficher dans le banner offline la taille de la file ET le temps estimé
  avant éviction si > 8000.
