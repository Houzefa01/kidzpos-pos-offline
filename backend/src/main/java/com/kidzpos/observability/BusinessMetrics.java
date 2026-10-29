package com.kidzpos.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Counters exposés via /actuator/prometheus pour suivre la santé métier :
 * - replays idempotents (combien de doubles fois rejouées sont absorbées)
 * - conflits optimistic locking
 * - rate-limit hits
 *
 * Pas de gauges custom ici ; HTTP latency et JVM metrics sont émis nativement
 * par Spring Boot Actuator (http.server.requests, jvm.*).
 */
@Component
public class BusinessMetrics {

    public final Counter stockIdempotentReplay;
    public final Counter saleIdempotentReplay;
    public final Counter refundIdempotentReplay;
    public final Counter saleSeqRetry;
    public final Counter optimisticLockConflict;
    public final Counter loginRateLimited;

    /**
     * Counters par event-type pour le sync inbox processor. Lazy-init dans une
     * map concurrente : un Counter par (outcome, type) où outcome ∈
     * {applied, quarantined, errored}. Permet de tracer en Prometheus quel type
     * d'event dérive (ex: "product.updated" qui errored 5x/min révèle un bug
     * de handler ou un schema drift entre stores).
     *
     * Cardinality bornée par le nombre de handlers whitelistés (~10 aujourd'hui)
     * × 3 outcomes = ~30 séries max. Safe pour Prometheus.
     */
    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> inboxApplied = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> inboxQuarantined = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> inboxErrored = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.stockIdempotentReplay = Counter.builder("kidzpos.stock.idempotent_replay")
                .description("Mouvements de stock court-circuités car clientMovementId déjà appliqué")
                .register(registry);
        this.saleIdempotentReplay = Counter.builder("kidzpos.sale.idempotent_replay")
                .description("Ventes court-circuitées car clientSaleId déjà appliqué")
                .register(registry);
        this.refundIdempotentReplay = Counter.builder("kidzpos.refund.idempotent_replay")
                .description("V22 — Refunds court-circuités car clientRefundId déjà appliqué")
                .register(registry);
        this.saleSeqRetry = Counter.builder("kidzpos.sale.seq_retry")
                .description("Retries sur collision uk_sale_store_seq")
                .register(registry);
        this.optimisticLockConflict = Counter.builder("kidzpos.optimistic_lock.conflict")
                .description("Edits rejetés à cause d'un @Version stale (HTTP 409)")
                .register(registry);
        this.loginRateLimited = Counter.builder("kidzpos.login.rate_limited")
                .description("Tentatives de login rejetées par LoginRateLimitFilter (HTTP 429)")
                .register(registry);
    }

    public void incInboxApplied(String type) {
        inboxApplied.computeIfAbsent(safe(type), t -> Counter.builder("kidzpos.sync.inbox.applied")
                .description("Events sync_inbox appliqués par type")
                .tag("type", t)
                .register(registry)).increment();
    }

    public void incInboxQuarantined(String type) {
        inboxQuarantined.computeIfAbsent(safe(type), t -> Counter.builder("kidzpos.sync.inbox.quarantined")
                .description("Events sync_inbox quarantinés par type (no-handler ou max-retries)")
                .tag("type", t)
                .register(registry)).increment();
    }

    public void incInboxErrored(String type) {
        inboxErrored.computeIfAbsent(safe(type), t -> Counter.builder("kidzpos.sync.inbox.errored")
                .description("Events sync_inbox en erreur transitoire par type (rollback + retry)")
                .tag("type", t)
                .register(registry)).increment();
    }

    /** Borne la cardinality : type NULL/blank → "unknown". Évite de polluer Prometheus. */
    private static String safe(String type) {
        return (type == null || type.isBlank()) ? "unknown" : type;
    }
}
