-- V13 : journal d'opérations métier pour préparer la synchronisation local ↔ central.
--
-- Nouvelle table ISOLÉE — aucune table existante n'est modifiée.
-- Chaque mutation métier (vente, refund, ajustement stock, transfert) écrit
-- une ligne ici en best-effort (cf OperationLogService). La colonne `synced`
-- pilotera la future synchro vers le serveur central :
--   - false : pas encore envoyé au central
--   - true  : ACK reçu du central (mis à jour par /api/sync/push, ETAPE 4+)
--
-- `payload` en JSONB : indexable, requêtable, et compressé nativement.
-- `id` UUID généré côté Java (UUID.randomUUID) — pas besoin d'extension Postgres.
--
-- Index :
--   - (synced, created_at) sert le pull incrémental : "next batch where synced=false order by created_at"
--   - (type) sert le filtrage par type d'opération côté audit / debug

CREATE TABLE IF NOT EXISTS operation_log (
    id          UUID         PRIMARY KEY,
    type        VARCHAR(64)  NOT NULL,
    payload     JSONB        NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    synced      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_operation_log_synced_created
    ON operation_log (synced, created_at);

CREATE INDEX IF NOT EXISTS idx_operation_log_type
    ON operation_log (type);
