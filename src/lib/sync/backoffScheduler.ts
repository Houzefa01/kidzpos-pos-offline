/**
 * Per-item backoff scheduler — calcule la fenêtre d'éligibilité d'un item
 * après N échecs réseau/5xx ISOLÉS à cet item.
 *
 * Différent de BackoffPolicy (P4) qui était global au drain : ici chaque
 * mutation porte son propre `failureCount` et obtient son propre délai.
 *
 * Stratégie :
 *   delay = min(maxDelayMs, baseDelayMs × 2^(failureCount - 1))
 *   appliqué jitter ±20 % pour éviter les coïncidences entre items qui ont
 *   échoué au même cycle.
 *
 * Pure function — pas d'état, testable trivialement. Le scheduler donne
 * seulement la VALEUR ; c'est l'orchestrateur (syncService) qui pose
 * `nextEligibleAt = Date.now() + delay` sur l'entry.
 */

export interface BackoffSchedulerOptions {
  baseDelayMs?: number;   // défaut 2000 (un peu plus long que le global P4)
  maxDelayMs?: number;    // défaut 5 × 60_000 = 5 min
  jitterPct?: number;     // défaut 0.2
  /** Pour les tests : remplace Math.random. */
  random?: () => number;
}

export interface BackoffScheduler {
  delayForFailureCount(failureCount: number): number;
  /** Plafond logique de retries par item — au-delà, dead-letter. */
  maxAttempts: number;
}

const DEFAULT_MAX_ATTEMPTS = 50;  // ~30h de fenêtres jusqu'au cap, puis cap × N
const MAX_POW = 20;  // évite Math.pow(2, 50) → Infinity

export function createBackoffScheduler(opts: BackoffSchedulerOptions = {}): BackoffScheduler {
  const baseDelayMs = opts.baseDelayMs ?? 2000;
  const maxDelayMs = opts.maxDelayMs ?? 5 * 60_000;
  const jitterPct = opts.jitterPct ?? 0.2;
  const random = opts.random ?? Math.random;

  return {
    delayForFailureCount(failureCount: number): number {
      if (failureCount <= 0) return 0;
      const exp = Math.min(failureCount - 1, MAX_POW);
      const raw = baseDelayMs * Math.pow(2, exp);
      const capped = Math.min(raw, maxDelayMs);
      const jitter = (random() * 2 - 1) * jitterPct * capped;
      return Math.max(0, Math.round(capped + jitter));
    },
    maxAttempts: DEFAULT_MAX_ATTEMPTS,
  };
}
