package com.kidzpos.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler indépendant pour le traitement de sync_inbox.
 *
 * SÉPARÉ des schedulers push/pull pour 3 raisons :
 *  - cadences indépendantes (l'application métier peut être plus lente que le pull)
 *  - on/off indépendant (on peut vouloir pull-only en standby, sans application)
 *  - défense en profondeur : un crash du processor ne tue pas push/pull
 *
 * Tick toutes les 60s (configurable). Drain borné à 5 batches max par tick
 * (= 250 lignes avec batch=50 par défaut). Au-delà, on attend le prochain tick.
 *
 * @ConditionalOnProperty → bean créé uniquement si kidzpos.sync.inbox.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = "kidzpos.sync.inbox", name = "enabled", havingValue = "true")
public class SyncInboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncInboxScheduler.class);

    private static final int MAX_BATCHES_PER_TICK = 5;

    private final SyncInboxProcessor processor;

    public SyncInboxScheduler(SyncInboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${kidzpos.sync.inbox.interval-ms:60000}",
               initialDelayString = "${kidzpos.sync.inbox.initial-delay-ms:20000}")
    public void tick() {
        try {
            SyncInboxProcessor.Result r = processor.processAll(MAX_BATCHES_PER_TICK);
            if (r.applied() > 0 || r.quarantined() > 0 || r.errored() > 0) {
                log.info("[sync-inbox] tick complete: applied={} quarantined={} errored={}",
                        r.applied(), r.quarantined(), r.errored());
            }
        } catch (Exception e) {
            // Filet de sécurité — processor.processAll devrait ne JAMAIS throw,
            // mais on assure quand même : le scheduler doit survivre au tick.
            log.error("[sync-inbox] unexpected tick failure: {}", e.getMessage(), e);
        }
    }
}
