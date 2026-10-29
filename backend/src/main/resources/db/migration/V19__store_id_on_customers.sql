-- V19 : isolation par magasin sur la table customers.
--
-- Pourquoi : seule table métier persistée sans dimension store (les 3 autres —
-- products, sales, stock_movements — l'ont depuis V1). Conséquence sans cette
-- migration : un caissier de Magasin A pouvait voir/éditer les clients de
-- Magasin B en passant par les endpoints customers.
--
-- DÉCISION PRODUIT (documentée) : customer per-store, pas global.
-- Raisonnement :
--   - Le programme de fidélité (points) est intrinsèquement local
--   - L'historique (visits, total_spent) appartient au magasin où l'achat a eu lieu
--   - Un même téléphone client peut exister dans 2 magasins indépendamment
-- Réversible : si "customer global" devient un besoin, désactiver la garde
-- côté code suffit (ne pas dropper la colonne).
--
-- NULLABLE volontairement :
--   - Préserve les lignes existantes (pré-V19)
--   - Le code consommateur traite NULL comme "legacy" (visible cross-store
--     en lecture pour compat ascendante). Migration progressive possible
--     via le backfill manuel ci-dessous.
--
-- Index dédié : sert findByStoreId et la garde isInScope du processor.

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS store_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_customers_store
    ON customers (store_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- BACKFILL (À EXÉCUTER MANUELLEMENT — pas automatique)
-- ─────────────────────────────────────────────────────────────────────────────
-- Sur chaque local, après déploiement, taguer les clients legacy avec le
-- store_id du nœud. Exemple pour le serveur du magasin s1 :
--
--   UPDATE customers SET store_id = 's1' WHERE store_id IS NULL;
--
-- ⚠ NE JAMAIS exécuter ce backfill sur le central — il agrège plusieurs
-- magasins, l'attribution ne peut pas être déduite sans contexte métier.
-- ─────────────────────────────────────────────────────────────────────────────
