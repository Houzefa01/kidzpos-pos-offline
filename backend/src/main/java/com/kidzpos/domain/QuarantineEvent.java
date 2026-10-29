package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement de {@link SyncInbox} dont le type n'a aucun handler enregistré
 * dans le processor. Conservé pour audit / intervention manuelle ultérieure.
 *
 * Le champ {@code originalInboxId} pointe vers la ligne {@code sync_inbox}
 * d'origine (qui reste en base avec processed=true). UNIQUE en BDD pour
 * garantir l'idempotence (cf V16__quarantine_events.sql).
 *
 * Aucune logique automatique ne consomme cette table : c'est un dead-letter
 * queue. Une release ultérieure peut ajouter un handler pour l'un des types
 * et rejouer manuellement les lignes correspondantes.
 */
@Entity
@Table(name = "quarantine_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuarantineEvent {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "original_inbox_id", nullable = false, columnDefinition = "uuid")
    private UUID originalInboxId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Payload JSON intact (copié verbatim depuis sync_inbox.payload). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Motif court de quarantaine (ex: "no handler for type product.created"). */
    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** V18 — Magasin d'origine (copié depuis sync_inbox au moment de la quarantine). */
    @Column(name = "store_id", length = 64)
    private String storeId;
}
