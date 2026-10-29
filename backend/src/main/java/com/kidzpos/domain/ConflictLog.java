package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Journal des conflits de synchronisation détectés.
 *
 * Rempli par {@code ConflictLogService} (best-effort) lorsqu'un handler
 * d'inbox détecte qu'un event arrive "du passé" (local plus récent), qu'un
 * stock deviendrait négatif, ou tout autre cas anormal.
 *
 * Pure journalisation : aucune logique métier ne consomme cette table. Sert :
 *   - aux ops (audit "combien de conflits sur Product X ?")
 *   - aux développeurs (post-mortem sur incident sync)
 *   - aux clients (transparence sur ce qui a été appliqué vs. rejeté)
 */
@Entity
@Table(name = "conflict_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConflictLog {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    /** "Product", "Customer", "Stock" — chaîne libre identifiant le domaine. */
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    /** Magasin de l'entité concernée (NULL si non scoped, ex: legacy). */
    @Column(name = "store_id", length = 64)
    private String storeId;

    /** "stale_update", "negative_stock", "version_mismatch", etc. */
    @Column(name = "conflict_type", nullable = false, length = 64)
    private String conflictType;

    @Column(name = "local_version")
    private Integer localVersion;

    @Column(name = "remote_version")
    private Integer remoteVersion;

    @Column(name = "local_updated_at")
    private Instant localUpdatedAt;

    @Column(name = "remote_updated_at")
    private Instant remoteUpdatedAt;

    /** "skip_remote" | "accept_remote" | "clamp" | "logged_only". */
    @Column(nullable = false, length = 32)
    private String resolution;

    /** Snapshot JSON du payload distant pour forensics ultérieure. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
