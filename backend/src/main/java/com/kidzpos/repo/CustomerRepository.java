package com.kidzpos.repo;

import com.kidzpos.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Customer repository.
 *
 * V19 : on conserve les méthodes héritées de {@link JpaRepository} (findAll,
 * findById, save…) car elles servent encore au profil central (qui agrège) et
 * aux migrations/backfill. Les controllers métier doivent passer par les
 * variantes {@code *AndStoreIdOrLegacy} qui appliquent l'isolation.
 *
 * La clause {@code OR storeId IS NULL} préserve la rétrocompat avec les
 * clients créés avant V19 (storeId restera NULL tant qu'un backfill manuel
 * ne sera pas exécuté — cf script en commentaire dans la migration).
 */
public interface CustomerRepository extends JpaRepository<Customer, String> {

    /**
     * Récupère un client SSI :
     *  - son store_id matche le store appelant (cas nominal), OU
     *  - son store_id est NULL (legacy pré-V19, tolérance)
     *
     * Retour empty → le caller renvoie 404 (sans révéler si la ligne existe
     * dans un autre magasin).
     */
    @Query("SELECT c FROM Customer c WHERE c.id = :id " +
           "AND (c.storeId = :storeId OR c.storeId IS NULL)")
    Optional<Customer> findByIdAndStoreIdOrLegacy(@Param("id") String id,
                                                  @Param("storeId") String storeId);

    /**
     * Listing scoped (avec compat legacy). Ordonné par createdAt desc pour
     * cohérence avec l'UI (clients récents en haut).
     */
    @Query("SELECT c FROM Customer c WHERE c.storeId = :storeId OR c.storeId IS NULL " +
           "ORDER BY c.createdAt DESC")
    List<Customer> findAllByStoreIdOrLegacy(@Param("storeId") String storeId);
}
