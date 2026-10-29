# Runbook — Rollback d'un déploiement

**Quand l'utiliser** : un déploiement vient de provoquer une régression
(5xx en hausse, sync stuck, UI cassée). On veut revenir à la version qui
tournait juste avant.

**Pré-requis** : avoir un tag `prod-current` qui pointe vers la version
précédemment stable (mis à jour à la fin de chaque rolling deployment OK).

---

## 1. Décision en 30 secondes

```
Y a-t-il une migration Flyway DESTRUCTIVE dans le release fautif ?

  NON  → rollback simple (§2 central / §3 store) — RTO ~2 min
  OUI  → rollback DB requis (§4) — RTO ~15 min, RPO selon backup
```

```bash
# Check rapide :
git diff prod-current..v_BUGGY -- backend/src/main/resources/db/migration/ | head -50
# Cherche : DROP, ALTER COLUMN .* NOT NULL, RENAME COLUMN, CHECK plus stricte.
```

---

## 2. Rollback central (sans migration destructive)

```bash
ssh central
cd /opt/kidzpos

# 1. Snapshot rapide AVANT rollback (au cas où le rollback aussi casse)
./ops/backup/pg_backup.sh

# 2. Retour au tag stable précédent
git fetch
git checkout prod-current
./start-server.sh stop
./start-server.sh rebuild
./start-server.sh

# 3. Healthcheck
sleep 30
./ops/scripts/healthcheck.sh

# 4. Vérifier que le central accepte à nouveau les push
curl -sf -X POST \
  -H "X-Sync-Api-Key: $KIDZPOS_SYNC_API_KEY" -H "X-Sync-Store-Id: probe" \
  -H "Content-Type: application/json" -d '{"nodeId":"probe","operations":[]}' \
  "$CENTRAL_URL/api/sync/push"
```

**Si les stores sont déjà passés en V_BUGGY** :
- Si V_BUGGY n'a PAS changé le payload outbox → OK, rollback central suffit.
- Sinon → rollback stores aussi (§3 sur chaque).

---

## 3. Rollback d'un store

```bash
ssh store-sN
cd /opt/kidzpos

# 1. Snapshot
./ops/backup/pg_backup.sh

# 2. Pause sync push pour éviter d'envoyer des payloads V_BUGGY au central
echo "KIDZPOS_SYNC_PUSH_ENABLED=false" >> /etc/kidzpos/.env

# 3. Rollback
git fetch && git checkout prod-current
./start-server.sh restart

# 4. Healthcheck + reprendre la sync
./ops/scripts/healthcheck.sh
sed -i 's/^KIDZPOS_SYNC_PUSH_ENABLED=false/KIDZPOS_SYNC_PUSH_ENABLED=true/' /etc/kidzpos/.env
./start-server.sh restart
```

Les caisses (offline-first) ne perçoivent rien — l'outbox absorbe la coupure.

---

## 4. Rollback AVEC migration destructive

⚠️ Flyway ne supporte pas les down scripts. Seules options :

### Option A — Restore depuis backup (RPO = âge du dernier dump)

```bash
ssh central   # ou store, même procédure
cd /opt/kidzpos

# 1. Arrêter le backend (sinon écritures concurrentes pendant restore)
./start-server.sh stop

# 2. Identifier le dump AVANT V_BUGGY
LAST_PRE_DEPLOY=$(grep -l "v$PREVIOUS_VERSION" backups/*.log 2>/dev/null | head -1)
# Sinon : ls -lt backups/kidzpos-*.dump | head -3
DUMP="..."

# 3. Drop + recreate DB
sudo -u postgres psql <<SQL
DROP DATABASE IF EXISTS kidzpos_old;
ALTER DATABASE kidzpos RENAME TO kidzpos_old;     -- garde une copie au cas où
CREATE DATABASE kidzpos OWNER kidzpos;
SQL

# 4. Restore
PGPASSWORD=$DB_PASSWORD pg_restore \
  --host=$DB_HOST --username=$DB_USER --dbname=$DB_NAME \
  --no-owner --no-privileges --exit-on-error \
  "$DUMP"

# 5. Code à la version stable
git checkout prod-current
./start-server.sh rebuild
./start-server.sh

# 6. Healthcheck
./ops/scripts/healthcheck.sh

# 7. Cleanup kidzpos_old APRÈS validation 24h
# sudo -u postgres psql -c "DROP DATABASE kidzpos_old;"
```

**Perte de données** : toutes les écritures entre `$DUMP` et l'incident.
Pour les stores : l'outbox des caisses + les events `operation_log synced=false`
permettent de **rejouer** ces écritures via :
```bash
# Force le push de tous les events post-dump
PGPASSWORD=$DB_PASSWORD psql -c "UPDATE operation_log SET synced=false, attempts=0
  WHERE created_at > '$DUMP_TS';"
```

### Option B — Migration de compensation manuelle

Si V_BUGGY ne peut pas être restaurée (long downtime inacceptable) :
écrire `V{n+2}__rollback_V{n+1}.sql` qui défait ce que V_BUGGY a fait
(ex: re-CREATE la colonne droppée + backfill depuis une source). Compétence
DBA requise. Documentation obligatoire dans post-mortem.

---

## 5. Communication

Pendant le rollback :
1. **Manager** : "On revert, ETA X minutes. Aucune vente perdue, l'outbox couvre."
2. **Caisses** : aucune com nécessaire (transparent côté UX).
3. **Slack/Telegram ops** : `📉 ROLLBACK v1.2.0 → v1.1.0 sur central — cause: <résumé>`.

Après rollback :
1. Confirmer healthcheck OK partout (`./ops/scripts/healthcheck.sh`).
2. Surveiller 30 min : aucune alerte critical, latence stable.
3. Démarrer post-mortem (§7).

---

## 6. Rebuild d'un environnement complet (worst case)

Si le central est totalement perdu (HDD mort) et qu'aucun backup local n'est OK :

```bash
# 1. Récupérer le dernier dump depuis le NAS / S3
rsync backup@nas.local:/volume1/kidzpos/store-s1/kidzpos-*.dump ./backups/
# ou : rclone copy s3-backup:kidzpos-prod/ ./backups/

# 2. Vérifier checksum + integrity
cd backups && sha256sum -c kidzpos-*.dump.sha256
pg_restore --list kidzpos-LATEST.dump >/dev/null

# 3. Restore (option A ci-dessus)

# 4. Si CRITIQUE et plusieurs stores : restore CHAQUE store en parallèle
#    sur des machines différentes pour réduire RTO.
```

---

## 7. Post-mortem (OBLIGATOIRE après tout rollback)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-rollback-vX.Y.Z.md` :
- Quel commit/release a déclenché le bug
- Pourquoi les tests CI ne l'ont pas attrapé
- Action corrective : test à ajouter, lint à ajouter, étape à ajouter au
  rolling_deployment.md
- Coût (downtime, données rejouées, time-to-resolution)
