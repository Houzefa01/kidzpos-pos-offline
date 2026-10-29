-- V4 : soft-delete sur products.
-- Le hard-delete cassait l'historique (sale_items.product_id n'a pas de FK, mais
-- une suppression libérait le SKU et permettait à un nouveau produit de réutiliser
-- l'id, ce qui réécrivait la sémantique des ventes passées).
--
-- L'unique (store_id, sku) devient un index partiel sur les lignes actives
-- uniquement, pour qu'un SKU puisse être recyclé après suppression logique.
ALTER TABLE products ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

ALTER TABLE products DROP CONSTRAINT IF EXISTS uk_product_store_sku;

CREATE UNIQUE INDEX IF NOT EXISTS uk_product_store_sku_active
    ON products (store_id, sku) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_products_deleted_at ON products(deleted_at);
