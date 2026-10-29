-- V8 : idempotence des mouvements de stock initiés client.
-- /api/stock/adjust et /api/stock/transfer reçoivent un clientMovementId UUID
-- stable côté front. Au replay (outbox), le même body est rejoué → unique
-- partiel empêche la double application.
--
-- NULL toléré : les mouvements internes serveur (SALE, REFUND générés par
-- SaleController) n'ont pas de clientMovementId et restent valides.

ALTER TABLE stock_movements
    ADD COLUMN IF NOT EXISTS client_movement_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_movements_client_id
    ON stock_movements (client_movement_id)
    WHERE client_movement_id IS NOT NULL;
