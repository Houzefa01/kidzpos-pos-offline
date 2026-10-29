/**
 * Machine à états du replay : NORMAL → DEGRADED → RECOVERY → NORMAL.
 *
 * Transitions purement déterministes basées sur les stats de la sliding window :
 *
 *   NORMAL → DEGRADED :
 *     failureRate > 0.3 OR consecutiveFailures ≥ 3
 *     (signal "backend instable ou réseau dégradé")
 *
 *   DEGRADED → RECOVERY :
 *     consecutiveSuccesses ≥ 3
 *     (premiers signes de retour)
 *
 *   RECOVERY → NORMAL :
 *     consecutiveSuccesses ≥ 10 AND failureRate < 0.1
 *     (stabilité confirmée — on relâche les brides)
 *
 *   RECOVERY → DEGRADED :
 *     consecutiveFailures ≥ 2
 *     (la reprise échoue, on re-protège)
 *
 * Implémentation : pure function `advance(state, stats)` + wrapper stateful
 * pour usage simple côté syncService. Pas de timer interne.
 */

import type { WindowStats } from "./metricsWindow";

export type ReplayMode = "NORMAL" | "DEGRADED" | "RECOVERY";

export interface StateMachine {
  current(): ReplayMode;
  advance(stats: WindowStats): { from: ReplayMode; to: ReplayMode; changed: boolean };
  /** Total cumulé d'entrées en DEGRADED depuis le boot — pour metric counter. */
  degradedEntriesTotal(): number;
  reset(): void;
}

const FAILURE_RATE_ENTER_DEGRADED = 0.3;
const CONSECUTIVE_FAIL_ENTER_DEGRADED = 3;
const CONSECUTIVE_SUCCESS_RECOVERY = 3;
const CONSECUTIVE_SUCCESS_NORMAL = 10;
const FAILURE_RATE_BACK_TO_NORMAL = 0.1;
const CONSECUTIVE_FAIL_REGRESS_TO_DEGRADED = 2;

/** Pure transition logic — exposé pour les tests et la composition. */
export function nextMode(current: ReplayMode, stats: WindowStats): ReplayMode {
  // Pas assez de signal → on reste sur l'état courant
  if (stats.total < 3) return current;

  switch (current) {
    case "NORMAL":
      if (stats.failureRate > FAILURE_RATE_ENTER_DEGRADED
          || stats.consecutiveFailures >= CONSECUTIVE_FAIL_ENTER_DEGRADED) {
        return "DEGRADED";
      }
      return "NORMAL";
    case "DEGRADED":
      if (stats.consecutiveSuccesses >= CONSECUTIVE_SUCCESS_RECOVERY) {
        return "RECOVERY";
      }
      return "DEGRADED";
    case "RECOVERY":
      if (stats.consecutiveFailures >= CONSECUTIVE_FAIL_REGRESS_TO_DEGRADED) {
        return "DEGRADED";
      }
      if (stats.consecutiveSuccesses >= CONSECUTIVE_SUCCESS_NORMAL
          && stats.failureRate < FAILURE_RATE_BACK_TO_NORMAL) {
        return "NORMAL";
      }
      return "RECOVERY";
  }
}

export function createStateMachine(initial: ReplayMode = "NORMAL"): StateMachine {
  let mode: ReplayMode = initial;
  let degradedEntries = 0;

  return {
    current() { return mode; },
    advance(stats) {
      const from = mode;
      const to = nextMode(mode, stats);
      if (to !== mode) {
        if (to === "DEGRADED") degradedEntries++;
        mode = to;
        return { from, to, changed: true };
      }
      return { from, to, changed: false };
    },
    degradedEntriesTotal() { return degradedEntries; },
    reset() {
      mode = "NORMAL";
      degradedEntries = 0;
    },
  };
}
