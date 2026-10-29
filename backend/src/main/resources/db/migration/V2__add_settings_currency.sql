-- V2 : rattrape la dérive de schéma post-baseline.
-- La colonne `currency` a été ajoutée à l'entité Settings après la baseline Flyway,
-- donc absente des bases déjà initialisées par Hibernate ddl-auto.
-- Idempotent : ne fait rien si la colonne existe déjà.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS currency VARCHAR(10) NOT NULL DEFAULT 'AR';
