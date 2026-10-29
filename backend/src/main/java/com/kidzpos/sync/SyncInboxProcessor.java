package com.kidzpos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.QuarantineEvent;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.events.EventBus;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.QuarantineEventRepository;
import com.kidzpos.repo.SyncInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applique les événements de {@code sync_inbox} sur l'état métier local.
 *
 * Pipeline d'un batch :
 *  1. Charger N lignes WHERE processed=false ORDER BY created_at ASC.
 *  2. Pour chaque ligne :
 *     a. type sans handler whitelisté → WARN, on skip (ligne reste processed=false).
 *     b. handler trouvé → TX dédiée : parse payload + handler.apply + mark processed=true.
 *        - succès → commit, ligne ne sera plus retentée.
 *        - exception → rollback, ligne reste processed=false (sera retentée).
 *  3. Retourner un compteur (applied / skipped / errored).
 *
 * Garanties :
 *  - Idempotence forte : apply + mark dans la MÊME transaction → at-most-once
 *    par tick. Combiné aux handlers eux-mêmes idempotents (checks existsById…),
 *    on tolère un redémarrage en plein traitement.
 *  - Tolérance aux erreurs : une ligne errored ne bloque pas le batch.
 *  - Bornage : batch_size capé (anti-DoS interne).
 *
 * Activation : @ConditionalOnProperty kidzpos.sync.inbox.enabled=true.
 * Sans cette propriété, le bean n'est PAS créé → ZÉRO impact runtime.
 */
@Service
@ConditionalOnProperty(prefix = "kidzpos.sync.inbox", name = "enabled", havingValue = "true")
public class SyncInboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(SyncInboxProcessor.class);

    /** V17 — Au-delà, la ligne est déplacée en quarantine_events (max retries). */
    private static final int MAX_RETRIES = 5;
    /** V17 — En-deçà, on log debug+error normalement ; au-delà, on ajoute un WARN. */
    private static final int RETRY_WARN_THRESHOLD = 3;

    private final SyncInboxRepository inbox;
    private final QuarantineEventRepository quarantineRepo;
    private final ObjectMapper mapper;
    private final NodeContext nodeContext;
    private final EventBus bus;
    private final BusinessMetrics metrics;
    private final int batchSize;
    private final Map<String, InboxHandler> handlersByType;
    private final TransactionTemplate tx;

    public SyncInboxProcessor(SyncInboxRepository inbox,
                              QuarantineEventRepository quarantineRepo,
                              ObjectMapper mapper,
                              NodeContext nodeContext,
                              EventBus bus,
                              BusinessMetrics metrics,
                              List<InboxHandler> handlers,
                              PlatformTransactionManager txm,
                              @Value("${kidzpos.sync.inbox.batch-size:50}") int batchSize) {
        this.inbox = inbox;
        this.quarantineRepo = quarantineRepo;
        this.mapper = mapper;
        this.bus = bus;
        this.metrics = metrics;
        this.nodeContext = nodeContext;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.handlersByType = new HashMap<>();
        for (InboxHandler h : handlers) {
            InboxHandler previous = this.handlersByType.put(h.type(), h);
            if (previous != null) {
                log.warn("[sync-inbox] duplicate handler for type '{}' — keeping {} over {}",
                        h.type(), h.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        this.tx = new TransactionTemplate(txm);
        log.info("[sync-inbox] processor ready — whitelist={} batchSize={} nodeStore={}",
                this.handlersByType.keySet(), this.batchSize,
                nodeContext.storeId() == null ? "<none>" : nodeContext.storeId());
    }

    /** Pour les tests / inspection. */
    public java.util.Set<String> whitelist() {
        return java.util.Collections.unmodifiableSet(handlersByType.keySet());
    }

    public Result processBatch() {
        List<SyncInbox> rows = inbox.findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(0, batchSize));
        if (rows.isEmpty()) {
            log.debug("[sync-inbox] no unprocessed events");
            return new Result(0, 0, 0, 0);
        }

        int applied = 0;
        int quarantined = 0;    // type inconnu → déplacé vers quarantine_events
        int errored = 0;        // exception en handler ou parse (ligne préservée processed=false)

        for (SyncInbox row : rows) {
            // V22 — Full mesh : la garde cross-store V18 est désactivée. Chaque
            // nœud peut désormais matérialiser des events de TOUS les magasins,
            // pour que la DB locale reflète l'état complet du système (admin
            // omnipotent). L'isolation reste enforcée par l'API (enforceStoreScope :
            // EMPLOYEE voit son store, ADMIN voit tout).
            // Si une vraie isolation stricte est nécessaire (futur), réactiver ici
            // ou via un flag de config kidzpos.sync.cross-store-isolation.

            // V17 — Garde anti-retry-infini : check AVANT toute tentative.
            // Si une ligne a échoué > MAX_RETRIES fois, on la sort du flux par
            // quarantaine au lieu de boucler à chaque tick.
            if (row.getRetryCount() > MAX_RETRIES) {
                try {
                    quarantineOne(row, "max retries exceeded (count=" + row.getRetryCount() + ")");
                    quarantined++;
                    metrics.incInboxQuarantined(row.getType());
                    log.error("[sync-inbox] quarantined id={} type={} reason=max-retries-exceeded count={}",
                            row.getId(), row.getType(), row.getRetryCount());
                } catch (Exception e) {
                    log.error("[sync-inbox] max-retries quarantine FAILED for id={} type='{}': {}",
                            row.getId(), row.getType(), e.getMessage());
                    errored++;
                }
                continue;
            }

            InboxHandler handler = handlersByType.get(row.getType());
            if (handler == null) {
                // Type non whitelisté → on déplace en quarantaine (TX dédiée) puis on
                // marque processed=true. La ligne sync_inbox reste en base (audit).
                // Idempotence : double check (existsByOriginalInboxId + UNIQUE BDD).
                try {
                    quarantineOne(row, "no handler for type " + row.getType());
                    quarantined++;
                    metrics.incInboxQuarantined(row.getType());
                    log.warn("[sync-inbox] quarantined id={} type='{}' (no handler)",
                            row.getId(), row.getType());
                } catch (Exception e) {
                    // Échec INSERT quarantine ET échec mark processed → on préserve la ligne
                    // (sera retentée au prochain tick avec idempotence côté quarantine).
                    log.error("[sync-inbox] quarantine FAILED for id={} type='{}': {}",
                            row.getId(), row.getType(), e.getMessage());
                    errored++;
                }
                continue;
            }
            try {
                applyOne(row, handler);
                applied++;
                metrics.incInboxApplied(row.getType());
                log.debug("[sync-inbox] applied id={} type={}", row.getId(), row.getType());
            } catch (Exception e) {
                // Rollback déjà fait par tx.execute. Ligne reste processed=false.
                // V17 — Bump du compteur de retry en TX dédiée (le rollback applyOne
                // n'écrase pas l'incrément, qui est dans une autre TX).
                int newCount = bumpRetry(row);
                if (newCount > RETRY_WARN_THRESHOLD) {
                    log.warn("[sync-inbox] retry threshold exceeded id={} type={} count={} max={}",
                            row.getId(), row.getType(), newCount, MAX_RETRIES);
                }
                // Cas typique : payload invalide → l'événement n'est PAS perdu, il
                // reste en sync_inbox pour investigation. Au-delà de MAX_RETRIES,
                // le prochain tick le déplace en quarantine (reason=max-retries-exceeded).
                log.error("[sync-inbox] error processing id={} type={} retry={}: {}",
                        row.getId(), row.getType(), newCount, e.getMessage());
                errored++;
                metrics.incInboxErrored(row.getType());
            }
        }

        log.info("[sync-inbox] batch processed: total={} applied={} quarantined={} errored={}",
                rows.size(), applied, quarantined, errored);

        // V22 — Si au moins une mutation métier est matérialisée, notifier les
        // clients SSE connectés au backend local pour qu'ils re-hydratent
        // automatiquement (sales, products, customers, stock_movements). Sans
        // ça, le user devait F5 pour voir les events synchronisés. UN SEUL
        // "change" SSE par batch (et non par event) → frontend a un débounce
        // 400ms côté sse.ts qui collapse les rafales en 1 seule re-hydratation.
        if (applied > 0) {
            bus.publish("sync", "inbox-applied", null);
        }

        return new Result(rows.size(), applied, quarantined, errored);
    }

    /**
     * Apply + mark dans UNE seule transaction.
     *
     * Si une étape échoue (parse, handler, save mark), tout rollback → la ligne
     * reste à processed=false. Garantit l'invariant "marqué ⇒ appliqué".
     */
    private void applyOne(SyncInbox row, InboxHandler handler) {
        tx.executeWithoutResult(status -> {
            JsonNode payload;
            try {
                payload = mapper.readTree(row.getPayload() == null ? "{}" : row.getPayload());
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid JSON payload: " + e.getMessage(), e);
            }
            handler.apply(row.getId(), payload);
            row.setProcessed(true);
            inbox.save(row);
        });
    }

    /**
     * Quarantine + mark processed=true dans UNE seule transaction.
     *
     * Le paramètre {@code reason} est libre (V17 : permet de distinguer
     * "no handler" vs "max retries exceeded" dans quarantine_events.reason).
     * Coupé défensivement à 252 chars + "..." si nécessaire (colonne VARCHAR(255)).
     *
     * Idempotence triple :
     *   1. Check applicatif {@code existsByOriginalInboxId} avant insert
     *   2. UNIQUE en BDD sur {@code original_inbox_id} (V16) → catch silencieux
     *      d'une éventuelle race (deux tickers concurrents — impossible avec
     *      fixedDelay, défense en profondeur)
     *   3. Le marquage {@code processed=true} ne re-fetche pas le sync_inbox row :
     *      sa version en mémoire est déjà la dernière, save() = UPDATE par id
     *
     * Si l'INSERT quarantine échoue pour une raison NON-idempotence (DB down,
     * etc.) la TX rollback → ligne sync_inbox reste à processed=false → retry
     * au prochain tick (et idempotence du retry garantie par le check ci-dessus).
     */
    private void quarantineOne(SyncInbox row, String reason) {
        tx.executeWithoutResult(status -> {
            if (!quarantineRepo.existsByOriginalInboxId(row.getId())) {
                try {
                    quarantineRepo.save(QuarantineEvent.builder()
                            .id(UUID.randomUUID())
                            .originalInboxId(row.getId())
                            .eventType(row.getType())
                            .payload(row.getPayload() == null ? "{}" : row.getPayload())
                            .reason(truncateReason(reason))
                            .createdAt(Instant.now())
                            // V18 : preserve l'origine pour l'audit (utile au replay,
                            // au reporting, et au troubleshooting cross-store).
                            .storeId(row.getStoreId())
                            .build());
                } catch (DataIntegrityViolationException race) {
                    // Race entre check et insert : l'autre thread a inséré entre-temps.
                    // On considère la quarantaine comme déjà effective, on continue
                    // vers le mark processed=true.
                    log.debug("[sync-inbox] quarantine race for id={}: {}", row.getId(), race.getMessage());
                }
            }
            row.setProcessed(true);
            inbox.save(row);
        });
    }

    /**
     * V17 — Bump retry_count + last_attempt_at en TX dédiée.
     *
     * Pourquoi une TX séparée de {@link #applyOne(SyncInbox, InboxHandler)} :
     * cette dernière a déjà rollback (c'est pour ça qu'on est dans le catch).
     * Un bump dans la même TX serait lui aussi rollback → on perdrait la trace
     * de la tentative et le retry_count resterait à 0 indéfiniment.
     *
     * La ligne {@code row} est détachée (TX applyOne fermée) → {@code save()}
     * → JPA fait un merge (SELECT-then-UPDATE) qui applique nos 2 nouvelles
     * valeurs sans toucher aux autres champs (qui n'ont pas été mutés en mémoire).
     *
     * Retourne le nouveau retry_count (utilisé pour le log WARN threshold).
     */
    private int bumpRetry(SyncInbox row) {
        final int newCount = row.getRetryCount() + 1;
        tx.executeWithoutResult(status -> {
            // BUG FIX critique : applyOne fait `row.setProcessed(true)` AVANT le save.
            // Si le commit échoue (ex: contrainte unique violée au flush), la TX
            // rollback mais l'OBJET en mémoire reste avec processed=true. Si on
            // mergait ici directement cet objet, on écrirait processed=true en DB
            // → l'event serait marqué traité alors qu'il n'a JAMAIS été appliqué.
            //
            // Solution : recharger l'état DB authoritaire avant de bumper.
            SyncInbox fresh = inbox.findById(row.getId()).orElse(null);
            if (fresh == null || fresh.isProcessed()) return;  // race / déjà acquitté
            fresh.setRetryCount(newCount);
            fresh.setLastAttemptAt(Instant.now());
            inbox.save(fresh);
        });
        return newCount;
    }

    /** {@code reason VARCHAR(255)} — coupe défensivement les types très longs. */
    private static String truncateReason(String reason) {
        return reason.length() <= 255 ? reason : reason.substring(0, 252) + "...";
    }

    /**
     * Drain : enchaîne jusqu'à `maxBatches` batches tant qu'il y a du travail
     * ET qu'on progresse (au moins 1 ligne marquée processed=true). Borné pour
     * ne pas monopoliser le scheduler.
     *
     * Note : un batch entièrement composé de lignes ERRORED (ex: payload invalide)
     * NE progresse pas (les lignes restent processed=false) → on s'arrête pour
     * éviter de re-fetcher les mêmes lignes en boucle dans le même tick.
     */
    public Result processAll(int maxBatches) {
        Result acc = new Result(0, 0, 0, 0);
        for (int i = 0; i < Math.max(1, maxBatches); i++) {
            Result r = processBatch();
            if (r.total() == 0) break;
            acc = acc.plus(r);
            // Progrès = au moins une ligne marquée processed=true (apply ou quarantine).
            // Si tout le batch est errored, le SELECT suivant retournerait les mêmes
            // lignes → boucle. On laisse au prochain tick (60s plus tard) le soin de
            // re-tenter, après éventuelle correction côté émetteur.
            if (r.applied() == 0 && r.quarantined() == 0) break;
        }
        return acc;
    }

    public record Result(int total, int applied, int quarantined, int errored) {
        Result plus(Result other) {
            return new Result(
                    this.total + other.total,
                    this.applied + other.applied,
                    this.quarantined + other.quarantined,
                    this.errored + other.errored);
        }
    }
}
