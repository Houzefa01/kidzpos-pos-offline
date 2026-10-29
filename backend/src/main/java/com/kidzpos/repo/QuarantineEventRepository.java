package com.kidzpos.repo;

import com.kidzpos.domain.QuarantineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Accès au dead-letter queue des événements sync_inbox sans handler.
 *
 * {@link #existsByOriginalInboxId(UUID)} : garde d'idempotence au niveau
 * applicatif (en plus de la UNIQUE en BDD).
 */
public interface QuarantineEventRepository extends JpaRepository<QuarantineEvent, UUID> {

    boolean existsByOriginalInboxId(UUID originalInboxId);

    /**
     * Listing admin avec filtre optionnel sur event_type + offset/limit arbitraires.
     *
     * Native query (Postgres) plutôt que JPQL pour exposer un OFFSET/LIMIT
     * direct. Plus naturel pour un endpoint paginé "offset" classique que
     * d'imposer une pagination par "page" Spring (où offset doit être multiple
     * de size). Pas de gain perf significatif sur une table de quarantaine
     * (volumes faibles), c'est purement un choix d'ergonomie.
     *
     * type NULL → retourne TOUS les événements. Sinon → filtre exact (case-sensitive).
     */
    @Query(value =
            "SELECT * FROM quarantine_events " +
            "WHERE (:type IS NULL OR event_type = :type) " +
            "AND (:storeId IS NULL OR store_id = :storeId) " +
            "ORDER BY created_at DESC " +
            "LIMIT :lim OFFSET :off",
            nativeQuery = true)
    List<QuarantineEvent> findPage(@Param("type") String type,
                                   @Param("storeId") String storeId,
                                   @Param("lim") int limit,
                                   @Param("off") int offset);

    /** Compte total (pour la réponse paginée). Même filtre que {@link #findPage}. */
    @Query(value =
            "SELECT COUNT(*) FROM quarantine_events " +
            "WHERE (:type IS NULL OR event_type = :type) " +
            "AND (:storeId IS NULL OR store_id = :storeId)",
            nativeQuery = true)
    long countByType(@Param("type") String type, @Param("storeId") String storeId);
}
