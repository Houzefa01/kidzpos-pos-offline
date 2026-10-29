-- V17 : tracking des tentatives sur sync_inbox pour borner les retries.
--
-- Objectif : éviter qu'un événement systématiquement en erreur (payload
-- invalide, handler buggy, contrainte JPA récalcitrante…) reste indéfiniment
-- dans sync_inbox et soit retenté à chaque tick scheduler. Au-delà d'un
-- seuil (MAX_RETRIES=5, hardcodé côté processor), l'événement est déplacé
-- en quarantine_events avec reason="max retries exceeded".
--
-- 2 colonnes additives :
--   - retry_count NOT NULL DEFAULT 0 : compteur d'échecs apply (n'inclut PAS
--     les apply réussis — ceux-ci marquent processed=true, la ligne sort du
--     SELECT et n'est plus vue). Bumpé en TX dédiée APRÈS chaque échec apply.
--   - last_attempt_at NULL : horodatage du dernier bump. NULL si jamais
--     attempté → diagnostic "ligne fraîche" vs "ligne stagnante".
--
-- Aucune contrainte ajoutée, aucun index : le scan se fait toujours via
-- idx_sync_inbox_processed_created (V15). retry_count est lu uniquement
-- pour les lignes déjà retournées par ce SELECT, donc pas d'index dédié.
--
-- Backward compatible : les lignes existantes prennent retry_count=0 +
-- last_attempt_at=NULL → traitées comme "jamais tentées" → bénéficient
-- du quota complet de 5 retries avant quarantine.

ALTER TABLE sync_inbox
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE sync_inbox
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP NULL;
