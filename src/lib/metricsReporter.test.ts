import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { startMetricsReporter, stopMetricsReporter } from "./metricsReporter";
import { tokenStore } from "@/lib/apiClient";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";

/**
 * Tests metricsReporter — fake timers + mock api.
 *
 * Invariants vérifiés :
 *  1. Cadence : 1 POST par interval
 *  2. Skip si pas de token / pas online
 *  3. Dedup : 2 ticks identiques → 1 seul POST
 *  4. Samples latency → POST même si gauges identiques
 *  5. Silent fail si api throw
 *  6. stopMetricsReporter() libère le timer
 */

const INTERVAL = 100;  // ms — court pour les tests

let apiMock: ReturnType<typeof vi.fn>;
let snapshotCalls: Array<{ outboxSize: number; failedReplaysCount: number; syncLatencyMs: number[] }>;

beforeEach(() => {
  vi.useFakeTimers();
  apiMock = vi.fn().mockResolvedValue(undefined);
  snapshotCalls = [];
  // Backend dit qu'on est ok et token présent → tick poste.
  tokenStore.set("test-token");
  useBackend.setState({ lanReachable: true });
  useFailedReplays.getState().clear();
});

afterEach(() => {
  stopMetricsReporter();
  vi.useRealTimers();
  tokenStore.set(null);
  useBackend.setState({ lanReachable: false });
});

function makeSnapshot(outbox: number, failed: number, latencies: number[] = []) {
  return () => ({ outboxSize: outbox, failedReplaysCount: failed, syncLatencyMs: latencies });
}

describe("metricsReporter — cadence", () => {
  it("poste 1 fois par interval", async () => {
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: makeSnapshot(2, 1, []),
    });

    expect(apiMock).not.toHaveBeenCalled();  // pas d'appel immédiat

    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(1);
    expect(apiMock.mock.calls[0][1].body).toEqual({
      outboxSize: 2, failedReplaysCount: 1, syncLatencyMs: [],
    });
  });
});

describe("metricsReporter — guards", () => {
  it("skip si pas de token", async () => {
    tokenStore.set(null);
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: makeSnapshot(1, 0),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL * 3);
    expect(apiMock).not.toHaveBeenCalled();
  });

  it("skip si lanReachable=false", async () => {
    useBackend.setState({ lanReachable: false });
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: makeSnapshot(1, 0),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL * 3);
    expect(apiMock).not.toHaveBeenCalled();
  });
});

describe("metricsReporter — dedup", () => {
  it("2 ticks avec mêmes valeurs et zéro sample → 1 seul POST", async () => {
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: makeSnapshot(3, 1, []),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL);
    await vi.advanceTimersByTimeAsync(INTERVAL);
    await vi.advanceTimersByTimeAsync(INTERVAL);

    expect(apiMock).toHaveBeenCalledTimes(1);
  });

  it("valeurs changent → nouveau POST", async () => {
    let outbox = 1;
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: () => ({ outboxSize: outbox, failedReplaysCount: 0, syncLatencyMs: [] }),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(1);

    outbox = 5;
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(2);
  });

  it("samples latency présents → POST même si gauges identiques", async () => {
    let samples = [50, 80];
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: () => ({ outboxSize: 0, failedReplaysCount: 0, syncLatencyMs: samples }),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(1);

    // tick suivant : mêmes gauges, mais nouveaux samples → repost
    samples = [120];
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(2);

    // tick suivant : mêmes gauges, samples vides → dedup
    samples = [];
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(2);
  });
});

describe("metricsReporter — résilience", () => {
  it("api throw → tick suivant marche", async () => {
    apiMock.mockRejectedValueOnce(new Error("network"));
    let outbox = 1;
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: () => ({ outboxSize: outbox, failedReplaysCount: 0, syncLatencyMs: [] }),
    });

    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(1);  // 1er tick a tenté + échoué

    outbox = 2;
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(2);  // 2e tick fonctionne
  });

  it("stopMetricsReporter() arrête le timer", async () => {
    startMetricsReporter({
      api: apiMock as never,
      intervalMs: INTERVAL,
      snapshot: makeSnapshot(1, 0),
    });
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(apiMock).toHaveBeenCalledTimes(1);

    stopMetricsReporter();
    const outbox = 999;
    await vi.advanceTimersByTimeAsync(INTERVAL * 5);
    expect(apiMock).toHaveBeenCalledTimes(1);  // pas de tick supplémentaire
  });
});
