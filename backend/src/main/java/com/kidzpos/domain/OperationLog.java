package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Journal d'opérations métier — préparation à la synchronisation local ↔ central.
 *
 * Chaque mutation métier (ETAPE 3) écrit ici en best-effort via
 * {@link com.kidzpos.sync.OperationLogService}. La colonne {@code synced}
 * est posée à {@code false} à l'écriture ; la future couche de synchro
 * (ETAPE 4+) la passera à {@code true} après ACK du serveur central.
 *
 * Le payload est stocké en JSONB Postgres (cf migration V13) — typé String
 * côté Java avec {@link JdbcTypeCode}({@link SqlTypes#JSON}) ; la sérialisation
 * est faite par Jackson dans le service avant insertion.
 */
@Entity
@Table(name = "operation_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationLog {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String type;

    /** Sérialisation JSON faite côté service ; Hibernate écrit la String en JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean synced;

    // ─── V14 — Suivi des tentatives (nullable, additif) ──────────────────────
    // Posés UNIQUEMENT par SyncPushService (pas par OperationLogService.record).
    // NULL = jamais tenté → traité comme attempt_count=0 / lastAttemptAt=jamais
    // → READY pour le prochain tick. Permet la coexistence avec les lignes
    // créées avant V14 sans aucune migration de données.

    /** Horodatage du dernier essai de push. NULL = jamais tenté. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Nombre d'essais infructueux. NULL = jamais tenté (traité comme 0). */
    @Column(name = "attempt_count")
    private Integer attemptCount;

    /** V18 — Magasin d'origine de l'opération. NULL = legacy / non-store-scoped. */
    @Column(name = "store_id", length = 64)
    private String storeId;
}
