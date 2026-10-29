-- V14 : suivi des tentatives de push pour le retry à backoff côté local.
--
-- Deux colonnes NULLABLE ajoutées à `operation_log` :
--   - last_attempt_at : timestamp du dernier essai de push (NULL = jamais tenté)
--   - attempt_count   : nombre d'essais infructueux (NULL = jamais tenté, traité
--                       comme 0 par le code → READY pour le prochain tick)
--
-- Aucune contrainte ajoutée, aucune valeur par défaut côté SQL pour rester
-- 100 % rétrocompatible avec les lignes déjà présentes (qui resteront NULL).
-- Le code Java (SyncPushService) traite NULL comme "jamais tenté".
--
-- Aucun nouvel index : la requête de drain reste
--   `WHERE synced = false ORDER BY created_at ASC LIMIT n`
-- → l'index existant `idx_operation_log_synced_created` (V13) suffit.
-- La filtration éligibilité (backoff) est faite côté Java sur le batch chargé.

ALTER TABLE operation_log
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP NULL;

ALTER TABLE operation_log
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NULL;
