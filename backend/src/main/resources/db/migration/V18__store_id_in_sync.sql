-- V18 : isolation multi-magasin sur le pipeline sync.
--
-- Ajoute store_id sur les 3 tables sync. Les tables métier (products, sales,
-- stock_movements) en ont déjà depuis V1 — on ne les touche pas.
--
-- NULLABLE volontairement : préserve les lignes existantes (pré-V18) qui
-- n'avaient pas la notion. Le runtime tolère NULL comme "événement legacy
-- sans métadonnée de magasin" (log debug, traité normalement).
--
-- Index dédié sur chaque colonne pour servir :
--   - pull central filtré par storeId (WHERE store_id = ? AND created_at > ?)
--   - audit ops (combien d'événements par magasin ?)
--   - cross-store guard côté processor (lookup direct)

ALTER TABLE operation_log
    ADD COLUMN IF NOT EXISTS store_id VARCHAR(64);

ALTER TABLE sync_inbox
    ADD COLUMN IF NOT EXISTS store_id VARCHAR(64);

ALTER TABLE quarantine_events
    ADD COLUMN IF NOT EXISTS store_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_operation_log_store
    ON operation_log (store_id);

CREATE INDEX IF NOT EXISTS idx_sync_inbox_store
    ON sync_inbox (store_id);

CREATE INDEX IF NOT EXISTS idx_quarantine_events_store
    ON quarantine_events (store_id);
