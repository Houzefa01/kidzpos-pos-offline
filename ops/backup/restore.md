# Restauration PostgreSQL — KidzPOS

Procédures pour restaurer un dump produit par `pg_backup.sh`.

Tous les dumps sont au format **custom** (`-Fc`) — ne pas tenter `psql -f` dessus,
utiliser `pg_restore`.

---

## 1. Pré-flight obligatoire

```bash
# Lister les backups disponibles
ls -lh /chemin/absolu/backups/kidzpos-*.dump | tail -10

# Vérifier l'intégrité du dump avant tout
DUMP=/chemin/absolu/backups/kidzpos-20260516T020001.dump
sha256sum -c "${DUMP}.sha256"      # doit afficher "OK"
pg_restore --list "$DUMP" | head   # doit lister tables/objets

# Identifier les variables d'env (cf .env ou docker-compose)
echo "Cible : $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
```

⚠️ **AVANT toute restore destructive** : faire un dump de l'état actuel pour
pouvoir revenir en arrière :

```bash
./ops/backup/pg_backup.sh
```

---

## 2. Restore full database (drop + recréer)

Procédure standard pour récupérer d'une corruption complète.

```bash
# 1) Stopper le backend (sinon il rebrancherait des connexions pendant le drop)
./start-server.sh stop

# 2) Drop + recréer la base
export PGPASSWORD="$DB_PASSWORD"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"

# 3) Restore
pg_restore \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  --no-owner --no-privileges \
  --jobs=4 \
  --verbose \
  "$DUMP"
unset PGPASSWORD

# 4) Vérification rapide
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT COUNT(*) FROM sales;"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# 5) Redémarrer
./start-server.sh
./ops/scripts/healthcheck.sh
```

**Temps estimé** : ~ 1-3 min pour une base 1 GB.

---

## 3. Restore d'une seule table (ex: sales)

Quand un mauvais update / delete a corrompu une table mais pas le reste.

```bash
# 1) Backup pre-restore (sécurité)
./ops/backup/pg_backup.sh

# 2) Extraire la liste des objets et filtrer ceux qu'on veut
pg_restore --list "$DUMP" > /tmp/restore.list
# Éditer /tmp/restore.list pour commenter les lignes à NE PAS restaurer
# (préfixer par ';'). Garder uniquement : TABLE DATA sales, TABLE DATA sale_items.

# 3) Restore filtré (et OVERWRITE — TRUNCATE puis insert)
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -c "TRUNCATE TABLE sale_items, sales CASCADE;"

pg_restore \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  --data-only \
  --use-list=/tmp/restore.list \
  "$DUMP"

# 4) Recalculer les séquences si nécessaire
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "
  SELECT setval(pg_get_serial_sequence('sale_items','id'), COALESCE(MAX(id),1)) FROM sale_items;
"
```

⚠️ Limites : la table `sales` est référencée par `sale_items` (FK). Restaurer
sales sans sale_items casserait la cohérence. Pour ne restaurer QU'UNE table
isolée (ex: customers), c'est plus simple.

---

## 4. Rollback scenario : migration Flyway qui casse la base

```bash
# 1) Identifier la version foireuse
psql -d "$DB_NAME" -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"

# 2) Si success=false : la migration a échoué proprement. Solution :
#    a) Corriger le SQL de la migration dans backend/src/main/resources/db/migration/Vn__*.sql
#    b) Marquer la version comme repair via Flyway CLI :
#       flyway -url=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME -user=$DB_USER -password=$DB_PASSWORD repair
#    c) Relancer le backend : Flyway retentera la migration.

# 3) Si la migration s'est appliquée (success=true) mais a corrompu logiquement :
#    Option A — Restore complet depuis le dump pré-migration (cf §2)
#    Option B — Écrire Vn+1__undo_xxx.sql qui défait les changements de Vn
#               Recommandé pour les corrections en avant (Flyway ne supporte pas
#               nativement le rollback descendant).

# 4) Vérifier flyway_schema_history après reprise
psql -d "$DB_NAME" -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 5. Test de restore mensuel (PRATIQUE OBLIGATOIRE)

Un backup non testé n'est pas un backup. Procédure mensuelle :

```bash
# Dans un environnement isolé (DB de test ou container ad-hoc)
docker run -d --name pg-restore-test \
  -e POSTGRES_PASSWORD=test -e POSTGRES_USER=test -e POSTGRES_DB=kidzpos_test \
  -p 5433:5432 postgres:15-alpine

sleep 5

LATEST_DUMP=$(ls -t /chemin/absolu/backups/kidzpos-*.dump | head -1)
PGPASSWORD=test pg_restore -h localhost -p 5433 -U test -d kidzpos_test \
  --no-owner --no-privileges --jobs=4 "$LATEST_DUMP"

# Vérifier le row count plausible vs prod
PGPASSWORD=test psql -h localhost -p 5433 -U test -d kidzpos_test -c "
  SELECT 'sales', COUNT(*) FROM sales
  UNION ALL SELECT 'products', COUNT(*) FROM products
  UNION ALL SELECT 'customers', COUNT(*) FROM customers
  UNION ALL SELECT 'users', COUNT(*) FROM users;
"

docker rm -f pg-restore-test
```

Documenter le run dans un `restore-test-YYYYMM.md`. Si le test échoue → ne pas
attendre, investiguer et corriger pg_backup.sh.

---

## 6. RPO / RTO actuels (à connaître)

| Mesure | Valeur | Comment l'améliorer |
|---|---|---|
| RPO (perte max acceptable) | 24h | Réduire la fréquence cron (4h ou 1h) |
| RTO (durée restore) | ~ 5 min pour 1 GB | Pré-provisionner une instance hot-standby |

Sans réplication streaming Postgres (WAL streaming), **on perdra toujours les
écritures depuis le dernier dump quotidien**. C'est acceptable pour un POS
mono-shop, à reconsidérer pour multi-shops.
