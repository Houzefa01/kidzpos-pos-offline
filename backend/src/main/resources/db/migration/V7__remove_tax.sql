-- V7 : Suppression complète de la TVA
-- ─────────────────────────────────────
-- Décision produit : pas de TVA dans KidzPOS (commerce Madagascar — pas de TVA appliquée).
-- Drop des colonnes tax / tax_rate sur sales et tax_rate sur settings.
-- Les valeurs précédentes sont perdues (action assumée par le produit).

ALTER TABLE sales    DROP COLUMN IF EXISTS tax;
ALTER TABLE sales    DROP COLUMN IF EXISTS tax_rate;
ALTER TABLE settings DROP COLUMN IF EXISTS tax_rate;
