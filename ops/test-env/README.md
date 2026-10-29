# Test-env multi-serveur (central + store local)

Setup pour tester la **synchronisation complète** entre :
- un **CENTRAL** sur `localhost:8080` (API) + `localhost:3000` (UI)
- un **STORE s1** sur `localhost:8081` (API) + `localhost:3001` (UI)

Tout tourne sur ta machine. Frontend dupliqué en 2 builds avec URL API câblée à la compilation. Aucune modification du code métier.

---

## Prérequis

- Java 17+
- Maven
- Node 18+ / npm (pour build frontends + `npx serve`)
- PostgreSQL local (running)
- Le JAR backend buildé : `cd backend && mvn -DskipTests package`

---

## Setup (1 fois)

### 1) Créer les 2 bases Postgres

```bash
psql -U postgres -h localhost -f ops/test-env/init-test-databases.sql
```

Crée `kidzpos_central` et `kidzpos_store_s1`, plus le rôle `kidzpos`/`kidzpos` s'il manque. Flyway s'occupera du schéma au démarrage.

### 2) Build des 2 frontends (URL API câblée à la compilation)

```bash
./ops/test-env/build-frontends.sh         # les deux d'un coup
# OU séparément :
./ops/test-env/build-frontends.sh central
./ops/test-env/build-frontends.sh store
```

Produit :
- `dist-central/` — pointe vers `http://localhost:8080` via `VITE_API_URL`
- `dist-store/` — pointe vers `http://localhost:8081`

Le `dist/` original (utilisé par `start-server.sh` mono-serveur) n'est **pas** touché.

### 3) Rendre les scripts exécutables

```bash
chmod +x ops/test-env/*.sh
```

---

## Lancement

```bash
# Terminal 1 — CENTRAL (API :8080 + UI :3000)
./ops/test-env/start-central.sh

# Terminal 2 — STORE s1 (API :8081 + UI :3001)
./ops/test-env/start-store.sh
```

Chaque script démarre **2 process** :
- backend Spring Boot (`java -jar`)
- serveur statique `npx serve` du frontend correspondant

PIDs traqués dans `logs/{central,central-frontend,store-s1,store-s1-frontend}.pid`.

Ouvrir dans le navigateur :
- **CENTRAL UI** → <http://localhost:3000>
- **STORE s1 UI** → <http://localhost:3001>

(les deux UI sont indépendantes ; chacune tape directement son backend câblé. Aucune manipulation `localStorage` requise. Ouvrir les deux en mode incognito si tu veux isoler les JWTs.)

Suivre les logs :

```bash
tail -f logs/central.log logs/central-frontend.log
tail -f logs/store-s1.log logs/store-s1-frontend.log
```

Arrêter (backends + frontends d'un coup) :

```bash
./ops/test-env/stop-all.sh
```

---

## Vérifier l'état via les endpoints debug

Activés uniquement avec `KIDZPOS_DEBUG_ENABLED=true` (les scripts le posent automatiquement). Auth ADMIN requise.

```bash
# Login admin (mot de passe par défaut = admin123)
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@kidzpos.com","password":"admin123"}' \
  | jq -r .token)

# Snapshot du store
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/debug/status | jq

# Derniers events journalisés (operation_log)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/debug/operations?limit=10" | jq

# Inbox du store (events reçus du central)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/debug/inbox?limit=10" | jq

# Conflits détectés
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/debug/conflicts?limit=10" | jq

# Quarantaines
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/debug/quarantine?limit=10" | jq
```

Mêmes endpoints sur le central (port 8080).

---

## Reset complet

```bash
./ops/test-env/stop-all.sh
psql -U postgres -h localhost -c "DROP DATABASE kidzpos_central;"
psql -U postgres -h localhost -c "DROP DATABASE kidzpos_store_s1;"
psql -U postgres -h localhost -f ops/test-env/init-test-databases.sql

# Rebuild les frontends si le code TS a changé :
./ops/test-env/build-frontends.sh
```

---

## Rebuild après modification du code

| Code modifié | Action |
|---|---|
| Backend Java (controllers, handlers, repos…) | `cd backend && mvn -DskipTests package` puis `stop-all.sh` + relance |
| Frontend (React, TS, CSS) | `./ops/test-env/build-frontends.sh` (rebuilds les 2 dist) puis `stop-all.sh` + relance |
| Config Spring (`application*.yml`) | Rebuild backend + relance |

---

## Cadences sync (test vs prod)

| Cycle | Test (ce setup) | Prod (défaut) |
|---|---|---|
| Push (store → central) | 5 s | 30 s |
| Pull (central → store) | 10 s | 60 s |
| Inbox processor | 10 s | 60 s |

Configurable via env :

```bash
export KIDZPOS_SYNC_INTERVAL_MS=5000
export KIDZPOS_SYNC_PULL_INTERVAL_MS=10000
export KIDZPOS_SYNC_INBOX_INTERVAL_MS=10000
```

---

## Secrets test (ne JAMAIS utiliser en prod)

| Variable | Valeur test |
|---|---|
| `JWT_SECRET` (central) | `test-central-jwt-secret-must-be-at-least-32-bytes-long-xyz` |
| `JWT_SECRET` (store) | `test-store-s1-jwt-secret-must-be-at-least-32-bytes-long-abc` |
| `KIDZPOS_SYNC_API_KEY` (store) | `test-sync-key-shared-123456789` |
| `KIDZPOS_SYNC_INBOUND_API_KEY` (central) | `test-sync-key-shared-123456789` |
| Admin password (les deux) | `admin123` |

## V21 — Migration vers clés API par store (optionnel pour les tests)

Le mode legacy (clé partagée ci-dessus) reste accepté tant qu'aucune clé
per-store n'existe en base. Pour tester le mode V21 :

```bash
# 1) Login admin sur le central
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@kidzpos.com","password":"admin123"}' \
  | jq -r .token)

# 2) Créer une clé pour le store s1 — COPIER le plaintext, il n'apparaîtra plus
curl -s -X POST http://localhost:8080/api/sync/keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"storeId":"s1","label":"store s1 test-env"}' | jq

# Response :
# {
#   "id": "…",
#   "storeId": "s1",
#   "label": "store s1 test-env",
#   "createdAt": "…",
#   "plaintext": "abc…xyz"   ← copier IMMÉDIATEMENT
# }

# 3) Redémarrer le store avec la nouvelle clé
./ops/test-env/stop-all.sh
KIDZPOS_SYNC_API_KEY="abc…xyz" ./ops/test-env/start-store.sh

# 4) Vérifier que le push remonte (le central va exiger X-Sync-Store-Id) :
tail -f logs/central.log | grep '\[sync\]'

# Listing des clés actives (sans révéler le hash)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/sync/keys | jq

# Révoquer une clé
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/sync/keys/<id> | jq
```

Côté central, dès qu'une clé existe en DB, **le mode legacy est désactivé** :
tout push sans `X-Sync-Store-Id` valide est rejeté avec un log warn. Tester
qu'un store compromis ne peut PAS forger un push pour un autre store :

```bash
# Avec la clé de s1, essayer de push pour s2 → 403 attendu
curl -X POST http://localhost:8080/api/sync/push \
  -H "X-Sync-Api-Key: <key-de-s1>" \
  -H "X-Sync-Store-Id: s1" \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"forge","operations":[{"id":"00000000-0000-0000-0000-000000000001","type":"product.created","payload":"{}","createdAt":"2026-01-01T00:00:00Z","storeId":"s2"}]}'
# → {"error":"cross-store push forbidden","authenticatedStoreId":"s1","rejectedStoreId":"s2"}
```

---

## Architecture du test

```
┌─────────────────────────────────────────────────────────────────────┐
│  Machine locale                                                      │
│                                                                      │
│  Navigateur                                                          │
│  ┌──────────────────┐         ┌──────────────────┐                  │
│  │ UI Central :3000 │         │ UI Store :3001   │                  │
│  │ (dist-central)   │         │ (dist-store)     │                  │
│  └────────┬─────────┘         └────────┬─────────┘                  │
│           │ API direct                  │ API direct                 │
│           ▼                             ▼                            │
│  ┌──────────────────┐    push    ┌──────────────────┐               │
│  │ BACKEND :8080    │ ◄────────► │ BACKEND :8081    │               │
│  │ profile=central  │    pull    │ profile=local    │               │
│  │ storeId=<empty>  │            │ storeId=s1       │               │
│  └────────┬─────────┘            └────────┬─────────┘               │
│           │                                │                         │
│           └─────────────┬──────────────────┘                         │
│                         ▼                                            │
│                  ┌──────────────┐                                    │
│                  │ PostgreSQL   │                                    │
│                  │ :5432        │                                    │
│                  │ • kidzpos_central                                 │
│                  │ • kidzpos_store_s1                                │
│                  └──────────────┘                                    │
└─────────────────────────────────────────────────────────────────────┘
```
