package com.kidzpos.observability;

import com.kidzpos.events.EventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests purs Mockito du healthcheck SSE. Pas de Spring, pas de Docker.
 *
 * Couvre :
 *   - Bus actif (broadcast récent) → UP avec détails {subscribers, secondsSinceLastBroadcast}
 *   - Bus stale (broadcast > seuil) → DOWN avec mêmes détails
 *   - Floor staleSeconds (60 s minimum pour éviter faux positifs au boot)
 *   - Format Instant.toString() sur lastBroadcastAt (ISO-8601, parseable)
 */
class SseHealthIndicatorTest {

    @Test
    void recentBroadcast_isUp_withSubscriberDetail() {
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 10_000); // 10 s ago
        when(bus.subscribers()).thenReturn(3);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("subscribers", 3)
                .containsEntry("staleAfterSeconds", 90L)
                .containsKey("lastBroadcastAt")
                .containsKey("secondsSinceLastBroadcast");
        long age = (long) health.getDetails().get("secondsSinceLastBroadcast");
        assertThat(age).isBetween(9L, 12L);
    }

    @Test
    void staleBroadcast_isDown() {
        EventBus bus = mock(EventBus.class);
        // 120 s ago → au-delà du seuil 90 s
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 120_000);
        when(bus.subscribers()).thenReturn(0);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("subscribers", 0);
        long age = (long) health.getDetails().get("secondsSinceLastBroadcast");
        assertThat(age).isGreaterThanOrEqualTo(120L);
    }

    @Test
    void zeroSubscribers_andRecentTick_isUp() {
        // Cas nominal au boot : 0 subscribers mais le heartbeater vient de tick
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 5_000);
        when(bus.subscribers()).thenReturn(0);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void staleSecondsBelowFloor_isClampedTo60() {
        EventBus bus = mock(EventBus.class);
        // Config absurde : staleSeconds = 5 → doit être clampé à 60
        SseHealthIndicator indicator = new SseHealthIndicator(bus, 5, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 30_000); // 30 s
        when(bus.subscribers()).thenReturn(1);

        Health health = indicator.health();
        // 30 s < 60 s (floor) → toujours UP
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("staleAfterSeconds", 60L);
    }

    @Test
    void lastBroadcastAt_isIso8601_parseable() {
        EventBus bus = mock(EventBus.class);
        long ts = System.currentTimeMillis() - 1_000;
        when(bus.lastBroadcastAt()).thenReturn(ts);
        when(bus.subscribers()).thenReturn(0);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();
        String iso = (String) health.getDetails().get("lastBroadcastAt");
        assertThat(iso).isNotBlank();
        // Doit parser sans exception et matcher le timestamp source à la ms près
        Instant parsed = Instant.parse(iso);
        assertThat(parsed.toEpochMilli()).isEqualTo(ts);
    }

    @Test
    void futureBroadcast_clockSkew_doesNotProduceNegativeAge() {
        // Edge case : si l'horloge système recule, lastBroadcastAt > now
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() + 10_000);
        when(bus.subscribers()).thenReturn(1);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();
        // Age clampé à 0, jamais négatif → UP
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        long age = (long) health.getDetails().get("secondsSinceLastBroadcast");
        assertThat(age).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void heartbeatPeriodSecondsInDetails_helpsOpsDiagnose() {
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis());
        when(bus.subscribers()).thenReturn(0);

        Health health = new SseHealthIndicator(bus, 90, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).health();
        // Le payload doit inclure la période du heartbeat — utile pour
        // l'opérateur qui regarde /actuator/health sans avoir le code sous la main
        assertThat(health.getDetails())
                .containsEntry("heartbeatPeriodSeconds", Duration.ofSeconds(30).getSeconds());
    }

    // ──── Gauges Micrometer (consommés par PromQL — cf ops/prometheus/alerts.yml) ──

    @Test
    void registersMicrometerGauges_busUpReflectsHealth() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry reg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 5_000);
        when(bus.subscribers()).thenReturn(2);

        new SseHealthIndicator(bus, 90, reg);

        // bus_up = 1 (broadcast récent)
        assertThat(reg.find("kidzpos.sse.bus_up").gauge().value()).isEqualTo(1.0);
        // subscribers = 2
        assertThat(reg.find("kidzpos.sse.subscribers").gauge().value()).isEqualTo(2.0);
        // age ≈ 5s
        double age = reg.find("kidzpos.sse.seconds_since_last_broadcast").gauge().value();
        assertThat(age).isBetween(4.0, 7.0);
    }

    @Test
    void busUpGauge_dropsToZeroWhenStale() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry reg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        EventBus bus = mock(EventBus.class);
        // Stale dès le départ
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis() - 150_000);
        when(bus.subscribers()).thenReturn(0);

        new SseHealthIndicator(bus, 90, reg);

        // Gauge lazy : valeur recalculée à chaque lecture → 0 car > 90s
        assertThat(reg.find("kidzpos.sse.bus_up").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void subscribersGauge_reflectsLiveCount() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry reg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        EventBus bus = mock(EventBus.class);
        when(bus.lastBroadcastAt()).thenReturn(System.currentTimeMillis());
        // Première lecture : 0 subscribers
        when(bus.subscribers()).thenReturn(0);

        new SseHealthIndicator(bus, 90, reg);
        assertThat(reg.find("kidzpos.sse.subscribers").gauge().value()).isEqualTo(0.0);

        // Le mock change → la gauge le voit (lazy callback, pas snapshot)
        when(bus.subscribers()).thenReturn(5);
        assertThat(reg.find("kidzpos.sse.subscribers").gauge().value()).isEqualTo(5.0);
    }
}
