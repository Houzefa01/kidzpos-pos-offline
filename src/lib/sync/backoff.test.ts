import { describe, it, expect } from "vitest";
import { createBackoffPolicy } from "./backoff";

/**
 * Tests unitaires BackoffPolicy — comportement attendu :
 *   attempts=0 → 0 ms (premier essai)
 *   attempts=1 → base × 1 = 1000 ms ± jitter
 *   attempts=2 → base × 2 = 2000 ms ± jitter
 *   attempts=N → min(base × 2^(N-1), maxDelay) ± jitter
 *
 * Pour la fidélité, on injecte `random` constant à 0.5 → jitter = 0 (centre).
 */

const noJitter = () => 0.5;  // (0.5 * 2 - 1) = 0 → jitter ratio = 0

describe("createBackoffPolicy", () => {
  it("retourne 0 ms tant qu'aucun échec", () => {
    const p = createBackoffPolicy({ random: noJitter });
    expect(p.attempts()).toBe(0);
    expect(p.nextDelayMs()).toBe(0);
  });

  it("double le délai à chaque échec, capé à maxDelayMs", () => {
    const p = createBackoffPolicy({ baseDelayMs: 100, maxDelayMs: 800, random: noJitter });
    p.fail(); expect(p.nextDelayMs()).toBe(100);  // 100 × 2^0
    p.fail(); expect(p.nextDelayMs()).toBe(200);  // 100 × 2^1
    p.fail(); expect(p.nextDelayMs()).toBe(400);  // 100 × 2^2
    p.fail(); expect(p.nextDelayMs()).toBe(800);  // 100 × 2^3 = 800 (cap)
    p.fail(); expect(p.nextDelayMs()).toBe(800);  // cap maintenu
  });

  it("reset() ramène à zéro", () => {
    const p = createBackoffPolicy({ random: noJitter });
    p.fail(); p.fail(); p.fail();
    expect(p.attempts()).toBe(3);
    p.reset();
    expect(p.attempts()).toBe(0);
    expect(p.nextDelayMs()).toBe(0);
  });

  it("jitter ±20 % : amplitude bornée", () => {
    // random=0 → -20%, random=1 → +20%, random=0.5 → 0
    const min = createBackoffPolicy({ baseDelayMs: 1000, random: () => 0 });
    const mid = createBackoffPolicy({ baseDelayMs: 1000, random: () => 0.5 });
    const max = createBackoffPolicy({ baseDelayMs: 1000, random: () => 0.999 });
    min.fail(); mid.fail(); max.fail();

    expect(min.nextDelayMs()).toBe(800);   // 1000 × (1 - 0.2)
    expect(mid.nextDelayMs()).toBe(1000);
    // 1000 × (1 + 0.2 × 0.998) = 1199.6 ≈ 1200 après round
    expect(max.nextDelayMs()).toBeGreaterThanOrEqual(1199);
    expect(max.nextDelayMs()).toBeLessThanOrEqual(1200);
  });

  it("cap à 20 fail() pour éviter Math.pow saturation", () => {
    const p = createBackoffPolicy({ baseDelayMs: 1000, maxDelayMs: 30_000, random: noJitter });
    for (let i = 0; i < 100; i++) p.fail();
    expect(p.attempts()).toBeLessThanOrEqual(20);
    expect(p.nextDelayMs()).toBe(30_000);  // capé à maxDelay quoi qu'il arrive
  });
});
