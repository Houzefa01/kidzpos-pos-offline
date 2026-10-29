-- V22 — Idempotence du refund via clientRefundId
--
-- Avant cette migration, un POST /api/sales/refund était un INSERT pur :
-- si le client retentait (réseau perdu, retry outbox, double-clic), un
-- DEUXIÈME refund était créé pour la MÊME vente, créant un double crédit
-- au comptable et un double restock.
--
-- Symétrique à clientSaleId sur le checkout : le client génère un UUID stable
-- côté frontend, et le backend déduplique sur (clientRefundId).
--
-- Stratégie :
-- 1. Colonne nullable (lignes legacy sans clientRefundId restent valides).
-- 2. UNIQUE partiel WHERE NOT NULL (deux refunds legacy NULL n'entrent pas en
--    conflit, mais aucun doublon n'est possible une fois clientRefundId fourni).
-- 3. Index pour le lookup d'idempotence.

ALTER TABLE sales
    ADD COLUMN IF NOT EXISTS client_refund_id VARCHAR(64);

-- Index unique partiel : seuls les refunds avec un clientRefundId sont uniques.
-- Idempotent (IF NOT EXISTS) — replay safe.
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_client_refund_id
    ON sales (client_refund_id)
    WHERE client_refund_id IS NOT NULL;

COMMENT ON COLUMN sales.client_refund_id IS
    'V22 — UUID stable côté client pour idempotence du refund. NULL toléré pour les lignes legacy ; tout NEW refund DOIT le fournir.';
