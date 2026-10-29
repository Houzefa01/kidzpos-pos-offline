import { describe, it, expect } from "vitest";
import { createStateMachine, nextMode } from "./stateMachine";
import type { WindowStats } from "./metricsWindow";

function stats(partial: Partial<WindowStats>): WindowStats {
  return {
    total: 10,
    successRate: 1,
    failureRate: 0,
    p50LatencyMs: 100,
    p95LatencyMs: 200,
    consecutiveFailures: 0,
    consecutiveSuccesses: 0,
    ...partial,
  };
}

describe("nextMode (pure transitions)", () => {
  it("reste sur état courant si pas assez de signal (total < 3)", () => {
    expect(nextMode("NORMAL", stats({ total: 1, consecutiveFailures: 99 }))).toBe("NORMAL");
    expect(nextMode("DEGRADED", stats({ total: 2, consecutiveSuccesses: 99 }))).toBe("DEGRADED");
  });

  describe("NORMAL transitions", () => {
    it("→ DEGRADED si failureRate > 0.3", () => {
      expect(nextMode("NORMAL", stats({ failureRate: 0.4 }))).toBe("DEGRADED");
    });
    it("→ DEGRADED si 3 échecs consécutifs", () => {
      expect(nextMode("NORMAL", stats({ consecutiveFailures: 3 }))).toBe("DEGRADED");
    });
    it("reste NORMAL si santé bonne", () => {
      expect(nextMode("NORMAL", stats({ failureRate: 0.05, consecutiveFailures: 1 }))).toBe("NORMAL");
    });
  });

  describe("DEGRADED transitions", () => {
    it("→ RECOVERY après 3 succès consécutifs", () => {
      expect(nextMode("DEGRADED", stats({ consecutiveSuccesses: 3 }))).toBe("RECOVERY");
    });
    it("reste DEGRADED si moins de 3 succès consécutifs", () => {
      expect(nextMode("DEGRADED", stats({ consecutiveSuccesses: 2 }))).toBe("DEGRADED");
    });
  });

  describe("RECOVERY transitions", () => {
    it("→ NORMAL après 10 succès consécutifs et failureRate < 0.1", () => {
      expect(nextMode("RECOVERY", stats({
        consecutiveSuccesses: 10,
        failureRate: 0.05,
      }))).toBe("NORMAL");
    });
    it("reste RECOVERY si pas encore stable", () => {
      expect(nextMode("RECOVERY", stats({
        consecutiveSuccesses: 5,
        failureRate: 0.05,
      }))).toBe("RECOVERY");
    });
    it("→ DEGRADED si la reprise échoue (2 fails consécutifs)", () => {
      expect(nextMode("RECOVERY", stats({ consecutiveFailures: 2 }))).toBe("DEGRADED");
    });
  });
});

describe("createStateMachine (stateful)", () => {
  it("démarre en NORMAL et compte les entrées DEGRADED", () => {
    const sm = createStateMachine();
    expect(sm.current()).toBe("NORMAL");
    expect(sm.degradedEntriesTotal()).toBe(0);

    sm.advance(stats({ failureRate: 0.5 }));
    expect(sm.current()).toBe("DEGRADED");
    expect(sm.degradedEntriesTotal()).toBe(1);

    sm.advance(stats({ consecutiveSuccesses: 3 }));
    expect(sm.current()).toBe("RECOVERY");
    expect(sm.degradedEntriesTotal()).toBe(1);

    sm.advance(stats({ consecutiveFailures: 2 }));
    expect(sm.current()).toBe("DEGRADED");
    expect(sm.degradedEntriesTotal()).toBe(2);  // 2e entrée
  });

  it("advance() retourne changed=false si même état", () => {
    const sm = createStateMachine();
    const r = sm.advance(stats({ failureRate: 0.05 }));
    expect(r.changed).toBe(false);
    expect(r.from).toBe("NORMAL");
    expect(r.to).toBe("NORMAL");
  });

  it("reset() retour à NORMAL", () => {
    const sm = createStateMachine();
    sm.advance(stats({ failureRate: 0.5 }));
    expect(sm.current()).toBe("DEGRADED");
    sm.reset();
    expect(sm.current()).toBe("NORMAL");
    expect(sm.degradedEntriesTotal()).toBe(0);
  });
});
