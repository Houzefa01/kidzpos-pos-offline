# Runbook — PostgreSQL down

**Alerte associée** : `DBDown` (Prometheus) — `pg_up == 0` pendant 1 min.

**Sévérité** : P0 (critique, le backend ne peut plus rien faire).

**Impact business** : le backend va cascade-fail dans la minute (HikariCP
épuisé). Les caisses passent en offline, leurs outboxes accumulent les ventes.
Aucune mutation n'est persistée.

---

## 1. Diagnostic en 3 commandes

```bash
# A. Le service postgres tourne ?
systemctl status postgresql 2>/dev/null || systemctl status postgresql@*-main
ps aux | grep -E "[p]ostgres:.*writer" | head -3

# B. Tentative de connexion directe
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
  -c "SELECT version(), pg_is_in_recovery();" 2>&1 | head -5

# C. Espace disque DB
df -h "$(sudo -u postgres psql -tAc 'SHOW data_directory' 2>/dev/null || echo /var/lib/postgresql)"
```

---

## 2. Cas A — Service postgres arrêté

### Causes typiques
- Reboot machine sans `enable` du service.
- OOMKill (compétition mémoire avec backend / autres services).
- `pg_ctl stop` lancé manuellement par erreur.

### Recovery
```bash
# 1. Démarrer
sudo systemctl start postgresql
# Ou si installation manuelle :
sudo -u postgres pg_ctl -D /var/lib/postgresql/15/main start

# 2. Activer le boot automatique pour éviter récidive
sudo systemctl enable postgresql

# 3. Le backend reconnecte automatiquement via HikariCP (~30s).
sleep 30 && ./ops/scripts/healthcheck.sh
```

---

## 3. Cas B — Postgres tourne mais refuse les connexions

### Symptômes
- `psql` retourne `FATAL: too many connections` ou `connection refused`.
- Logs backend : `HikariPool-1 - Connection is not available`.

### Investigation
```bash
# Connexions actives
sudo -u postgres psql -c "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"

# Backend a-t-il leaké des connexions ?
sudo -u postgres psql -c "SELECT pid, usename, application_name, state, query_start, state_change
  FROM pg_stat_activity WHERE state = 'idle in transaction' AND state_change < now() - interval '5 min';"
```

### Recovery
```bash
# 1. Killer les "idle in transaction" stales (libère les slots)
sudo -u postgres psql -c "SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity WHERE state = 'idle in transaction'
  AND state_change < now() - interval '10 min';"

# 2. Si saturation chronique : augmenter max_connections
sudo -u postgres psql -c "ALTER SYSTEM SET max_connections = 200;"
sudo systemctl restart postgresql
```

---

## 4. Cas C — Disque plein

### Symptômes
- `df -h` montre 100% sur la partition data.
- Logs postgres : `could not extend file ... No space left on device`.

### Recovery immédiate
```bash
# 1. Libérer 1-2 Go en supprimant les WAL archivés / logs anciens
sudo find /var/lib/postgresql/*/main/pg_wal -name "*.backup" -mtime +7 -delete
sudo find /var/log -name "*.log" -mtime +14 -delete

# 2. Si l'app génère beaucoup de WAL (par ex. bulk import) : checkpoint
sudo -u postgres psql -c "CHECKPOINT;"

# 3. Identifier les plus grosses tables pour audit
sudo -u postgres psql -d $DB_NAME -c "
  SELECT schemaname, relname, pg_size_pretty(pg_total_relation_size(relid)) AS size
  FROM pg_catalog.pg_statio_user_tables
  ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;"
```

### Long terme
- Activer la rotation/purge `operation_log` (déjà en place, cf
  `OperationLogCleanupJob` — TTL 90j configurable via
  `KIDZPOS_OPLOG_RETENTION_DAYS`).
- Activer pgBadger ou auto-vacuum aggressif.

---

## 5. Cas D — Corruption / data files

### Symptômes
- `psql` retourne `invalid page header` ou `could not read block`.
- Logs postgres : `PANIC` / `corrupted page`.

### Recovery
**STOP** : ne PAS écrire dans la DB tant que la corruption n'est pas
diagnostiquée. Passer en mode read-only :

```bash
sudo -u postgres psql -c "ALTER SYSTEM SET default_transaction_read_only = on;"
sudo systemctl reload postgresql
```

Puis suivre `ops/backup/restore.md §1` (restore full DB depuis le dernier
dump valide). RTO ~5 min pour 1 Go.

---

## 6. Communication

Pendant l'incident :
- "Les ventes continuent côté caisse (offline mode), elles seront enregistrées
  dès la reprise de la DB. Ne PAS redémarrer les caisses."
- Si downtime > 15 min : prévenir le manager.

Après résolution :
- Vérifier que les outboxes des caisses se vident (Grafana → Business Sync).
- Comparer count des ventes vs heures d'activité.

---

## 7. Post-mortem (si downtime > 30 min OU corruption)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-db-down.md` :
- Cause racine (OOM, disk, corruption, config)
- Combien de ventes "à risque" pendant l'incident
- Actions correctives (alerte disque plus tôt, backup off-site, etc.)
