import { describe, it, expect, vi } from "vitest";
import { createThrottle } from "./throttle";

describe("createThrottle", () => {
  it("première acquisition est immédiate", async () => {
    let setTimeoutCalls = 0;
    const fakeSetTimeout = (cb: () => void, _ms: number) => {
      setTimeoutCalls++;
      cb();
      return 0 as never;
    };
    const t = createThrottle({ maxRequestsPerSecond: 10, setTimeout: fakeSetTimeout });
    await t.acquire();
    expect(setTimeoutCalls).toBe(0);  // pas de wait au 1er
    expect(t.totalDelayMs()).toBe(0);
  });

  it("acquisitions consécutives respectent l'intervalle minimum", async () => {
    let elapsedFakeMs = 0;
    const now = () => elapsedFakeMs;
    const waits: number[] = [];
    const fakeSetTimeout = (cb: () => void, ms: number) => {
      waits.push(ms);
      elapsedFakeMs += ms;
      cb();
      return 0 as never;
    };
    // 5 req/s → 200 ms entre deux
    const t = createThrottle({ maxRequestsPerSecond: 5, setTimeout: fakeSetTimeout, now });

    await t.acquire();         // t=0 (immédiat)
    elapsedFakeMs = 50;        // 50 ms après
    await t.acquire();         // doit attendre 150 ms

    expect(waits).toEqual([150]);
    expect(t.totalDelayMs()).toBe(150);
  });

  it("acquisitions concurrentes sont sérialisées", async () => {
    let elapsedFakeMs = 0;
    const now = () => elapsedFakeMs;
    const waits: number[] = [];
    const fakeSetTimeout = (cb: () => void, ms: number) => {
      waits.push(ms);
      elapsedFakeMs += ms;
      cb();
      return 0 as never;
    };
    const t = createThrottle({ maxRequestsPerSecond: 10, setTimeout: fakeSetTimeout, now });

    // 4 acquires lancés simultanément
    await Promise.all([t.acquire(), t.acquire(), t.acquire(), t.acquire()]);

    // Attendus : 1er instantané, 3 suivants attendent 100 ms chacun (intervalMs = 100)
    expect(waits).toEqual([100, 100, 100]);
    expect(t.totalDelayMs()).toBe(300);
  });

  it("reset() vide le cumul", async () => {
    const t = createThrottle({ maxRequestsPerSecond: 100, setTimeout: ((cb: () => void) => { cb(); return 0 as never; }) as never });
    await t.acquire();
    await t.acquire();
    t.reset();
    expect(t.totalDelayMs()).toBe(0);
  });

  it("rps ≤ 0 est clampé à 0.1", async () => {
    // Pas d'exception, pas de division par zéro
    const t = createThrottle({ maxRequestsPerSecond: 0 });
    expect(() => t.totalDelayMs()).not.toThrow();
  });
});
