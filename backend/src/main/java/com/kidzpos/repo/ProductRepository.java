package com.kidzpos.repo;

import com.kidzpos.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findByStoreId(String storeId);
    Optional<Product> findByStoreIdAndSkuIgnoreCase(String storeId, String sku);

    /**
     * V19-audit — Lookup par ID strictement scoped par store.
     * Utilisé par {@code SaleController.doCheckout} et autres call-sites qui
     * doivent refuser de charger un produit cross-store, même connu en base.
     * Retourne empty si l'id n'existe pas OU appartient à un autre magasin.
     */
    Optional<Product> findByIdAndStoreId(String id, String storeId);

    /**
     * Décrémente le stock atomiquement uniquement si {@code stock >= qty}.
     * Retourne le nombre de lignes modifiées : 0 = stock insuffisant, 1 = OK.
     * Indispensable contre les checkouts concurrents (B6).
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.id = :id AND p.stock >= :qty")
    int decrementStockIfAvailable(@Param("id") String id, @Param("qty") int qty);

    /**
     * Soft-delete bypass : retrouve un produit même si deleted_at != null.
     * Utilisé par le refund pour restocker un produit retiré du catalogue.
     * Native query : @SQLRestriction de l'entité ne s'applique qu'aux requêtes JPQL.
     */
    @Query(value = "SELECT * FROM products WHERE id = :id", nativeQuery = true)
    Optional<Product> findByIdIncludingDeleted(@Param("id") String id);
}
