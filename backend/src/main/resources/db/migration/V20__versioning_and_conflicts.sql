-- V20 : versioning + détection de conflits pour la sync distribuée.
--
-- Stratégie globale (documentée) :
--   - Product : @Version JPA existe déjà depuis V11 → optimistic locking direct API.
--                Ajout updated_at pour la détection conflit côté inbox processor.
--   - Customer : nouveau @Version JPA + updated_at. Conflits API directs gérés
--                via OptimisticLockException → 412 (comme Product).
--   - Sale     : immutable une fois créée (refund = nouvelle ligne). updated_at
--                ajouté à des fins de traçabilité, jamais utilisé en update.
--   - Stock    : pas de conflit possible par construction — la table stock_movements
--                journalise les deltas, le stock courant peut être recalculé.
--
-- Toutes les colonnes ajoutées sont NULLABLE ou ont un DEFAULT compatible. Les
-- lignes pré-V20 restent valides (updated_at=NULL, version=0).

-- ─── Versioning ──────────────────────────────────────────────────────────────

-- Customer : ajout @Version (default 0 pour les lignes existantes)
ALTER TABLE customers ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- Timestamps "updated_at" sur les entités modifiables
ALTER TABLE customers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE products  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE sales     ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

-- ─── Journal des conflits ────────────────────────────────────────────────────
-- Table isolée : aucune FK vers les entités métier (préserve l'audit même si la
-- ligne d'origine est supprimée). Format identique à operation_log pour cohérence
-- opérationnelle (payload JSONB requêtable).

CREATE TABLE IF NOT EXISTS conflict_log (
    id                 UUID         PRIMARY KEY,
    entity_type        VARCHAR(64)  NOT NULL,    -- "Product" | "Customer" | "Stock" | ...
    entity_id          VARCHAR(64)  NOT NULL,
    store_id           VARCHAR(64),               -- d'où vient l'entité concernée
    conflict_type      VARCHAR(64)  NOT NULL,    -- "stale_update" | "negative_stock" | etc.
    local_version      INTEGER,                   -- snapshot pour forensics
    remote_version     INTEGER,
    local_updated_at   TIMESTAMP,
    remote_updated_at  TIMESTAMP,
    resolution         VARCHAR(32)  NOT NULL,    -- "skip_remote" | "accept_remote" | "clamp" | "logged_only"
    payload            JSONB,                     -- snapshot du payload distant (forensics)
    detected_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conflict_log_entity
    ON conflict_log (entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_conflict_log_detected
    ON conflict_log (detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_conflict_log_store
    ON conflict_log (store_id);

CREATE INDEX IF NOT EXISTS idx_conflict_log_type
    ON conflict_log (conflict_type);
