import { describe, it, expect } from "vitest";
import { createMetricsWindow } from "./metricsWindow";

describe("createMetricsWindow", () => {
  it("snapshot vide quand rien n'a été enregistré", () => {
    const w = createMetricsWindow();
    expect(w.snapshot()).toEqual({
      total: 0,
      successRate: 0,
      failureRate: 0,
      p50LatencyMs: 0,
      p95LatencyMs: 0,
      consecutiveFailures: 0,
      consecutiveSuccesses: 0,
    });
  });

  it("compteurs consécutifs incrémentent + reset croisé", () => {
    const w = createMetricsWindow();
    w.recordSuccess(100);
    w.recordSuccess(110);
    expect(w.snapshot().consecutiveSuccesses).toBe(2);
    expect(w.snapshot().consecutiveFailures).toBe(0);

    w.recordNetworkFailure();
    expect(w.snapshot().consecutiveSuccesses).toBe(0);
    expect(w.snapshot().consecutiveFailures).toBe(1);

    w.recordSuccess(120);
    expect(w.snapshot().consecutiveSuccesses).toBe(1);
    expect(w.snapshot().consecutiveFailures).toBe(0);
  });

  it("4xx n'incrémente PAS consecutiveFailures (pas un signal backend)", () => {
    const w = createMetricsWindow();
    w.recordNetworkFailure();          // consecutiveFailures = 1
    w.recordClientError();             // no-op sur consecutiveFailures
    w.recordNetworkFailure();          // consecutiveFailures = 2
    // 4xx neutre vis-à-vis de la santé backend : ni reset ni incrément.
    expect(w.snapshot().consecutiveFailures).toBe(2);
  });

  it("ring buffer borné : pas de fuite mémoire après N+1 ops", () => {
    const w = createMetricsWindow({ capacity: 10 });
    for (let i = 0; i < 1000; i++) w.recordSuccess(i);
    const s = w.snapshot();
    expect(s.total).toBe(10);  // capé
    expect(s.successRate).toBe(1.0);
    expect(w.capacity()).toBe(10);
  });

  it("p50/p95 calculés correctement sur les succès uniquement", () => {
    const w = createMetricsWindow({ capacity: 20 });
    // 10 succès linéaires de 10 à 100 ms
    for (let i = 1; i <= 10; i++) w.recordSuccess(i * 10);
    // 5 échecs (ne doivent pas être pris en compte dans la latence)
    for (let i = 0; i < 5; i++) w.recordNetworkFailure();
    const s = w.snapshot();
    expect(s.p50LatencyMs).toBeGreaterThanOrEqual(50);
    expect(s.p50LatencyMs).toBeLessThanOrEqual(60);
    expect(s.p95LatencyMs).toBeGreaterThanOrEqual(90);
  });

  it("successRate / failureRate compatibles", () => {
    const w = createMetricsWindow();
    w.recordSuccess(100);
    w.recordSuccess(100);
    w.recordNetworkFailure();
    w.recordClientError();
    const s = w.snapshot();
    expect(s.total).toBe(4);
    expect(s.successRate).toBe(0.5);    // 2/4
    expect(s.failureRate).toBe(0.25);   // 1/4 (4xx non compté en network)
  });

  it("reset() vide tout", () => {
    const w = createMetricsWindow();
    w.recordSuccess(100);
    w.recordNetworkFailure();
    w.reset();
    expect(w.snapshot().total).toBe(0);
    expect(w.snapshot().consecutiveFailures).toBe(0);
  });
});
