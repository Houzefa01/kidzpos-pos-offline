-- V11 : optimistic locking sur products.
-- @Version JPA → un INT incrémenté à chaque save. Le client lit la version courante
-- (via ETag), la renvoie en If-Match au PUT ; conflit (concurrence) = HTTP 412.
--
-- Default 0 sur les lignes existantes pour ne pas casser les écritures en cours.

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;
