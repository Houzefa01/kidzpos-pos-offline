package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity @Table(name = "customers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {
    @Id private String id;
    private String name;
    private String phone;
    private String email;
    @Column(nullable = false) private int points;
    @Column(nullable = false) private double totalSpent;
    @Column(nullable = false) private int visits;
    @Column(nullable = false) private Instant createdAt;

    /**
     * V19 — Magasin propriétaire du client. NULL = legacy pré-V19 (visible
     * cross-store en lecture pour compat ascendante). Posé automatiquement
     * par {@code CustomerController} via {@code NodeContext.storeId()} à la
     * création. Frontend ignoré sur ce champ (le serveur est source de vérité).
     */
    @Column(name = "store_id", length = 64)
    private String storeId;

    /**
     * V20 — Optimistic locking JPA (même pattern que Product depuis V11).
     * Incrémenté à chaque flush par Hibernate. Si concurrent update détectée,
     * lève {@code ObjectOptimisticLockingFailureException} → 412 côté API.
     * Default 0 (compat lignes pré-V20 backfillées automatiquement par la migration).
     */
    @jakarta.persistence.Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    /**
     * V20 — Horodatage de la dernière modification, mis à jour automatiquement
     * via {@link #touchUpdatedAt()}. Sert à la détection de conflits côté inbox
     * (LWW par timestamp). NULL = jamais modifié post-V20 → traité comme "ancien"
     * (tolérance legacy : l'event remote est appliqué sans détection de conflit).
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}
