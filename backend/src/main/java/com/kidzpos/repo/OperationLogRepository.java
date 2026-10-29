package com.kidzpos.repo;

import com.kidzpos.domain.OperationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accès au journal d'opérations métier.
 *
 * Lectures utilisées à terme par le pull du serveur central
 * (cf {@link com.kidzpos.sync.SyncController}). Tant que le pull est un stub,
 * ces méthodes ne sont appelées par personne (no-op à l'exécution).
 */
public interface OperationLogRepository extends JpaRepository<OperationLog, UUID> {

    /** Pull incrémental : retourne le batch des opérations non encore synchronisées. */
    List<OperationLog> findBySyncedFalseOrderByCreatedAtAsc(Pageable pageable);

    /** Pull par date : utilisé pour rejouer une fenêtre temporelle si besoin. */
    List<OperationLog> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Instant since, Pageable pageable);

    /**
     * Pull incrémental (strict GT) : sert {@code GET /api/sync/pull?since=...}.
     *
     * Sémantique : le local a déjà vu l'événement à {@code created_at == since}
     * (c'est lui qui était au cursor du tour précédent) → on l'exclut.
     *
     * Limite connue : si deux événements partagent exactement le même
     * {@code created_at} et que la pagination les sépare, le second peut être
     * sauté. Mitigation : l'idempotence côté local évite les doublons en
     * lecture ; en cas de saut suspecté, le cursor peut être reculé manuellement.
     * En pratique TIMESTAMP postgres = précision microseconde → collision rare.
     */
    List<OperationLog> findByCreatedAtGreaterThanOrderByCreatedAtAsc(Instant since, Pageable pageable);

    // ─── V18 — Filtrage par store_id pour la sync multi-magasin ──────────────

    /** Pull initial filtré par magasin (cas {@code since==null && storeId != null}). */
    List<OperationLog> findByStoreIdOrderByCreatedAtAsc(String storeId, Pageable pageable);

    /** Pull incrémental filtré par magasin (cas nominal multi-store). */
    List<OperationLog> findByStoreIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(
            String storeId, Instant since, Pageable pageable);

    // ─── Debug / monitoring helpers ──────────────────────────────────────────

    /** Pour {@code /api/debug/status} et dashboard ops. */
    long countBySyncedFalse();

    /**
     * Plus ancien event en attente de sync. Utilisé par le gauge
     * {@code kidzpos.sync.lag_seconds} (cf BusinessMetrics) :
     *   lag = now() - oldestUnsynced.createdAt
     *
     * Retourne {@link java.util.Optional#empty()} si tout est syncé.
     * Spring Data dérive le SELECT avec ORDER BY + LIMIT 1.
     */
    java.util.Optional<OperationLog> findFirstBySyncedFalseOrderByCreatedAtAsc();

    /**
     * Purge bornée : supprime au plus {@code limit} lignes anciennes ET syncées.
     *
     * Sécurité :
     *   - synced=true UNIQUEMENT → on ne supprime jamais d'event en attente
     *   - created_at < cutoff → respect du TTL configuré
     *   - LIMIT en sous-requête → borne la taille de la TX (anti-LongRunningTX)
     *
     * Native query : Spring Data JPA ne supporte pas DELETE … LIMIT en JPQL.
     * IN (SELECT … LIMIT) garde le DELETE déterministe sur n'importe quel SGBD,
     * et l'optimizer Postgres collapse en seek index efficace.
     */
    @Modifying
    @Query(value = """
            DELETE FROM operation_log
            WHERE id IN (
                SELECT id FROM operation_log
                WHERE synced = true AND created_at < :cutoff
                ORDER BY created_at ASC
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteSyncedOlderThan(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Compteur exploité par le job de purge pour log "il restait N lignes éligibles". */
    long countBySyncedTrueAndCreatedAtLessThan(Instant cutoff);
}
