-- V3 : ajout de la colonne `currency` sur sales pour figer la devise au moment du checkout.
-- Idempotent : ignore si déjà présente. Default 'AR' pour rétro-compat sur les ventes existantes.
ALTER TABLE sales ADD COLUMN IF NOT EXISTS currency VARCHAR(10) NOT NULL DEFAULT 'AR';
