package com.kidzpos.observability;

import com.kidzpos.dto.Dtos.FrontendMetricsReq;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Agrège les métriques offline-first remontées par les caisses.
 *
 * Une caisse POST son snapshot toutes les 30s (cf metricsReporter.ts). On stocke
 * la dernière valeur PAR userId dans une map en mémoire. Les gauges Prometheus
 * exposent la SOMME (ou le count) sans label dynamique → cardinalité bornée.
 *
 * TTL 5 min sur lecture : si une caisse arrête de reporter, son snapshot est
 * ignoré au calcul (pas besoin de thread de cleanup — éviction opportuniste
 * à chaque record() + filtre à la lecture).
 *
 * Les samples de latency syncLatencyMs alimentent un DistributionSummary
 * (histogram Micrometer → buckets Prometheus le="…").
 */
@Service
public class FrontendMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(FrontendMetricsCollector.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final double LATENCY_MAX_MS = 3_600_000; // sanity-check 1h

    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final DistributionSummary syncLatency;
    private final Counter replayBatchSize;
    private final Counter replayThrottleDelayMs;
    private final Counter replayBackoffRetries;
    // P5 — Adaptive replay
    private final Counter degradedModeEntries;
    private final Map<String, AdaptiveSnapshot> adaptive = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public FrontendMetricsCollector(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /** Constructor exposé pour les tests : permet d'injecter un Clock fixed.
     *  Pas annoté @Autowired → Spring ne le considère pas pour l'injection. */
    public FrontendMetricsCollector(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        this.syncLatency = DistributionSummary.builder("kidzpos.frontend.sync_latency_ms")
                .description("Latence entre enqueue outbox et replay réussi (ms)")
                .baseUnit("milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        Gauge.builder("kidzpos.frontend.outbox_size", this, FrontendMetricsCollector::totalOutboxSize)
                .description("Somme des tailles d'outbox sur toutes les caisses actives")
                .register(registry);
        Gauge.builder("kidzpos.frontend.failed_replays", this, FrontendMetricsCollector::totalFailedReplays)
                .description("Somme des échecs 4xx persistés sur toutes les caisses actives")
                .register(registry);
        Gauge.builder("kidzpos.frontend.reporting_cashiers", this, FrontendMetricsCollector::activeCashierCount)
                .description("Nombre de caisses ayant reporté dans les " + STALE_AFTER.toMinutes() + " dernières minutes")
                .register(registry);

        // P4 — Counters cumulés (deltas envoyés par le client). Aucun label
        // dynamique : un time-series global pour toute la flotte.
        this.replayBatchSize = Counter.builder("kidzpos.frontend.replay_batch_size")
                .description("Nombre cumulé d'entrées outbox replay-traitées par batch (succès + 4xx)")
                .register(registry);
        this.replayThrottleDelayMs = Counter.builder("kidzpos.frontend.replay_throttle_delay_ms")
                .description("Cumul des ms d'attente du throttle client (anti-burst)")
                .baseUnit("milliseconds")
                .register(registry);
        this.replayBackoffRetries = Counter.builder("kidzpos.frontend.replay_backoff_retries")
                .description("Nombre de drains de l'outbox interrompus par 5xx/réseau (déclenchant un backoff)")
                .register(registry);

        // P5 — Adaptive replay gauges (agrégats max sur snapshots actives).
        this.degradedModeEntries = Counter.builder("kidzpos.frontend.degraded_mode_entries")
                .description("Cumul d'entrées en mode DEGRADED toutes caisses confondues")
                .register(registry);

        Gauge.builder("kidzpos.frontend.replay_mode_max", this, FrontendMetricsCollector::worstReplayMode)
                .description("Pire mode replay actif (0=NORMAL, 1=RECOVERY, 2=DEGRADED) — alerte > 0")
                .register(registry);
        Gauge.builder("kidzpos.frontend.adaptive_rps_current", this, FrontendMetricsCollector::medianAdaptiveRps)
                .description("RPS médian appliqué par les caisses actives")
                .register(registry);
        Gauge.builder("kidzpos.frontend.adaptive_batch_size_current", this, FrontendMetricsCollector::medianAdaptiveBatchSize)
                .description("Taille de batch médiane appliquée par les caisses actives")
                .register(registry);
    }

    public void record(String userId, FrontendMetricsReq req) {
        if (userId == null || userId.isBlank()) return;
        Instant now = clock.instant();
        // Éviction opportuniste : nettoie les vieilles entrées avant d'ajouter
        Instant cutoff = now.minus(STALE_AFTER);
        snapshots.entrySet().removeIf(e -> e.getValue().ts().isBefore(cutoff));
        snapshots.put(userId, new Snapshot(req.outboxSize(), req.failedReplaysCount(), now));

        if (req.syncLatencyMs() != null) {
            for (Double v : req.syncLatencyMs()) {
                if (v != null && v >= 0 && v <= LATENCY_MAX_MS) {
                    syncLatency.record(v);
                }
            }
        }

        // P4 — Counters cumulés. Les champs sont optionnels (clients P3 → null).
        if (req.replayBatchSize() != null && req.replayBatchSize() > 0) {
            replayBatchSize.increment(req.replayBatchSize());
        }
        if (req.replayThrottleDelayMs() != null && req.replayThrottleDelayMs() > 0) {
            replayThrottleDelayMs.increment(req.replayThrottleDelayMs());
        }
        if (req.replayBackoffRetries() != null && req.replayBackoffRetries() > 0) {
            replayBackoffRetries.increment(req.replayBackoffRetries());
        }

        // P5 — Adaptive replay : snapshots actifs par caisse + counter cumulé.
        if (req.replayMode() != null || req.replayAdaptiveRps() != null
                || req.replayAdaptiveBatchSize() != null) {
            adaptive.put(userId, new AdaptiveSnapshot(
                    req.replayMode() == null ? "NORMAL" : req.replayMode(),
                    req.replayAdaptiveRps() == null ? 0.0 : req.replayAdaptiveRps(),
                    req.replayAdaptiveBatchSize() == null ? 0 : req.replayAdaptiveBatchSize(),
                    now));
        }
        // degraded_mode_entries_total = somme des entrées en DEGRADED.
        // Le client envoie un cumul depuis le boot ; on calcule le delta et incrément.
        if (req.replayDegradedEntriesTotal() != null && req.replayDegradedEntriesTotal() > 0) {
            Long delta = computeDegradedDelta(userId, req.replayDegradedEntriesTotal());
            if (delta > 0) degradedModeEntries.increment(delta);
        }

        log.debug("Frontend metrics recorded: userId={} outbox={} failed={} samples={} batch={} throttleMs={} backoff={}",
                userId, req.outboxSize(), req.failedReplaysCount(),
                req.syncLatencyMs() == null ? 0 : req.syncLatencyMs().size(),
                req.replayBatchSize(), req.replayThrottleDelayMs(), req.replayBackoffRetries());
    }

    // ── Gauge suppliers ──────────────────────────────────────────────────────

    double totalOutboxSize() {
        return activeSnapshots().mapToInt(Snapshot::outboxSize).sum();
    }

    double totalFailedReplays() {
        return activeSnapshots().mapToInt(Snapshot::failedReplays).sum();
    }

    double activeCashierCount() {
        return activeSnapshots().count();
    }

    private Stream<Snapshot> activeSnapshots() {
        Instant cutoff = clock.instant().minus(STALE_AFTER);
        return snapshots.values().stream().filter(s -> s.ts().isAfter(cutoff));
    }

    // ── P5 — Adaptive snapshot helpers ──────────────────────────────────────

    private Stream<AdaptiveSnapshot> activeAdaptive() {
        Instant cutoff = clock.instant().minus(STALE_AFTER);
        return adaptive.values().stream().filter(s -> s.ts().isAfter(cutoff));
    }

    double worstReplayMode() {
        return activeAdaptive()
                .mapToInt(s -> modeRank(s.mode()))
                .max()
                .orElse(0);
    }

    double medianAdaptiveRps() {
        double[] xs = activeAdaptive().mapToDouble(AdaptiveSnapshot::rps).sorted().toArray();
        return xs.length == 0 ? 0 : xs[xs.length / 2];
    }

    double medianAdaptiveBatchSize() {
        int[] xs = activeAdaptive().mapToInt(AdaptiveSnapshot::batchSize).sorted().toArray();
        return xs.length == 0 ? 0 : xs[xs.length / 2];
    }

    private static int modeRank(String mode) {
        return switch (mode == null ? "NORMAL" : mode) {
            case "DEGRADED" -> 2;
            case "RECOVERY" -> 1;
            default -> 0;
        };
    }

    private final Map<String, Long> degradedTotalsLastSeen = new ConcurrentHashMap<>();

    private Long computeDegradedDelta(String userId, long currentTotal) {
        Long previous = degradedTotalsLastSeen.put(userId, currentTotal);
        if (previous == null) return currentTotal;       // 1er report → on prend le cumul
        long delta = currentTotal - previous;
        return delta > 0 ? delta : 0L;                    // ignore reset (boot du client)
    }

    private record Snapshot(int outboxSize, int failedReplays, Instant ts) {}
    private record AdaptiveSnapshot(String mode, double rps, int batchSize, Instant ts) {}
}
