-- Schéma initial PostgreSQL pour KidzPOS.
-- IF NOT EXISTS partout : permet baseline-on-migrate sur une base existante (avant Flyway)
-- comme sur une base vide. Hibernate (ddl-auto: validate) confirme ensuite la cohérence
-- avec les @Entity.

CREATE TABLE IF NOT EXISTS stores (
    id        VARCHAR(64)  PRIMARY KEY,
    name      VARCHAR(255) NOT NULL,
    location  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    store_id      VARCHAR(64),
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS settings (
    id                    BIGINT PRIMARY KEY,
    tax_rate              DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    max_discount_percent  DOUBLE PRECISION NOT NULL DEFAULT 10.0,
    points_per_euro       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    euro_per_point        DOUBLE PRECISION NOT NULL DEFAULT 0.05,
    shop_name             VARCHAR(255)     NOT NULL DEFAULT 'KidzPOS',
    currency              VARCHAR(10)      NOT NULL DEFAULT 'AR'
);

-- Conserve la compat avec bases existantes : ajoute les colonnes si la table préexiste sans elles.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS tax_rate              DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS max_discount_percent  DOUBLE PRECISION NOT NULL DEFAULT 10.0;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS points_per_euro       DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS euro_per_point        DOUBLE PRECISION NOT NULL DEFAULT 0.05;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS shop_name             VARCHAR(255)     NOT NULL DEFAULT 'KidzPOS';
ALTER TABLE settings ADD COLUMN IF NOT EXISTS currency              VARCHAR(10)      NOT NULL DEFAULT 'AR';

CREATE TABLE IF NOT EXISTS products (
    id         VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    price      DOUBLE PRECISION NOT NULL,
    stock      INTEGER      NOT NULL,
    store_id   VARCHAR(64)  NOT NULL,
    category   VARCHAR(255),
    sku        VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT uk_product_store_sku UNIQUE (store_id, sku)
);
CREATE INDEX IF NOT EXISTS idx_products_store ON products(store_id);

CREATE TABLE IF NOT EXISTS customers (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255),
    phone       VARCHAR(64),
    email       VARCHAR(255),
    points      INTEGER          NOT NULL DEFAULT 0,
    total_spent DOUBLE PRECISION NOT NULL DEFAULT 0,
    visits      INTEGER          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP        NOT NULL
);

CREATE TABLE IF NOT EXISTS sales (
    id              VARCHAR(64)      PRIMARY KEY,
    seq             BIGINT           NOT NULL,
    store_id        VARCHAR(64)      NOT NULL,
    user_id         VARCHAR(64)      NOT NULL,
    user_name       VARCHAR(255)     NOT NULL,
    subtotal        DOUBLE PRECISION NOT NULL,
    tax             DOUBLE PRECISION NOT NULL,
    tax_rate        DOUBLE PRECISION NOT NULL,
    discount        DOUBLE PRECISION NOT NULL,
    total           DOUBLE PRECISION NOT NULL,
    date            TIMESTAMP        NOT NULL,
    customer_id     VARCHAR(64),
    customer_name   VARCHAR(255),
    points_earned   INTEGER          NOT NULL,
    points_redeemed INTEGER          NOT NULL,
    payment_mode    VARCHAR(32)      NOT NULL,
    amount_paid     DOUBLE PRECISION,
    change          DOUBLE PRECISION,
    refunded_from   VARCHAR(64),
    CONSTRAINT uk_sale_store_seq UNIQUE (store_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_sales_date ON sales(date DESC);
CREATE INDEX IF NOT EXISTS idx_sales_store ON sales(store_id);
CREATE INDEX IF NOT EXISTS idx_sales_refunded_from ON sales(refunded_from);

CREATE TABLE IF NOT EXISTS sale_items (
    id         BIGSERIAL PRIMARY KEY,
    sale_id    VARCHAR(64)      NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    product_id VARCHAR(64)      NOT NULL,
    name       VARCHAR(255)     NOT NULL,
    quantity   INTEGER          NOT NULL,
    price      DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sale_items_sale ON sale_items(sale_id);

CREATE TABLE IF NOT EXISTS stock_movements (
    id              BIGSERIAL    PRIMARY KEY,
    product_id      VARCHAR(64)  NOT NULL,
    store_id        VARCHAR(64)  NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    quantity        INTEGER      NOT NULL,
    date            TIMESTAMP    NOT NULL,
    user_id         VARCHAR(64),
    reason          VARCHAR(255),
    related_sale_id VARCHAR(64),
    target_store_id VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_stock_moves_date ON stock_movements(date DESC);
CREATE INDEX IF NOT EXISTS idx_stock_moves_store ON stock_movements(store_id);
