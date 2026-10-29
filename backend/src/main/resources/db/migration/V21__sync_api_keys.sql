-- V21 : Clé API par magasin pour /api/sync/**
--
-- Remplace la clé partagée `KIDZPOS_SYNC_INBOUND_API_KEY` (single secret pour
-- tous les stores) par une clé hashée par store. Bénéfices :
--   - Révocation d'une clé compromise sans impacter les autres magasins
--   - Audit : on sait quel store a push quoi (et quand : last_used_at)
--   - Cross-validation : un store ne peut push QUE ses propres operations
--
-- Backward compat : la clé partagée historique reste acceptée tant que la
-- table est vide. Migration progressive : générer les clés par store via
-- POST /api/sync/keys (admin), déployer les stores avec leur nouvelle clé,
-- supprimer KIDZPOS_SYNC_INBOUND_API_KEY de l'env du central.

CREATE TABLE IF NOT EXISTS sync_api_keys (
    id            UUID         PRIMARY KEY,
    store_id      VARCHAR(64)  NOT NULL,
    -- SHA-256 hex (64 chars). Le secret en clair n'est JAMAIS persisté ;
    -- il est retourné une seule fois à la création puis perdu.
    key_hash      VARCHAR(128) NOT NULL,
    label         VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMP,
    last_used_at  TIMESTAMP
);

-- Index pour le lookup au filter (chemin chaud : 1 hit / requête sync).
-- WHERE revoked_at IS NULL → l'optimizer ignore les clés révoquées au plan.
CREATE INDEX IF NOT EXISTS idx_sync_api_keys_hash_active
    ON sync_api_keys (key_hash)
    WHERE revoked_at IS NULL;

-- Listing admin par store + dédup par store actif.
CREATE INDEX IF NOT EXISTS idx_sync_api_keys_store_active
    ON sync_api_keys (store_id)
    WHERE revoked_at IS NULL;
