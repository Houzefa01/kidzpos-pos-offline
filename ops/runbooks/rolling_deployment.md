# Runbook — Rolling deployment N magasins

**Quand l'utiliser** : déployer une nouvelle version du backend (et/ou frontend)
sur N magasins + le central, sans downtime, sans casser la sync pendant la
fenêtre de skew.

**Pré-requis** :
- Tag git du release (ex `v1.2.0`) buildé en CI (artefact JAR + image Docker, cf
  `.github/workflows/release.yml`).
- Backup off-site frais (cf `pg_backup.sh` + `offsite_sync.sh`).
- Restore test < 7 jours (cf `test_restore.sh`).
- Runbook `rollback.md` ouvert dans un autre onglet, juste au cas.

---

## 1. Règles d'or

1. **Central avant stores** : le central doit savoir gérer les payloads de l'ancienne
   ET de la nouvelle version. Le store ne sait gérer que SA version.
2. **Migrations Flyway compatibles avant** : si `V{n+1}` casse l'ancien code,
   on déploie un release intermédiaire `V{n}.compat` qui crée la colonne nullable
   AVANT le release qui la rend NOT NULL.
3. **Un magasin à la fois** : si V_new pose un problème, on l'isole sur 1 magasin
   max au lieu de tous.
4. **Healthcheck obligatoire après chaque nœud** : ne pas continuer si
   `/actuator/health` reste DOWN > 2 min.
5. **Toujours préserver une fenêtre de rollback** : ne pas appliquer V{n+2}
   tant que V{n+1} n'a pas tourné en prod sur tous les nœuds depuis 48h.

---

## 2. Phase 0 — Préparation (J-1)

```bash
# 1. Vérifier que CI a produit l'artefact
gh release view v1.2.0 --json assets --jq '.assets[].name'

# 2. Backup off-site frais
./ops/backup/pg_backup.sh && ./ops/backup/offsite_sync.sh

# 3. Test restore récent
./ops/backup/test_restore.sh

# 4. Récap des changements (= ce qui devra être documenté en post-deploy)
git log --oneline v1.1.0..v1.2.0
git diff v1.1.0..v1.2.0 -- backend/src/main/resources/db/migration/
```

### Décision : migration Flyway dans ce release ?

```bash
ls backend/src/main/resources/db/migration/ | sort -V | tail -5
# Si nouvelle V{n+1} présente :
#   → vérifier qu'elle est COMPATIBLE avec V{n} (le central doit pouvoir tourner
#     SUR V{n+1} pendant que les stores sont encore en V{n}).
#   → patterns autorisés : ADD COLUMN nullable, CREATE INDEX, CREATE TABLE.
#   → patterns INTERDITS sans release intermédiaire : DROP COLUMN, RENAME COLUMN,
#     ALTER COLUMN NOT NULL, CHECK constraint plus stricte.
```

---

## 3. Phase 1 — Déploiement central (J0, fenêtre faible activité)

### 3.1 Snapshot DB central pré-déploiement

```bash
ssh central
cd /opt/kidzpos
./ops/backup/pg_backup.sh
LAST_DUMP=$(ls -t backups/kidzpos-*.dump | head -1)
echo "Rollback dump: $LAST_DUMP"
```

### 3.2 Arrêt + déploiement central

```bash
# Build local OU pull image
git fetch && git checkout v1.2.0
./start-server.sh stop
./start-server.sh rebuild     # build JAR + dist
./start-server.sh
```

### 3.3 Vérification central

```bash
sleep 30
./ops/scripts/healthcheck.sh
# Doit retourner exit 0. /actuator/health → UP. Flyway info → V{n+1} applied.

# Tester /api/sync/push depuis l'extérieur
curl -sf -X POST \
  -H "X-Sync-Api-Key: $KIDZPOS_SYNC_API_KEY" \
  -H "X-Sync-Store-Id: probe" \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"probe","operations":[]}' \
  "$CENTRAL_URL/api/sync/push"
# Attendu : 200 OK
```

### 3.4 Watch des métriques pendant 15 min

- Grafana → System Health : status UP, latence stable.
- Alertmanager : aucune alerte critical.
- `SyncInboxErrorRateHigh` : doit rester à 0 (les stores en V{n} continuent à
  pousser, central V{n+1} doit accepter).

**Si DÉGRADATION pendant ces 15 min** : `./ops/runbooks/rollback.md §2` (central).

---

## 4. Phase 2 — Déploiement stores (J0+1, magasin par magasin)

Ordre suggéré : du plus petit volume au plus gros (impact moindre si bug).

### 4.1 Pour CHAQUE store :

```bash
ssh store-s1
cd /opt/kidzpos
./ops/backup/pg_backup.sh && ./ops/backup/offsite_sync.sh   # snapshot pré-deploy
git fetch && git checkout v1.2.0
./start-server.sh stop
./start-server.sh rebuild
./start-server.sh

sleep 30
./ops/scripts/healthcheck.sh

# Vérifier que le push reprend
sleep 30
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME \
  -c "SELECT COUNT(*) FILTER (WHERE synced=false) AS pending,
             MIN(created_at) FILTER (WHERE synced=false) AS oldest_pending
      FROM operation_log;"
# pending devrait diminuer ; oldest_pending récent.
```

### 4.2 Smoke test fonctionnel (1 caisse du store)

- Login OK.
- Recherche produit OK.
- Checkout (1 produit, CASH, montant exact) → reçu généré.
- Refund de cette vente test → stock restocké.
- Pas de toast d'erreur, pas de badge "N échec(s)".

### 4.3 Watch 5 min avant de passer au store suivant

- `SyncLagHigh` ne doit pas se déclencher.
- `SyncInboxErrorRateHigh` ne doit pas apparaître côté central pour les events
  émis par ce store.

**Si problème** : `rollback.md §3` (un seul store). Les autres stores en V{n}
continuent normalement, le central V{n+1} les sert toujours.

---

## 5. Phase 3 — Post-déploiement (J+1 → J+7)

- Surveiller `SyncInboxErrorRateHigh`, `OptimisticLockConflictSpike`,
  `HighBackend5xxRate` pendant 48h.
- Comparer les compteurs métiers (ventes/jour) à la moyenne pré-deploy.
- Mettre à jour le CHANGELOG + le tag `prod-current` :
  ```bash
  git tag -f prod-current v1.2.0 && git push -f origin prod-current
  ```

---

## 6. Cas particulier : migration Flyway destructive

Si on doit DROP COLUMN ou ALTER NOT NULL, **2 releases successifs** :

| Release | Migration | Code |
|---|---|---|
| V{n+1} (compat) | Ajoute la nouvelle colonne nullable ET conserve l'ancienne | Lit/écrit les DEUX |
| (attendre 7 jours en prod sur tous les nœuds + backups OK) |||
| V{n+2} (cleanup) | Backfill (UPDATE) puis DROP / ALTER NOT NULL | N'utilise plus l'ancienne |

Ce pattern garantit qu'on peut rollback à V{n} même après V{n+1}, et qu'on peut
rollback de V{n+2} à V{n+1} si V{n+2} casse quelque chose.

---

## 7. Si tout casse

Voir `rollback.md`. Les backups + l'idempotence + l'outbox des caisses sont les
3 filets de sécurité — aucune vente n'est perdue tant que ces 3 fonctionnent.
