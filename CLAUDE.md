# CLAUDE.md — KidzPOS

Application POS offline-first pour une chaîne de magasins (contexte Madagascar, devise Ariary).
Architecture : SPA React → REST/SSE → Spring Boot → PostgreSQL.

---

## Stack technique réel

### Frontend
| Outil | Rôle |
|---|---|
| React 18 + TypeScript 5.8 | SPA |
| Vite 8 + `@vitejs/plugin-react-swc` | Bundler (port dev 5173) |
| Zustand 5 + `persist` middleware | État global persisté en localStorage |
| React Router DOM v6 | Routing (lazy loading via `React.lazy`) |
| shadcn/ui (Radix UI) | Composants UI (toute la bibliothèque installée) |
| Tailwind CSS 3 | Styles utilitaires |
| Zod 4 | Validation + parsing des réponses backend |
| TanStack Query v5 | QueryClient présent, mais les stores Zustand font le vrai travail |
| Sonner | Toasts (`toast.success/error` depuis `sonner`) |
| Recharts | Graphiques Dashboard |
| jsPDF | Génération reçus PDF |
| Vitest | Tests unitaires + composant + e2e golden path (134 tests, cf `src/**/*.test.ts(x)`) |
| openapi-typescript | Types TS générés depuis la spec OpenAPI backend (`scripts/gen-api-types.mjs` → `src/lib/api-types.ts`) ; contrat type-level Zod↔OpenAPI (`schemas.contract.ts`) |

### Backend
| Outil | Rôle |
|---|---|
| Spring Boot 3.3.4 / Java 17 | Web, Security, Data JPA, Actuator, Validation |
| PostgreSQL | Seule BDD supportée (pas de SQLite, pas de H2 en prod ; tests via TestContainers) |
| Flyway | Migrations **V1–V22** dans `backend/src/main/resources/db/migration/` |
| JJWT 0.12.6 | Access token stateless court (**15 min** par défaut) + refresh tokens rotatifs httpOnly (**30j**, table `refresh_tokens` V12, détection de reuse) |
| Micrometer + Prometheus | Métriques applicatives (`/actuator/prometheus`), health indicators custom (SSE), MDC corrélé |
| springdoc-openapi | Spec OpenAPI 3 sur `/v3/api-docs` (consommée par la génération de types front) |
| Lombok | `@Builder @Getter @Setter @NoArgsConstructor @AllArgsConstructor` sur toutes les entités |
| BCrypt | Hachage mots de passe via Spring Security |

---

## Structure des dossiers

```
.
├── backend/                          # Spring Boot
│   └── src/main/java/com/kidzpos/
│       ├── config/                   # CorsConfig, DataInitializer
│       ├── domain/                   # Entités JPA (Sale, Product, User…)
│       ├── dto/Dtos.java             # Tous les records request/response dans UN seul fichier
│       ├── events/EventBus.java      # SSE broadcast
│       ├── repo/                     # Repositories Spring Data
│       ├── security/                 # JwtService, JwtAuthFilter, SecurityConfig
│       └── web/                      # Controllers REST (un par domaine)
│   └── src/main/resources/
│       ├── application.yml           # Config complète avec ${ENV_VAR:default}
│       └── db/migration/             # Flyway V1__*, V2__*, V3__*
│
├── src/                              # React SPA
│   ├── App.tsx                       # Router, QueryClientProvider, lazy pages
│   ├── main.tsx                      # Entry point
│   ├── index.css                     # Design system HSL, tokens Tailwind
│   ├── components/
│   │   ├── ui/                       # shadcn/ui (NE PAS modifier manuellement)
│   │   ├── AppLayout.tsx             # Layout principal + RequireRole
│   │   ├── AppSidebar.tsx            # Navigation
│   │   └── OfflineBanner.tsx         # Indicateur hors-ligne + outbox pending
│   ├── pages/                        # POS, Sales, Stock, Settings, Customers, Users, Dashboard, Login
│   ├── store/                        # Zustand stores (auth, data, settings, exchange, backend, customers)
│   ├── lib/
│   │   ├── apiClient.ts              # fetch wrapper, tokenStore, ApiError, pingBackend
│   │   ├── apiConfig.ts              # URL backend configurable
│   │   ├── outbox.ts                 # File offline (localStorage, max 500 entrées)
│   │   ├── sse.ts                    # EventSource avec reconnexion exponentielle
│   │   ├── syncBackend.ts            # hydrateFromBackend() — peuple tous les stores
│   │   ├── schemas.ts                # Schemas Zod miroir des DTOs Java
│   │   ├── money.ts                  # formatMoney() + useFormatMoney() hook
│   │   ├── crypto.ts                 # hashPassword / verifyPassword (SubtleCrypto)
│   │   ├── pdf.ts                    # downloadReceiptPdf / downloadSalesReportPdf
│   │   ├── sync.ts                   # broadcastSync() — BroadcastChannel entre onglets
│   │   └── preload.ts                # Préchargement des pages lazy
│   └── hooks/                        # useDebouncedValue, useHotkeys, useOnlineStatus, use-mobile, use-toast
│
├── .env.example                      # Variables d'environnement (toutes optionnelles)
├── start-server.sh                   # Script LAN : build + démarrage backend + frontend statique
├── CLAUDE.md                         # Ce fichier
└── TODO_FUTURE.md                    # Dette technique documentée
```

---

## Commandes utiles

```bash
# Frontend
npm install          # ou npm ci (CI)
npm run dev          # dev server sur :5173 (vite.config.ts)
npm run build        # build prod dans dist/
npm run test         # vitest run
npm run lint         # eslint

# Backend (depuis backend/)
mvn package -DskipTests        # build JAR
mvn package                    # build + tests
java -jar target/kidzpos-backend-1.0.0.jar   # démarrer

# Serveur LAN complet
./start-server.sh              # démarre avec le JAR + dist/ EXISTANTS (ne rebuild pas)
./start-server.sh stop
./start-server.sh status
./start-server.sh rebuild      # force rebuild JAR + dist/ — OBLIGATOIRE après toute
                               # modif backend (code Java, migration Flyway, application.yml)
                               # sinon le serveur tourne avec l'ancien binaire et p.ex. une
                               # migration V{n+1} fraîchement écrite n'est PAS appliquée.

# PostgreSQL (requis avant démarrage backend)
# Voir backend/README.md § 2.1
```

---

## Architecture offline-first — à comprendre avant de modifier

### Flux d'une mutation

1. **Store Zustand** applique le changement **immédiatement** en local
2. `pushMutation(path, method, body, ref)` tente l'appel REST
3. Si LAN injoignable → enfilé dans `outbox` (localStorage)
4. `startBackendWatcher()` pulse toutes les 5s (offline) / 30s (online)
5. Au retour du backend → `flushOutbox()` rejoue dans l'ordre

### Synchronisation multi-caisses

- Backend publie un événement SSE `change` après chaque mutation
- Toutes les caisses connectées reçoivent l'event → `hydrateFromBackend()` (re-fetch complet)
- SSE avec reconnexion exponentielle (2s→30s) + détection zombie (90s sans ping)
- **Handshake authentifié (M5)** : `EventSource` ne peut pas envoyer d'`Authorization`,
  donc `sse.ts` POST `/api/events/auth` (JWT requis) pour obtenir un UUID single-use
  60s qu'il passe en `?token=…`. Conséquence : `startSse()` est **async**, ses
  callers (`syncBackend`, `store/backend`) préfixent l'appel par `void`.

### Idempotence

- Les ventes envoient `clientSaleId` = ID local généré côté client
- Le backend retourne la vente existante si `clientSaleId` déjà connu (replay outbox safe)

### Bus d'événements DOM (communication inter-modules)

Plutôt que prop drilling ou React context, les modules communiquent via `window.dispatchEvent` :

| Événement | Émetteur | Récepteur | Déclencheur |
|---|---|---|---|
| `auth:session-expired` | `apiClient.ts` (sur 401) | `AppLayout.tsx` | Redirect `/login` sans hard reload |
| `outbox:change` | `outbox.ts` (chaque mutation) | `backend.ts` | Rafraîchit `pendingCount` |
| `api-url:change` | `Settings.tsx` | `backend.ts` | Coupe SSE + re-pulse avec nouvelle URL |

En plus : `visibilitychange` (natif) capturé par `backend.ts` pour re-pulser quand l'onglet redevient visible (sortie de veille).

### Séquence de démarrage précise

```
main.tsx : createRoot().render(<App />)
  └─ queueMicrotask()              ← attend la réhydratation Zustand depuis localStorage
       ├─ startBackendWatcher()    ← pulse() immédiat puis schedule (5s/30s)
       │    └─ hydrateFromBackend()  ← si backend joignable
       │         └─ startSse()       ← SSE démarre APRÈS la première hydratation réussie
       └─ useExchange.refresh()    ← si navigator.onLine
```

**Pourquoi `queueMicrotask` ?** Sans ce délai, `hydrateFromBackend()` écraserait les données offline avant que Zustand ait pu les charger depuis localStorage.

### Synchronisation fédérée magasin ↔ central (paquet `backend/.../sync/`)

Au-delà de la sync temps réel intra-LAN (SSE), il existe un sous-système de **fédération multi-magasin** entre des nœuds *store* et un nœud *central*. Désactivé par défaut (`@ConditionalOnProperty` sur `kidzpos.sync.*` → beans non créés → zéro impact runtime), activé par profil (`application-local.yml` / `application-central.yml`).

- **Push** (`SyncPushService` + `SyncScheduler`) : le store pousse son `operation_log` vers `/api/sync/push` (batch, backoff exponentiel, FROZEN après N essais).
- **Pull** (`SyncPullService` + `SyncPullScheduler`) : tire les events depuis le central, filtré par `storeId`.
- **Inbox** (`SyncInboxProcessor` + 14 `InboxHandler`) : matérialise les events reçus dans les tables métier locales (idempotent, retry, quarantaine après `MAX_RETRIES`, dead-letter `quarantine_events`).
- **Auth server-to-server** : `X-Sync-Api-Key` per-store (V21, `SyncApiKeyFilter`) en plus du JWT ADMIN ; cross-store push/pull rejeté en 403.
- **Exception au "pas de service layer"** : ce paquet `sync/` a des classes service dédiées (logique trop riche pour vivre dans un controller). Les controllers CRUD `web/` restent sans service layer.

### Observabilité (`backend/.../observability/` + `ops/`)

Micrometer/Prometheus (`/actuator/prometheus`), `BusinessMetrics` (seq_retry, idempotent_replay, optimistic_lock, refresh_*…), `SseHealthIndicator`, `CorrelationIdFilter` (MDC corrélé), `SyncLagGauge`. Stack `ops/` : Prometheus + Grafana (3 dashboards) + Alertmanager + 11 runbooks + scripts backup.

---

## Conventions de code observées

### TypeScript / React

- **Pas de commentaires sauf WHY non-évident** : les noms de variables documentent le QUOI
- Imports : alias `@/` pour `src/` (configuré dans `tsconfig.app.json` et `vite.config.ts`)
- Pages exportées en `export default function NomPage()`
- Composants UI : shadcn importés depuis `@/components/ui/`
- `toast.success()` / `toast.error()` via `sonner` (pas le hook shadcn toast)
- Money : **stockage en Ariary** (depuis V5). EUR n'existe que comme vue d'affichage convertie via `useExchange.rate`. Pour le rendu, deux modes :
  - `fmt(amount)` → utilise la devise globale courante (`settings.currency`)
  - `fmt(amount, sale.currency)` → utilise la devise **figée au checkout** de cette vente. Obligatoire sur tous les reçus historiques (POS receipt overlay, Sales detail) sinon un changement de devise globale réécrit visuellement les ventes passées.
  - Toute saisie utilisateur en EUR doit passer par `parseMoneyToAr(input, currency, rate)` avant d'être stockée/envoyée au backend.
- IDs générés client-side : pattern `p${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
- `useMemo` systématique pour les listes filtrées
- `useHotkeys` pour les raccourcis POS (F2, F9, Escape)

### Zustand

- Stores créés avec `create<T>()(persist(…, { name: "kidzpos-*", version: N }))`)
- Migrations de schema via `migrate(persisted, version)` dans les options persist
- **Jamais** de `useState`/`useEffect` pour ce que Zustand peut faire (cf. M6)
- `broadcastSync("kidzpos-*")` après chaque mutation (sync entre onglets)

### Zod

- Schemas dans `src/lib/schemas.ts` miroir exact des DTOs Java
- `.nullish()` pour les champs nullable Postgres (pas `.optional()` seul)
- `.default()` pour les champs avec valeur par défaut backend

### Java / Spring Boot

- **Pas de service layer** : logique métier directement dans les controllers
- Tous les DTOs dans **un seul fichier** `Dtos.java` (records Java)
- Injection par constructeur uniquement (pas `@Autowired`)
- `@Builder @Getter @Setter @NoArgsConstructor @AllArgsConstructor` sur toutes les entités
- `@Valid` sur tous les `@RequestBody`
- `@AuthenticationPrincipal AuthPrincipal me` pour accéder à l'utilisateur courant
- `TransactionTemplate` programmatique (pas `@Transactional` déclaratif dans SaleController, à cause des retries) — **exception** : `SettingsController.update()` utilise `@Transactional` car pas de retry
- `ResponseEntity<?>` comme type de retour des endpoints mutants
- Roles : `ADMIN` et `EMPLOYEE` (Spring Security `hasRole("ADMIN")`)
- `Settings` : singleton en base avec `id=1L` fixe — `repo.findById(1L).orElseThrow()` partout
- **DEV uniquement** : `auth.ts` pré-charge 3 seed users et leurs passwords via `import.meta.env.DEV` — absent en production
- **Soft-delete (Product)** : `@SQLRestriction("deleted_at IS NULL")` filtre toutes les requêtes JPQL. `DELETE /api/products/{id}` pose `deletedAt = now()` au lieu de `repo.deleteById`. Une `UNIQUE (store_id, sku)` partielle (`WHERE deleted_at IS NULL`) autorise le recyclage du SKU après suppression. **Gotcha** : `@SQLRestriction` ne s'applique qu'à JPQL — pour les call sites qui DOIVENT voir les supprimés (refund qui restocke un produit retiré), utiliser `ProductRepository.findByIdIncludingDeleted` (native query).
- **Résidus `ddl-auto: create`** : la base a été initialisée jadis avec `ddl-auto: create`, qui génère des contraintes `CHECK` listant les valeurs des `@Enumerated(EnumType.STRING)`. Désormais en `validate`, ces CHECK ne sont plus rafraîchies. Ajouter une valeur à un enum → SQLState 23514 sur INSERT. **Recette** : migration qui `DROP CONSTRAINT IF EXISTS` puis `ADD CONSTRAINT` avec la liste à jour (cf V6 pour `sales_payment_mode_check`).
- **Devise canonique = Ariary** : tous les montants stockés (DB, Java, state Zustand) sont en AR. EUR est uniquement une vue d'affichage frontend, convertie via `useExchange.rate`. Saisies utilisateur en EUR passent par `parseMoneyToAr()` avant tout stockage.

---

## Règles à respecter

1. **Ne jamais stocker de mots de passe en clair dans l'outbox** — addUser et setPassword exigent `lanReachable: true`
2. **Ne jamais modifier les fichiers `src/components/ui/`** — générés par shadcn, à régénérer via CLI shadcn si besoin
3. **Tout nouveau endpoint backend doit être dans `SecurityConfig`** — par défaut `.anyRequest().authenticated()`
4. **Tout nouveau store Zustand doit incrémenter `version`** et fournir une migration si le schema change
5. **Tout nouveau schema de réponse backend doit avoir son Zod schema dans `schemas.ts`**
6. **Money : jamais `BigDecimal` côté Java sans changer le type TS** — actuellement `double`/`number`
7. **Les migrations Flyway sont irréversibles** — nommer en `V{n+1}__description.sql` avec `IF NOT EXISTS`
8. **`pushMutation` est le seul point d'entrée pour les mutations CRUD** — `api()` ne s'appelle directement que pour les opérations hors-outbox : login (`auth.ts`) et refresh taux de change (`exchange.ts`)

---

## Fichiers / dossiers à ne jamais modifier sans confirmation

| Chemin | Raison |
|---|---|
| `src/components/ui/` | Géré par shadcn — régénérer via CLI, pas manuellement |
| `backend/src/main/resources/db/migration/V1__*.sql` | Migration initiale en production — toute correction = nouvelle migration |
| `.claude/settings.local.json` | Permissions Claude Code — modifier via `/update-config` |
| `bun.lockb` / `package-lock.json` | Lockfiles — ne pas modifier manuellement |
| `backend/target/` | Artefacts compilés — ignorés par git |
| `dist/` | Build frontend — ignoré par git |

---

## Variables d'environnement clés

Toutes optionnelles (valeurs par défaut dans `application.yml` et `start-server.sh`) :

| Variable | Défaut | Usage |
|---|---|---|
| `JWT_SECRET` | Généré aléatoirement | Signer les tokens JWT (invalide les tokens au restart si non fixé) |
| `DB_HOST/PORT/NAME/USER/PASSWORD` | `localhost:5432/kidzpos/kidzpos` | PostgreSQL |
| `SERVER_PORT` | `8080` | Port backend |
| `FRONTEND_PORT` | `3000` | Port frontend statique (start-server.sh) |
| `CORS_ALLOWED_ORIGINS` | Plages LAN privées | CORS (forcer en prod HTTPS) |
| `KIDZPOS_ADMIN/SARAH/KARIM_PASSWORD` | UUID généré au 1er boot | Mots de passe initiaux (DB vide uniquement) |

---

## Dette technique connue (TODO_FUTURE.md)

Voir `TODO_FUTURE.md` pour le détail (résolu ✅ / à faire 🟡). Items **résolus** depuis cette ancienne liste :

- ~~Pas de pagination sur `GET /api/sales`~~ → ✅ `Page<Sale>` + cap dur sur les listings non paginés (anti-OOM), index `idx_sales_store_date` (V10).
- ~~Aucun test d'intégration backend~~ → ✅ TestContainers (Postgres réel), 97 méthodes `@Test`.
- ~~Types TS non générés depuis les DTOs Java~~ → ✅ openapi-typescript + contrat type-level `schemas.contract.ts`.

Items **encore ouverts** (non bloquants, acceptables en LAN mono-magasin) :

- Rate-limit login/refresh + soft-delete + SSE auth sont **in-memory mono-instance** — à porter sur store partagé (Redis) seulement si on passe multi-backend derrière un LB.
- Numéro de ticket via table `store_seq` (alternative au retry `MAX(seq)+1`), logs JSON Loki/ELK, audit log métier dédié, multi-store par utilisateur, export comptable PDF mensuel — cf `TODO_FUTURE.md`.
