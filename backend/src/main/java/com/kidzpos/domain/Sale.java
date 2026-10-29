package com.kidzpos.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "sales",
    uniqueConstraints = @UniqueConstraint(name = "uk_sale_store_seq", columnNames = {"storeId", "seq"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Sale {
    @Id private String id;
    @Column(nullable = false) private long seq;
    @Column(nullable = false) private String storeId;
    @Column(nullable = false) private String userId;
    @Column(nullable = false) private String userName;

    // B4 : LAZY + @EntityGraph sur les listings non-paginés (cf SaleRepository).
    // open-in-view: false ⇒ les items sont chargés explicitement via le fetch plan.
    // @BatchSize : sur les listings PAGINÉS (sans @EntityGraph, pour éviter la
    // pagination en mémoire HHH90003004), l'init des collections se fait par lot
    // de 100 (1 SELECT WHERE sale_id IN (…) pour 100 ventes au lieu de N requêtes).
    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 100)
    @JsonManagedReference
    @Builder.Default
    private List<SaleItem> items = new ArrayList<>();

    @Column(nullable = false) private double subtotal;
    @Column(nullable = false) private double discount;
    @Column(nullable = false) private double total;
    @Column(nullable = false) private Instant date;

    private String customerId;
    private String customerName;
    @Column(nullable = false) private int pointsEarned;
    @Column(nullable = false) private int pointsRedeemed;

    @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentMode paymentMode;
    private Double amountPaid;
    private Double change;
    private String refundedFrom;

    /**
     * V22 — UUID stable côté client pour l'idempotence du refund. Si fourni au
     * {@code POST /api/sales/refund} et déjà connu côté serveur, la requête
     * retourne le refund existant au lieu d'en créer un nouveau. Évite le
     * double-remboursement en cas de retry réseau ou de replay outbox.
     * NULL pour les lignes pré-V22 et pour les checkout (jamais alimenté côté
     * vente initiale).
     */
    @Column(name = "client_refund_id", length = 64, unique = true)
    private String clientRefundId;

    /** Devise affichée au client lors de la vente ("AR" ou "EUR"), figée au checkout.
     *  Les montants (subtotal, total, amountPaid…) sont stockés en Ariary canonique
     *  (cf V5) ; ce champ sert au rendu du reçu/historique avec la devise d'origine
     *  même si l'opérateur change la devise globale ultérieurement. */
    @Column(nullable = false, length = 10) @Builder.Default private String currency = "AR";

    /**
     * V20 — Horodatage (= date de création pour Sale, car les ventes sont
     * immutables : un refund crée une NOUVELLE ligne avec refundedFrom pointant
     * l'originale, jamais d'UPDATE). Conservé pour cohérence avec Product/Customer
     * et future traçabilité ops. NULL pour les lignes pré-V20.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}
