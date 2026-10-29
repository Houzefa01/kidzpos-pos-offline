-- V12 : refresh tokens persistés pour rotation et révocation.
--
-- Stratégie sécurité :
--   - token_hash = SHA-256 du token en clair (le cookie porte le clair, jamais persisté)
--   - rotation : chaque refresh révoque l'ancien (revoked_at NOT NULL, replaced_by = nouveau)
--   - reuse detection : un token revoked_at NOT NULL présenté = signe de vol → revoke ALL pour l'user
--   - expires_at borne dure (30 jours par défaut)
--
-- Index :
--   - uk_refresh_token_hash : lookup O(log n) au /refresh
--   - idx_refresh_user      : revoke all par user (cas reuse-detection)
--   - idx_refresh_expires   : cleanup périodique (job optionnel)

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id           VARCHAR(64)  PRIMARY KEY,
    token_hash   VARCHAR(128) NOT NULL,
    user_id      VARCHAR(64)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issued_at    TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    revoked_at   TIMESTAMP,
    replaced_by  VARCHAR(64),
    user_agent   VARCHAR(255),
    ip           VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_token_hash ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_expires ON refresh_tokens (expires_at);
