package com.kidzpos.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler du pull central → local.
 *
 * Distinct de {@link SyncScheduler} (qui pilote le push) pour deux raisons :
 *   - cadences indépendantes (le pull peut être plus lent que le push)
 *   - on/off indépendants (on peut vouloir push only ou pull only)
 *
 * Tick toutes les 60s (configurable). Tente un drain borné à 5 batches max
 * par tick (= 500 events max avec batch=100 par défaut).
 *
 * Activation : @ConditionalOnProperty kidzpos.sync.pull.enabled=true.
 *              Si SyncPullService n'est pas créé (cf même condition), ce
 *              scheduler ne l'est pas non plus → aucun impact runtime.
 */
@Component
@ConditionalOnProperty(prefix = "kidzpos.sync.pull", name = "enabled", havingValue = "true")
public class SyncPullScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncPullScheduler.class);

    private static final int MAX_BATCHES_PER_TICK = 5;

    private final SyncPullService pull;

    public SyncPullScheduler(SyncPullService pull) {
        this.pull = pull;
    }

    @Scheduled(fixedDelayString = "${kidzpos.sync.pull.interval-ms:60000}",
               initialDelayString = "${kidzpos.sync.pull.initial-delay-ms:15000}")
    public void tick() {
        if (!pull.isConfigured()) {
            log.debug("[sync-pull] tick skipped: not configured");
            return;
        }
        int inserted = pull.pullAll(MAX_BATCHES_PER_TICK);
        if (inserted > 0) {
            log.info("[sync-pull] tick complete: {} new events stored", inserted);
        }
    }
}
