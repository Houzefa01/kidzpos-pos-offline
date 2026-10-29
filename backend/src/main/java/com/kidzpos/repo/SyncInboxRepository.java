package com.kidzpos.repo;

import com.kidzpos.domain.SyncInbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès à l'inbox des événements PULL-és depuis le central.
 *
 * - {@link JpaRepository#existsById(Object)} : utilisé par SyncPullService pour
 *   l'idempotence (skip si l'UUID est déjà connu localement).
 * - {@link #findTopByOrderByCreatedAtDesc()} : cursor du prochain pull
 *   ({@code since} = createdAt du dernier événement reçu).
 */
public interface SyncInboxRepository extends JpaRepository<SyncInbox, UUID> {

    /** Cursor du pull : retourne l'événement le plus récent stocké. */
    Optional<SyncInbox> findTopByOrderByCreatedAtDesc();

    /**
     * Batch d'événements pas encore appliqués au métier local, en ordre
     * chronologique (préserve la causalité naturelle des événements).
     * Sert {@link com.kidzpos.sync.SyncInboxProcessor#processBatch()}.
     */
    List<SyncInbox> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);

    // ─── Debug / monitoring helpers ──────────────────────────────────────────

    /** Pour {@code /api/debug/status} et dashboard ops. */
    long countByProcessedFalse();
}
