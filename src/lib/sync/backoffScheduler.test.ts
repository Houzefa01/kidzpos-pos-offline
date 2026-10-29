import { describe, it, expect } from "vitest";
import { createBackoffScheduler } from "./backoffScheduler";

const noJitter = () => 0.5;  // (0.5 * 2 - 1) = 0 → 0 jitter

describe("createBackoffScheduler", () => {
  it("failureCount=0 → 0 ms (premier essai immédiat)", () => {
    const s = createBackoffScheduler({ random: noJitter });
    expect(s.delayForFailureCount(0)).toBe(0);
    expect(s.delayForFailureCount(-1)).toBe(0);
  });

  it("exponentiel : double à chaque échec", () => {
    const s = createBackoffScheduler({ baseDelayMs: 1000, maxDelayMs: 60_000, random: noJitter });
    expect(s.delayForFailureCount(1)).toBe(1000);
    expect(s.delayForFailureCount(2)).toBe(2000);
    expect(s.delayForFailureCount(3)).toBe(4000);
    expect(s.delayForFailureCount(4)).toBe(8000);
  });

  it("capé à maxDelayMs", () => {
    const s = createBackoffScheduler({ baseDelayMs: 1000, maxDelayMs: 5000, random: noJitter });
    expect(s.delayForFailureCount(3)).toBe(4000);
    expect(s.delayForFailureCount(4)).toBe(5000);   // cap
    expect(s.delayForFailureCount(50)).toBe(5000);  // cap maintenu
  });

  it("jitter ±20 % borne le délai", () => {
    const minS = createBackoffScheduler({ baseDelayMs: 1000, random: () => 0 });
    const maxS = createBackoffScheduler({ baseDelayMs: 1000, random: () => 0.999 });
    expect(minS.delayForFailureCount(1)).toBe(800);  // 1000 × (1 - 0.2)
    // max ~ 1000 × (1 + 0.2 × 0.998) = 1199.6
    expect(maxS.delayForFailureCount(1)).toBeGreaterThanOrEqual(1199);
    expect(maxS.delayForFailureCount(1)).toBeLessThanOrEqual(1200);
  });

  it("maxAttempts exposé pour l'orchestrateur", () => {
    const s = createBackoffScheduler();
    expect(s.maxAttempts).toBeGreaterThan(10);  // au moins 10 attempts par item
  });
});
