package com.kidzpos.observability;

import com.kidzpos.repo.OperationLogRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauge {@code kidzpos.sync.lag_seconds} : âge (en secondes) du plus vieux
 * event dans {@code operation_log} qui n'a pas encore été syncé.
 *
 * - Côté STORE (profil local) : mesure le retard de push vers le central.
 *   lag > 5 min = sync défaillante → alerte ops.
 * - Côté CENTRAL : toujours 0 en régime nominal (les events arrivés du push
 *   sont marqués synced=true à l'insertion, cf SyncController.push). Un lag
 *   non nul ici trahirait une corruption manuelle ou un bug.
 *
 * Implémentation : un @Scheduled rafraîchit un AtomicLong toutes les 30s
 * (1 SELECT FIRST 1, index-friendly). Le scrape Prometheus lit juste ce
 * volatile → zéro latence DB sur la route /actuator/prometheus.
 */
@Component
public class SyncLagGauge {

    private static final Logger log = LoggerFactory.getLogger(SyncLagGauge.class);

    /** Lag courant en secondes. 0 = tout est syncé. */
    private final AtomicLong lagSeconds = new AtomicLong(0L);

    private final OperationLogRepository repo;

    public SyncLagGauge(MeterRegistry registry, OperationLogRepository repo) {
        this.repo = repo;
        Gauge.builder("kidzpos.sync.lag_seconds", lagSeconds, AtomicLong::doubleValue)
                .description("Age (s) du plus vieux operation_log avec synced=false. "
                        + "0 = aucun retard de sync.")
                .baseUnit("seconds")
                .register(registry);
    }

    @PostConstruct
    void primeOnStartup() {
        // Premier refresh immédiat — évite que le gauge reste à 0 jusqu'au
        // premier tick scheduled (30s après le boot).
        refresh();
    }

    /**
     * Refresh périodique. fixedDelay=30s : si le SELECT prend plus de 30s
     * (DB en surcharge), on n'accumule pas les ticks (sécurité).
     *
     * Idle CPU : 1 SELECT léger toutes les 30s, négligeable.
     */
    @Scheduled(fixedDelayString = "${kidzpos.sync.lag-gauge.interval-ms:30000}",
               initialDelayString = "${kidzpos.sync.lag-gauge.initial-delay-ms:5000}")
    void refresh() {
        try {
            var oldest = repo.findFirstBySyncedFalseOrderByCreatedAtAsc();
            if (oldest.isEmpty()) {
                lagSeconds.set(0L);
                return;
            }
            long elapsed = Math.max(0L, Instant.now().getEpochSecond()
                    - oldest.get().getCreatedAt().getEpochSecond());
            lagSeconds.set(elapsed);
        } catch (Exception e) {
            // Best-effort : on log mais on n'écrase pas la valeur précédente.
            // Un échec DB transitoire ne doit pas remettre le lag à 0 (faux signal "OK").
            log.warn("[sync-lag-gauge] refresh failed (keeping previous value {}s): {}",
                    lagSeconds.get(), e.getMessage());
        }
    }
}
