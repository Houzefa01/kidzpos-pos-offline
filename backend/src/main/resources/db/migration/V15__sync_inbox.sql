-- V15 : inbox des opérations PULL-ées depuis le serveur central.
--
-- Table NOUVELLE et ISOLÉE — aucun lien FK, aucun trigger, aucun impact sur
-- les tables métier. Le contenu n'est appliqué à RIEN automatiquement :
-- c'est un journal de réception, processed=false par défaut. Une logique
-- d'application (future) marquera processed=true au cas par cas.
--
-- Schéma 1:1 avec operation_log (id, type, payload, created_at) plus le
-- flag processed local. La PK UUID permet l'idempotence côté local
-- (existsById avant insert).

CREATE TABLE IF NOT EXISTS sync_inbox (
    id          UUID         PRIMARY KEY,
    type        VARCHAR(64)  NOT NULL,
    payload     JSONB        NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    processed   BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Index composé : sert (1) la requête future d'application (WHERE processed=false
-- ORDER BY created_at) et (2) le cursor du pull (ORDER BY created_at DESC LIMIT 1
-- via scan DESC de l'index). Pas d'index séparé sur created_at seul.
CREATE INDEX IF NOT EXISTS idx_sync_inbox_processed_created
    ON sync_inbox (processed, created_at);
