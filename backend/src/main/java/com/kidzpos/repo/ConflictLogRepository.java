package com.kidzpos.repo;

import com.kidzpos.domain.ConflictLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Accès au journal de conflits. Lectures destinées à un futur outillage ops
 * (listing par entité / par type / par store). Pour l'instant utilisé en
 * écriture uniquement par {@code ConflictLogService}.
 */
public interface ConflictLogRepository extends JpaRepository<ConflictLog, UUID> {

    /** Forensics : historique des conflits sur une entité donnée. */
    List<ConflictLog> findByEntityTypeAndEntityIdOrderByDetectedAtDesc(String entityType, String entityId);
}
