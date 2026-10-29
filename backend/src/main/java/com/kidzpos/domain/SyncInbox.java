package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbox des événements PULL-és depuis le serveur central.
 *
 * Symétrique de {@link OperationLog} (côté émission), avec un flag
 * {@code processed} pour identifier ce qui n'a pas encore été appliqué
 * localement (logique métier — HORS scope de cette PR).
 *
 * Aucune logique automatique ne consomme cette table : c'est un journal de
 * réception pur. Une future itération introduira un dispatcher qui mappera
 * {@code type} vers un handler métier et basculera {@code processed=true}.
 */
@Entity
@Table(name = "sync_inbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SyncInbox {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String type;

    /** Sérialisation JSON côté émetteur ; Hibernate écrit la String en JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Horodatage d'origine côté émetteur (NON pas l'heure de pull). */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** false par défaut : aucun handler métier n'a encore consommé l'événement. */
    @Column(nullable = false)
    private boolean processed;

    // ─── V17 — Tracking des tentatives (anti-retry-infini) ───────────────────
    // Posés UNIQUEMENT par SyncInboxProcessor après un échec d'apply
    // (jamais touchés sur succès — la ligne est marquée processed=true et
    // n'est plus relue). Backward compatible : lignes V15/V16 démarrent à 0/null.

    /** Nombre cumulé d'échecs d'application. 0 = jamais tenté (ou jamais en erreur). */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /** Horodatage du dernier bump retry_count. NULL = jamais tenté. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** V18 — Magasin d'origine de l'événement. NULL = legacy / non-store-scoped. */
    @Column(name = "store_id", length = 64)
    private String storeId;
}
