# TODO_FUTURE.md — Améliorations reportées

État au 2026-05-26. Items hors-scope du plan strict mais identifiés
pendant les audits successifs. Aucun n'est bloquant.

Légende : **🟡 À faire** · **✅ Résolu** (date + référence)

---

## Sécurité

### ✅ Cross-store mutations + lectures (audit P1 + P4 — 2026-05)
`StoreAccessGuard.denyIfCrossStore(me, storeId)` appliqué uniformément :
- Mutations (5 endpoints) : `Sale.checkout/refund`, `Stock.adjust`,
  `Product.create/update`. `delete` + `Stock.transfer` restent ADMIN-only.
- Lectures (3 endpoints) : `GET /api/sales`, `/api/products`,
  `/api/stock/movements` — 403 strict pour EMPLOYEE cross-store, ADMIN
  passe partout. Frontend `syncBackend.ts` passe `?storeId=` pour EMPLOYEE.
- Couverture : `StoreAccessGuardTest` (6) + `CrossStoreReadGuardTest` (10).
- Message d'erreur 403 neutre (anti-énumération).

### ✅ Rate limiting `/api/auth/login` (M3)
`LoginRateLimitFilter` in-memory : fenêtre 5 min / 5 tentatives, seuls
les 401 consomment le quota. Mono-instance ; portage Redis nécessaire si
multi-backend.

### ✅ Rate limiting `/api/auth/refresh` (P2.2)
`RefreshRateLimitFilter` : fenêtre 1 min / 10 tentatives. Même remarque
mono-instance que login.

### ✅ Authentification SSE `/api/events/stream` (M5)
`EventTokenStore` émet un UUID single-use 60s via `POST /api/events/auth`
(auth JWT) ; `GET /api/events/stream?token=…` consomme avant d'ouvrir
l'`SseEmitter`.

### ✅ JWT fail-fast (audit P1.2 — 2026-05)
`JwtService` lève `IllegalStateException` au boot si `JWT_SECRET` vide
ou < 32 octets. Plus de défaut dans `application.yml`.

### ✅ Refresh tokens rotatifs (P2 sécurité)
V12 — `refresh_tokens` table, SHA-256 hash, rotation à chaque refresh,
revoke-all en cascade sur reuse détecté. Cleanup `@Scheduled` quotidien.

### ✅ Headers sécurité HTTP (PR http-hardening — 2026-05)
Implémenté dans `SecurityConfig.filterChain` :
- `Strict-Transport-Security` : 1 an + includeSubDomains (émis uniquement
  quand `request.isSecure()` → actif derrière Caddy/nginx HTTPS, no-op en LAN HTTP).
- `Content-Security-Policy` : `default-src 'none'; frame-ancestors 'none';
  base-uri 'none'; form-action 'none'` (le backend ne sert que du JSON).
- `X-Frame-Options: DENY` (double ceinture anti-clickjacking avec frame-ancestors).
- `Referrer-Policy: strict-origin-when-cross-origin`.
- `X-Content-Type-Options: nosniff` (défaut Spring conservé).

### 🟡 Rate-limit multi-instance
`LoginRateLimitFilter` + `RefreshRateLimitFilter` sont in-memory.
Acceptable mono-instance LAN ; portage Redis (ou bucket4j-redis) requis
si on passe en multi-backend derrière un LB.

---

## Performance

### ✅ Pagination `GET /api/sales` (audit perf)
`SaleController.list(?page=&size=)` retourne `Page<Sale>` (PageRequest
clamp 1-500). Rétrocompat : sans `page` → liste complète. Index dédié
`idx_sales_store_date` (V10).

### ✅ Soft-delete Product (audit perf — intégrité)
`@SQLRestriction("deleted_at IS NULL")` + V4 (colonne `deleted_at` +
unique partiel `(store_id, sku) WHERE deleted_at IS NULL`). Refund
utilise `findByIdIncludingDeleted` (native bypass).

### ✅ Optimistic locking Product (V11)
`@Version` JPA → ETag/If-Match sur `GET/PUT /api/products/{id}`,
conflit = 412.

### ✅ Séparation `useSales` non persisté (audit P1.3 — 2026-05)
Extrait `sales`/`saleSeq` du store persisté `useData` pour éviter la
saturation localStorage à long terme. Migration v5→v6 + `partialize`
whitelist. Backend = source de vérité (réhydraté au boot).

### ✅ Cache utilisateur dans `JwtAuthFilter`
`UserCache` (cf `security/UserCache.java`, couvert par `UserCacheTest`)
mémoïse la résolution `users.findById(...)` injectée dans `JwtAuthFilter`
→ supprime le SELECT par requête sur la table users.

### ✅ ETag sur `GET /api/products` (collection)
`ProductController.list` calcule un weak ETag dérivé du contenu de la
collection. `If-None-Match` correspondant → 304 Not Modified body vide
(ré-émission de l'ETag, RFC 7232 §4.1). Côté front : `apiWithEtag`.

### ✅ Compression gzip Spring
Active dans `application.yml` (`server.compression`) : `application/json`
uniquement, `min-response-size: 1024`. `text/event-stream` volontairement
EXCLU (gzip bufferise et casserait le streaming SSE temps réel).

### 🟡 Numéro de ticket via table `store_seq`
Alternative au retry `MAX(seq)+1` actuel (`SaleController.runWithSeqRetry`).
Table dédiée `store_seq(store_id, next_seq)` avec `UPDATE … RETURNING`
atomique. Plus robuste à fort débit. Pas urgent (LAN, 2 caisses).

---

## DX

### ✅ Tests intégration backend TestContainers
`IntegrationTestBase` + `ConcurrentCheckoutTest`, `RefundConcurrencyTest`,
`AuthRefreshFlowTest`, `RefreshHardeningTest`, `IdempotentReplayTest`.
Couvre les invariants critiques sur checkout/refund/stock concurrents.

### ✅ Tests Vitest sur sync engine (audit DX + P3)
Couvre `apiClient`, `outbox` (via `syncService.test.ts`),
`failedReplays`, `metricsReporter`, throttle/stateMachine/backoff,
`ids`, et tests composant POS + E2E runtime golden path.

### ✅ ESLint `@typescript-eslint/no-floating-promises` (P3.1 — 2026-05)
Activé en type-aware ciblé. 24 violations corrigées via `void`.

### ✅ Helper `newId()` unifié (P2.3)
`crypto.randomUUID()` → `crypto.getRandomValues()` (16 octets) →
`Date.now()+Math.random` fallback. Élimine les collisions d'IDs en boucle
synchrone (régression `bulkImportProducts`).

### ✅ Génération des types TS depuis les DTOs Java
Pipeline en place :
1. springdoc-openapi expose `/v3/api-docs` (spec OpenAPI 3 auto-générée).
2. `scripts/gen-api-types.mjs` (+ `npm run gen:api-types`) génère
   `src/lib/api-types.ts` via openapi-typescript.
3. `src/lib/schemas.contract.ts` assert AU `tsc` que les `z.infer<*Schema>`
   sont assignables aux types OpenAPI générés (Sale/Product/Customer/Settings)
   → la dérive FE/BE est attrapée à la build, pas en prod. Zéro octet runtime.

### ✅ Tests Vitest dédiés sur `money.ts` (audit 2026-05)
`src/lib/money.test.ts` (10 cas) : `parseMoneyToAr` (AR identité, EUR ×taux,
NaN/Infinity→0, 0/négatifs), `formatMoneyAs` (arrondi AR, 2 décimales EUR,
taux≤0→0, withSymbol:false), round-trip, `currencySymbol`.

### 🟡 Test composant Sales (refund flow)
Sur le même pattern que `POS.test.tsx`. Couvre refund + assertions
state useSales/useData/customers.

---

## Observabilité

### ✅ Stack ops complète
Prometheus + Grafana + Alertmanager + bot Telegram + node-exporter +
postgres-exporter + 3 dashboards Grafana + runbooks
(`outbox_saturation.md`, `incident_backend_down.md`) + backup script.

### ✅ Métriques applicatives (BusinessMetrics + FrontendMetricsCollector)
`/actuator/prometheus` expose :
- `kidzpos.sale.seq_retry`, `kidzpos.sale.idempotent_replay`
- `kidzpos.optimistic_lock.conflict`, `kidzpos.login.rate_limited`
- `kidzpos.auth.refresh_*` (success, failure, reuse_detected, cleanup)
- `kidzpos.frontend.outbox_size`, `kidzpos.frontend.failed_replays`,
  `kidzpos.frontend.sync_latency_ms` (histogram p50/p95/p99)
- `kidzpos.frontend.replay_*` (P5 adaptive metrics)

### ✅ MDC structuré (correlationId + userId + storeId)
`CorrelationIdFilter` injecte les champs dans le MDC ; logback affiche
`[correlationId] [userId/storeId]` à chaque ligne. Grep-friendly.

### 🟡 Logs JSON (Loki / ELK)
Aujourd'hui pattern texte. Pour Loki/ELK : ajouter `logstash-logback-encoder`
et remplacer le pattern par `<encoder class="net.logstash.logback.encoder.LogstashEncoder"/>`.
Référence : commentaire en tête de `logback-spring.xml`.

### ✅ Healthcheck enrichi (branche audit/healthcheck-enrichi)
`/actuator/health` (show-details: when_authorized) agrège :
- `sseBus` : indicateur custom `SseHealthIndicator` — UP/DOWN selon la
  fraîcheur du dernier broadcast (stale = heartbeater bloqué), avec détails
  subscribers / lastBroadcastAt / secondsSinceLastBroadcast.
- `db` + `flyway` : contributeurs actuator natifs (état DB + migrations).
Couverture : `SseHealthIndicatorTest`. Reste 🟡 optionnel : exposer la
taille outbox côté serveur (notion non encore matérialisée côté backend).

### 🟡 Audit log dédié
Table `audit_log` pour traçabilité forte :
- Création/suppression user
- Refund (qui, quand, montant, original sale)
- Ajustement stock (delta, raison, opérateur)
- Changement settings (diff avant/après)
- Login échoués → bonus anti-incident

Différent des access logs HTTP : sémantique métier, requêtable par auditeur
externe (fiscal, sécurité).

---

## Métier

### 🟡 Multi-store par utilisateur
Un caissier peut basculer entre plusieurs magasins assignés.
Modèle : `user_stores(user_id, store_id)` (relation N:N) + UI sélecteur
de magasin actif. Implique adaptation de `me.storeId()` → set de stores,
et propagation dans `StoreAccessGuard`.

### 🟡 Export comptable PDF mensuel par magasin
Conformité fiscale malgache. Récapitulatif ventes/TVA/modes de paiement
par mois, signé numériquement. À cadrer avec le client final.

### 🟡 Devise canonique : migrer `double` → `long` (iraimbilanja)
Aujourd'hui : `double` en Ariary (entier de fait, pas de centimes). Si
EUR devient mode de paiement réel (pas juste affichage), les `double`
EUR causeront des erreurs d'arrondi à l'accumulation. Migration vers
`long` (iraimbilanja = 1/5 Ar, mais en pratique 1 Ar suffit) ou
`BigDecimal`. Touche entité Sale/Product + frontend TS.

---

## Méta

### 🟡 Backlog historique pré-audit
`AUDIT_FIXES.md` et anciens lots de phase (M1…M9, B1…B6, I1…I8, P0…P5)
sont référencés dans les commits. Pas de consolidation centralisée :
la connaissance est dans les commit messages + CLAUDE.md. Acceptable.
