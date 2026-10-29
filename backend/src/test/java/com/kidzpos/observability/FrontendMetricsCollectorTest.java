package com.kidzpos.observability;

import com.kidzpos.dto.Dtos.FrontendMetricsReq;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires : pas de Spring, pas de TestContainers. Vérifie l'agrégation
 * (sum across cashiers), l'éviction TTL 5 min, l'enregistrement des samples
 * dans le DistributionSummary, et la borne sanity-check sur les latencies.
 */
class FrontendMetricsCollectorTest {

    @Test
    void aggregatesAcrossCashiers() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        c.record("u1", new FrontendMetricsReq(3, 1, List.of(100.0, 200.0), null, null, null, null, null, null, null));
        c.record("u2", new FrontendMetricsReq(5, 0, List.of(50.0), null, null, null, null, null, null, null));

        assertThat(reg.find("kidzpos.frontend.outbox_size").gauge().value()).isEqualTo(8.0);
        assertThat(reg.find("kidzpos.frontend.failed_replays").gauge().value()).isEqualTo(1.0);
        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(2.0);

        var lat = reg.find("kidzpos.frontend.sync_latency_ms").summary();
        assertThat(lat.count()).isEqualTo(3);
        assertThat(lat.totalAmount()).isEqualTo(350.0);
    }

    @Test
    void reReportFromSameCashierOverwritesNotAccumulates() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        c.record("u1", new FrontendMetricsReq(10, 2, List.of(), null, null, null, null, null, null, null));
        c.record("u1", new FrontendMetricsReq(3, 0, List.of(), null, null, null, null, null, null, null));  // queue vidée

        // Le gauge prend la VALEUR ACTUELLE (la plus récente), pas la somme.
        assertThat(reg.find("kidzpos.frontend.outbox_size").gauge().value()).isEqualTo(3.0);
        assertThat(reg.find("kidzpos.frontend.failed_replays").gauge().value()).isEqualTo(0.0);
        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void staleSnapshotsAreEvictedAfter5Min() {
        var mutableNow = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg, mutableNow);

        // u1 reporte à 10:00 avec outbox=10
        c.record("u1", new FrontendMetricsReq(10, 0, List.of(), null, null, null, null, null, null, null));
        assertThat(reg.find("kidzpos.frontend.outbox_size").gauge().value()).isEqualTo(10.0);
        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(1.0);

        // 6 min plus tard, u2 reporte (u1 n'a pas re-reporté → stale)
        mutableNow.advance(Duration.ofMinutes(6));
        c.record("u2", new FrontendMetricsReq(3, 0, List.of(), null, null, null, null, null, null, null));

        // u1 est évincé : seul u2 compte
        assertThat(reg.find("kidzpos.frontend.outbox_size").gauge().value()).isEqualTo(3.0);
        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void latencySamplesOutOfBoundsAreFiltered() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        // 1h00 = 3 600 000 ms (borne incluse), 1h01 = au-delà → filtré
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(100.0, 3_600_000.0, 3_700_000.0, -1.0), null, null, null, null, null, null, null));

        var lat = reg.find("kidzpos.frontend.sync_latency_ms").summary();
        assertThat(lat.count()).isEqualTo(2);  // 100 et 3.6M acceptés
        assertThat(lat.totalAmount()).isEqualTo(3_600_100.0);
    }

    @Test
    void emptySamplesListIsAccepted() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null, null, null, null, null));
        c.record("u2", new FrontendMetricsReq(0, 0, null, null, null, null, null, null, null, null));  // null tolérable

        assertThat(reg.find("kidzpos.frontend.sync_latency_ms").summary().count()).isEqualTo(0);
        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void blankUserIdIsIgnored() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        c.record(null, new FrontendMetricsReq(99, 99, List.of(99.0), null, null, null, null, null, null, null));
        c.record("", new FrontendMetricsReq(99, 99, List.of(99.0), null, null, null, null, null, null, null));
        c.record("   ", new FrontendMetricsReq(99, 99, List.of(99.0), null, null, null, null, null, null, null));

        assertThat(reg.find("kidzpos.frontend.reporting_cashiers").gauge().value()).isEqualTo(0.0);
        assertThat(reg.find("kidzpos.frontend.sync_latency_ms").summary().count()).isEqualTo(0);
    }

    @Test
    void p4CountersIncrementedFromOptionalFields() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        // 1er report : 3 entrées batch, 250ms throttle, 1 backoff
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), 3L, 250L, 1L, null, null, null, null));
        // 2e report : 5 entrées batch, 100ms throttle, 0 backoff
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), 5L, 100L, 0L, null, null, null, null));

        assertThat(reg.find("kidzpos.frontend.replay_batch_size").counter().count()).isEqualTo(8.0);
        assertThat(reg.find("kidzpos.frontend.replay_throttle_delay_ms").counter().count()).isEqualTo(350.0);
        assertThat(reg.find("kidzpos.frontend.replay_backoff_retries").counter().count()).isEqualTo(1.0);
    }

    @Test
    void p4CountersIgnoreNullAndZeroFields() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        // Client P3 (sans champs P4) → counters restent à 0
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null, null, null, null, null));
        c.record("u2", new FrontendMetricsReq(0, 0, List.of(), 0L, 0L, 0L, null, null, null, null));

        assertThat(reg.find("kidzpos.frontend.replay_batch_size").counter().count()).isEqualTo(0.0);
        assertThat(reg.find("kidzpos.frontend.replay_throttle_delay_ms").counter().count()).isEqualTo(0.0);
        assertThat(reg.find("kidzpos.frontend.replay_backoff_retries").counter().count()).isEqualTo(0.0);
    }

    @Test
    void p5AdaptiveSnapshotsAggregateAcrossCashiers() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        // u1 en DEGRADED, rps=2, batch=1
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null,
                "DEGRADED", 2.0, 1, 5L));
        // u2 en NORMAL, rps=7, batch=20
        c.record("u2", new FrontendMetricsReq(0, 0, List.of(), null, null, null,
                "NORMAL", 7.0, 20, 0L));

        // worst mode visible : DEGRADED → rank 2
        assertThat(reg.find("kidzpos.frontend.replay_mode_max").gauge().value()).isEqualTo(2.0);
        // médiane sur 2 caisses → index 1 (la plus grande)
        assertThat(reg.find("kidzpos.frontend.adaptive_rps_current").gauge().value()).isEqualTo(7.0);
        assertThat(reg.find("kidzpos.frontend.adaptive_batch_size_current").gauge().value()).isEqualTo(20.0);
        // degraded entries cumulés (1er report u1 → 5)
        assertThat(reg.find("kidzpos.frontend.degraded_mode_entries").counter().count()).isEqualTo(5.0);
    }

    @Test
    void p5DegradedDeltaIsIncremental() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var c = new FrontendMetricsCollector(reg);

        // u1 reporte degradedTotal=5 puis 8 → delta = 5 puis 3 = total 8
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null,
                "NORMAL", 5.0, 15, 5L));
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null,
                "NORMAL", 5.0, 15, 8L));
        assertThat(reg.find("kidzpos.frontend.degraded_mode_entries").counter().count()).isEqualTo(8.0);

        // Reset client (boot — degraded passe de 8 à 2) → no decrement (delta ≤ 0)
        c.record("u1", new FrontendMetricsReq(0, 0, List.of(), null, null, null,
                "NORMAL", 5.0, 15, 2L));
        assertThat(reg.find("kidzpos.frontend.degraded_mode_entries").counter().count()).isEqualTo(8.0);
    }

    // ── helper : Clock mutable pour tester le TTL ───────────────────────────

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
