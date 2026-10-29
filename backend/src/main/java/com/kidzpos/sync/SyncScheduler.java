package com.kidzpos.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler du push local → central.
 *
 * Tick toutes les 30s (configurable via kidzpos.sync.push.interval-ms).
 * Tente un drain borné (5 batches max = 100 opérations max par tick avec
 * la taille de batch par défaut). Au-delà, on attend le prochain tick.
 *
 * Activation : @ConditionalOnProperty kidzpos.sync.push.enabled=true.
 *              Si SyncPushService n'est pas créé (cf même condition), ce
 *              scheduler ne l'est pas non plus → aucun impact runtime.
 *
 * Pas de gestion d'erreur explicite : SyncPushService.pushOnce() avale
 * déjà les erreurs réseau et les retourne en `0` silencieux.
 */
@Component
@ConditionalOnProperty(prefix = "kidzpos.sync.push", name = "enabled", havingValue = "true")
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private static final int MAX_BATCHES_PER_TICK = 5;

    private final SyncPushService push;

    public SyncScheduler(SyncPushService push) {
        this.push = push;
    }

    /**
     * fixedDelayString → l'intervalle est lu à chaque cycle après la fin du
     * précédent. Garantit que deux ticks ne se chevauchent jamais, même si
     * un push prend > intervalle (cas pathologique d'un central très lent).
     */
    @Scheduled(fixedDelayString = "${kidzpos.sync.push.interval-ms:30000}",
               initialDelayString = "${kidzpos.sync.push.initial-delay-ms:10000}")
    public void tick() {
        if (!push.isConfigured()) {
            // Log debug uniquement — pas de pollution en prod si volontairement non configuré.
            log.debug("[sync-push] tick skipped: not configured");
            return;
        }
        int marked = push.pushAll(MAX_BATCHES_PER_TICK);
        if (marked > 0) {
            log.info("[sync-push] tick complete: {} operations synced", marked);
        }
    }
}
