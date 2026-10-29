package com.kidzpos.sync;

import com.kidzpos.repo.OperationLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Purge périodique de {@code operation_log} pour éviter une croissance illimitée.
 *
 * Pertinent surtout côté CENTRAL où s'accumulent les events de tous les
 * magasins (1 ligne par mutation × N stores). À l'horizon 1 an × 5 stores ×
 * 200 mutations/jour = ~365 000 lignes — encore gérable mais l'indexation et
 * les pulls « depuis le début » deviennent lents.
 *
 * Stratégie :
 *  - Garde les events synced=true plus jeunes que retention-days (90 par défaut)
 *  - Garde TOUS les events synced=false, quel que soit leur âge (audit)
 *  - Purge par batch borné (1000 par défaut) → TX courte, anti-lock
 *  - Drain tant qu'il reste du backlog éligible
 *
 * Activation :
 *  - @ConditionalOnProperty kidzpos.sync.cleanup.enabled=true
 *  - Désactivé par défaut, activé via application-central.yml
 *  - Côté store : NE PAS activer (le store ne doit pas supprimer ses operation_log
 *    avant de les avoir synced ; le job ne le ferait pas — mais le bean ne sert
 *    à rien runtime, autant ne pas l'instancier).
 *
 * Fenêtre d'exécution : @Scheduled fixedDelay 1h par défaut. Pas de cron de nuit
 * volontaire — le job traite par petits batches et reste discret 24/7.
 */
@Component
@ConditionalOnProperty(prefix = "kidzpos.sync.cleanup", name = "enabled", havingValue = "true")
public class OperationLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OperationLogCleanupJob.class);

    /** Borne dure : ne JAMAIS retenir moins de 7 jours, même si retention-days mal configuré. */
    private static final int MIN_RETENTION_DAYS = 7;

    /** Borne dure : ne JAMAIS supprimer plus de 50k lignes en un seul tick. */
    private static final int MAX_BATCH_PER_TICK = 50_000;

    private final OperationLogRepository repo;
    private final TransactionTemplate tx;
    private final Counter deletedCounter;

    private final int retentionDays;
    private final int batchSize;

    public OperationLogCleanupJob(OperationLogRepository repo,
                                  PlatformTransactionManager txm,
                                  MeterRegistry registry,
                                  @Value("${kidzpos.sync.cleanup.retention-days:90}") int retentionDays,
                                  @Value("${kidzpos.sync.cleanup.batch-size:1000}") int batchSize) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txm);
        this.retentionDays = Math.max(MIN_RETENTION_DAYS, retentionDays);
        this.batchSize = Math.max(1, Math.min(batchSize, MAX_BATCH_PER_TICK));
        this.deletedCounter = Counter.builder("kidzpos.sync.cleanup.deleted")
                .description("Lignes operation_log supprimées par le job de purge "
                        + "(synced=true, age > retention-days)")
                .register(registry);

        if (this.retentionDays != retentionDays) {
            log.warn("[sync-cleanup] retention-days={} clampé à {} (min={})",
                    retentionDays, this.retentionDays, MIN_RETENTION_DAYS);
        }
        log.info("[sync-cleanup] enabled — retentionDays={} batchSize={} maxPerTick={}",
                this.retentionDays, this.batchSize, MAX_BATCH_PER_TICK);
    }

    /**
     * Tick : drain le backlog éligible en plusieurs petits batches, jusqu'à
     * MAX_BATCH_PER_TICK ou plus rien à supprimer.
     *
     * Initial delay 10 min pour ne pas s'activer juste après le boot
     * (priorité au warm-up : Flyway, hydratation cache, etc.).
     */
    @Scheduled(fixedDelayString = "${kidzpos.sync.cleanup.interval-ms:3600000}",
               initialDelayString = "${kidzpos.sync.cleanup.initial-delay-ms:600000}")
    public void cleanupTick() {
        try {
            int totalDeleted = drain();
            if (totalDeleted > 0) {
                deletedCounter.increment(totalDeleted);
                long remaining = repo.countBySyncedTrueAndCreatedAtLessThan(cutoff());
                log.info("[sync-cleanup] tick deleted={} remaining-eligible={}",
                        totalDeleted, remaining);
            } else {
                log.debug("[sync-cleanup] tick: nothing eligible");
            }
        } catch (Exception e) {
            // Best-effort : un échec ne crashe pas l'app. Prochain tick retentera.
            log.error("[sync-cleanup] tick failed: {}", e.getMessage(), e);
        }
    }

    /** Boucle interne : N batches successifs, borné par MAX_BATCH_PER_TICK. */
    int drain() {
        int total = 0;
        while (total < MAX_BATCH_PER_TICK) {
            int chunk = Math.min(batchSize, MAX_BATCH_PER_TICK - total);
            int deleted = deleteOneBatch(chunk);
            if (deleted == 0) break;        // plus rien d'éligible
            total += deleted;
            // Pas de sleep volontaire : DELETE bounded est déjà court. Si on observe
            // du lock contention en prod, ajouter un Thread.sleep(50) ici.
        }
        return total;
    }

    /** Un batch = une TX courte → DELETE atomique, pas de lock long. */
    private int deleteOneBatch(int limit) {
        Integer n = tx.execute(status -> repo.deleteSyncedOlderThan(cutoff(), limit));
        return n == null ? 0 : n;
    }

    private Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(retentionDays));
    }
}
