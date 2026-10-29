import { describe, it, expect } from "vitest";
import { decide, smooth, REPLAY_BOUNDS, DEFAULT_PARAMS, type ControllerParams } from "./replayController";
import type { WindowStats } from "./metricsWindow";

function statsOf(p: Partial<WindowStats> = {}): WindowStats {
  return {
    total: 50,
    successRate: 1,
    failureRate: 0,
    p50LatencyMs: 100,
    p95LatencyMs: 200,
    consecutiveFailures: 0,
    consecutiveSuccesses: 50,
    ...p,
  };
}

describe("decide()", () => {
  it("DEGRADED → consigne ultra-conservatrice", () => {
    const r = decide({
      mode: "DEGRADED",
      stats: statsOf(),
      queueSize: 100,
      softLimit: 3000,
      hardLimit: 10000,
    });
    expect(r.rps).toBe(REPLAY_BOUNDS.minRps);  // 2
    expect(r.batchSize).toBe(1);
    expect(r.batchPauseMs).toBe(REPLAY_BOUNDS.maxPauseMs);
  });

  it("RECOVERY → consigne intermédiaire (ramp-up)", () => {
    const r = decide({
      mode: "RECOVERY",
      stats: statsOf(),
      queueSize: 100,
      softLimit: 3000,
      hardLimit: 10000,
    });
    expect(r.rps).toBeGreaterThan(REPLAY_BOUNDS.minRps);
    expect(r.rps).toBeLessThan(REPLAY_BOUNDS.maxRps);
    expect(r.batchSize).toBeGreaterThan(1);
    expect(r.batchSize).toBeLessThan(REPLAY_BOUNDS.maxBatch);
  });

  it("NORMAL + santé excellente + pression forte → MAX throughput", () => {
    const r = decide({
      mode: "NORMAL",
      stats: statsOf({ successRate: 1.0, p95LatencyMs: 100 }),
      queueSize: 5000,        // > softLimit
      softLimit: 3000,
      hardLimit: 10000,
    });
    expect(r.rps).toBe(REPLAY_BOUNDS.maxRps);
    expect(r.batchSize).toBe(REPLAY_BOUNDS.maxBatch);
  });

  it("NORMAL + santé limite → freine sans entrer DEGRADED", () => {
    const r = decide({
      mode: "NORMAL",
      stats: statsOf({ p95LatencyMs: 2000, failureRate: 0.15 }),
      queueSize: 1000,
      softLimit: 3000,
      hardLimit: 10000,
    });
    expect(r.rps).toBeLessThanOrEqual(5);
    expect(r.batchSize).toBeLessThanOrEqual(15);
  });

  it("Bornes respectées (clamp)", () => {
    // Aucune combinaison ne doit sortir des bornes
    const r1 = decide({
      mode: "NORMAL",
      stats: statsOf({ p95LatencyMs: 100, successRate: 1 }),
      queueSize: 99999,
      softLimit: 100,
      hardLimit: 999999,
    });
    expect(r1.rps).toBeGreaterThanOrEqual(REPLAY_BOUNDS.minRps);
    expect(r1.rps).toBeLessThanOrEqual(REPLAY_BOUNDS.maxRps);
    expect(r1.batchSize).toBeGreaterThanOrEqual(REPLAY_BOUNDS.minBatch);
    expect(r1.batchSize).toBeLessThanOrEqual(REPLAY_BOUNDS.maxBatch);
  });
});

describe("smooth()", () => {
  it("retourne target si dans la step limit", () => {
    const prev: ControllerParams = { rps: 5, batchSize: 15, batchPauseMs: 50 };
    const target: ControllerParams = { rps: 6, batchSize: 17, batchPauseMs: 80 };
    const r = smooth(prev, target);
    expect(r).toEqual(target);
  });

  it("limite les sauts (±1 RPS, ±5 batch, ±50 pause)", () => {
    const prev: ControllerParams = { rps: 5, batchSize: 10, batchPauseMs: 50 };
    const target: ControllerParams = { rps: 15, batchSize: 30, batchPauseMs: 500 };
    const r = smooth(prev, target);
    expect(r.rps).toBe(6);          // +1
    expect(r.batchSize).toBe(15);    // +5
    expect(r.batchPauseMs).toBe(100); // +50
  });

  it("limite à la baisse symétriquement", () => {
    const prev: ControllerParams = { rps: 10, batchSize: 20, batchPauseMs: 200 };
    const target: ControllerParams = { rps: 2, batchSize: 1, batchPauseMs: 25 };
    const r = smooth(prev, target);
    expect(r.rps).toBe(9);
    expect(r.batchSize).toBe(15);
    expect(r.batchPauseMs).toBe(150);
  });

  it("convergence en plusieurs ticks", () => {
    const target: ControllerParams = { rps: REPLAY_BOUNDS.maxRps, batchSize: REPLAY_BOUNDS.maxBatch, batchPauseMs: 500 };
    let current = { ...DEFAULT_PARAMS };
    for (let i = 0; i < 30; i++) current = smooth(current, target);
    expect(current).toEqual(target);  // a convergé
  });
});
