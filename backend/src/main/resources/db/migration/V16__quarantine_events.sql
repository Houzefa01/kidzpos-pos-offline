-- V16 : quarantine des événements sync_inbox sans handler enregistré.
--
-- Pourquoi : un type d'événement non whitelisté restait à processed=false
-- indéfiniment dans sync_inbox, polluant les logs (WARN à chaque tick) et
-- masquant les vraies erreurs (payload invalide). Désormais : extrait dans
-- une table dédiée, sync_inbox marqué processed=true → flux propre.
--
-- Nouvelle table ISOLÉE. Aucune FK vers sync_inbox : on veut que la trace
-- de quarantaine survive à un nettoyage manuel éventuel de sync_inbox.
--
-- IDEMPOTENCE niveau DB : UNIQUE sur original_inbox_id empêche d'insérer
-- deux fois la même quarantine, même en cas de tick double (impossible
-- avec fixedDelay mais défense en profondeur).

CREATE TABLE IF NOT EXISTS quarantine_events (
    id                UUID         PRIMARY KEY,
    original_inbox_id UUID         NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    payload           JSONB        NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);

-- Garantit l'unicité d'une mise en quarantaine par événement inbox.
CREATE UNIQUE INDEX IF NOT EXISTS uk_quarantine_events_original
    ON quarantine_events (original_inbox_id);

-- Diagnostic : combien d'événements par type rejetés ? (requête ops/support)
CREATE INDEX IF NOT EXISTS idx_quarantine_events_type
    ON quarantine_events (event_type);
