package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * @SQLRestriction filtre globalement les produits soft-deleted : tous les
 * findAll / findById / dérivés JPA ignorent les lignes avec deleted_at != null.
 * L'unique (store_id, sku) est porté par un index partiel V4 — voir migration.
 *
 * Pour les cas qui doivent voir aussi les supprimés (ex: refund d'une vente
 * d'un produit retiré), utiliser ProductRepository.findByIdIncludingDeleted.
 */
@Entity @Table(name = "products")
@SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {
    @Id private String id;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private double price;
    @Column(nullable = false) private int stock;
    @Column(nullable = false) private String storeId;
    private String category;
    @Column(nullable = false) private String sku;
    @Column(nullable = false) private Instant createdAt;

    @Column(name = "deleted_at") private Instant deletedAt;

    /** Optimistic locking : Hibernate incrémente à chaque flush, lève
     *  ObjectOptimisticLockingFailureException si la valeur attendue diverge.
     *  Exposé en ETag sur GET, attendu en If-Match au PUT. */
    @jakarta.persistence.Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    /**
     * V20 — Horodatage de la dernière modification (auto via {@link #touchUpdatedAt()}).
     * Utilisé par {@code ProductUpdatedHandler} pour détecter les events
     * obsolètes (local plus récent que remote) et journaliser le conflit.
     * NULL pour les lignes pré-V20 → traité comme "ancien" (l'event remote
     * est appliqué sans conflit, compat ascendante).
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * V20-fix sync (audit 2026-05) — {@code updatedAt} ne reflète QUE les éditions
     * CATALOGUE (nom/prix/sku/catégorie), pas les changements de stock.
     *
     * <p>Pourquoi : le LWW de {@code ProductUpdatedHandler} compare le {@code updatedAt}
     * local du central à celui (source) de l'événement. Si un {@code @PreUpdate}
     * bumpait {@code updatedAt=now()} à CHAQUE save (y compris les décréments de
     * stock matérialisés depuis un sale.checkout), le central se retrouvait avec un
     * {@code updatedAt} = horloge de matérialisation, postérieur au {@code updatedAt}
     * source d'un {@code product.updated} arrivé/traité plus tard → édition (nom/prix)
     * jugée "stale" et IGNORÉE. Régression gardée par
     * {@code FederatedOperationsE2ETest#lww_stock_change_does_not_pollute_catalog_timestamp_and_later_edit_applies}
     * (échoue si le {@code @PreUpdate} auto est réintroduit).
     *
     * <p>Désormais : pas de {@code @PreUpdate} auto. Le {@code @PrePersist} pose une
     * valeur initiale si absente (création). Les éditions catalogue posent
     * {@code updatedAt} EXPLICITEMENT — côté nœud via {@code ProductController.update},
     * côté sync via {@code ProductCreatedHandler}/{@code ProductUpdatedHandler} (qui
     * imposent l'horodatage SOURCE). Les saves de stock ne touchent plus
     * {@code updatedAt} → LWW correct quel que soit l'ordre d'arrivée des events.
     */
    @PrePersist
    void initUpdatedAt() {
        if (this.updatedAt == null) this.updatedAt = Instant.now();
    }
}
