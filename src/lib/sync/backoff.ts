/**
 * Politique de backoff exponentiel avec jitter pour les drains de l'outbox.
 *
 * Utilisé par syncService entre deux appels successifs à drainQueue lorsque
 * le précédent a fini sur une erreur réseau / 5xx. Empêche un retry storm
 * coordonné entre N caisses qui repartent au même moment après un redémarrage
 * backend.
 *
 * Pas de timer en interne : c'est l'appelant qui consulte nextDelayMs et
 * gère son propre setTimeout / "ne pas re-drainer avant ts" (pulse côté
 * backend.ts est déjà cadencé toutes les 5-30s).
 */

export interface BackoffPolicy {
  /** Délai recommandé avant la prochaine tentative (ms). 0 si attempts() == 0. */
  nextDelayMs(): number;
  /** À appeler après un drain réussi : remet le compteur à zéro. */
  reset(): void;
  /** À appeler après un drain en échec (5xx / network / timeout). */
  fail(): void;
  /** Nb d'échecs consécutifs depuis le dernier reset. */
  attempts(): number;
}

export interface BackoffOptions {
  baseDelayMs?: number; // défaut 1000
  maxDelayMs?: number;  // défaut 30000
  jitterPct?: number;   // défaut 0.2 (±20 %)
  /** Pour les tests : remplace Math.random (doit retourner [0, 1)). */
  random?: () => number;
}

const MAX_ATTEMPTS = 20; // évite Math.pow(2, 50) → Infinity

export function createBackoffPolicy(opts: BackoffOptions = {}): BackoffPolicy {
  const baseDelayMs = opts.baseDelayMs ?? 1000;
  const maxDelayMs = opts.maxDelayMs ?? 30_000;
  const jitterPct = opts.jitterPct ?? 0.2;
  const random = opts.random ?? Math.random;
  let attempts = 0;

  return {
    nextDelayMs() {
      if (attempts === 0) return 0;
      // attempts=1 → base × 2^0 = base
      // attempts=2 → base × 2^1 = 2×base
      // capé à maxDelayMs
      const raw = baseDelayMs * Math.pow(2, attempts - 1);
      const capped = Math.min(raw, maxDelayMs);
      // jitter symétrique ± jitterPct → [capped*(1-j), capped*(1+j)]
      const jitter = (random() * 2 - 1) * jitterPct * capped;
      return Math.max(0, Math.round(capped + jitter));
    },
    reset() { attempts = 0; },
    fail() { attempts = Math.min(attempts + 1, MAX_ATTEMPTS); },
    attempts() { return attempts; },
  };
}
