-- V10 : index composé pour findByStoreIdOrderByDateDesc.
-- Auparavant : deux index séparés (idx_sales_store, idx_sales_date) → Postgres
-- doit filter+sort. Le composé sert directement la requête sans tri additionnel.

CREATE INDEX IF NOT EXISTS idx_sales_store_date
    ON sales (store_id, date DESC);
