/**
 * Sliding window de statistiques de replay.
 *
 * Maintient un ring buffer borné des dernières N opérations (succès / échec
 * réseau / 4xx + latence ms) pour calculer en O(1) ou O(N) :
 *   - successRate : succès / total
 *   - failureRate : échecs réseau-5xx (les 4xx ne sont PAS comptés comme
 *     "instabilité backend" — ce sont des erreurs métier client)
 *   - p50/p95 latency (sur les seuls succès)
 *   - consecutiveFailures : compteur strict d'échecs réseau-5xx en chaîne
 *   - consecutiveSuccesses : symétrique, pour la sortie de DEGRADED
 *
 * Le ring est conçu pour ne JAMAIS grossir : capacité fixe, pop-oldest sur full.
 * Aucune allocation par tick — c'est important pour les longues sessions (8h+).
 */

export type OpKind = "success" | "network_fail" | "client_error_4xx";

export interface OpSample {
  kind: OpKind;
  latencyMs: number; // pour success ; 0 pour les autres (non utilisé)
}

export interface WindowStats {
  total: number;
  successRate: number;       // [0,1]
  failureRate: number;       // [0,1] — uniquement network_fail
  p50LatencyMs: number;      // 0 si pas de succès dans la fenêtre
  p95LatencyMs: number;
  consecutiveFailures: number;
  consecutiveSuccesses: number;
}

export interface MetricsWindow {
  recordSuccess(latencyMs: number): void;
  recordNetworkFailure(): void;
  recordClientError(): void;
  snapshot(): WindowStats;
  reset(): void;
  capacity(): number;
}

export interface MetricsWindowOptions {
  capacity?: number; // défaut 50
}

export function createMetricsWindow(opts: MetricsWindowOptions = {}): MetricsWindow {
  const capacity = Math.max(5, opts.capacity ?? 50);
  // Ring buffer manuel — évite array.shift() qui est O(N).
  const buf: (OpSample | undefined)[] = new Array(capacity);
  let writeIdx = 0;
  let size = 0;
  let consecutiveFailures = 0;
  let consecutiveSuccesses = 0;

  function append(s: OpSample) {
    buf[writeIdx] = s;
    writeIdx = (writeIdx + 1) % capacity;
    if (size < capacity) size++;
  }

  return {
    recordSuccess(latencyMs) {
      append({ kind: "success", latencyMs: Math.max(0, latencyMs) });
      consecutiveSuccesses++;
      consecutiveFailures = 0;
    },
    recordNetworkFailure() {
      append({ kind: "network_fail", latencyMs: 0 });
      consecutiveFailures++;
      consecutiveSuccesses = 0;
    },
    recordClientError() {
      // 4xx → on enregistre mais n'incrémente PAS consecutiveFailures (pas un
      // signal de santé backend). On reset les success-en-chaîne tout de même
      // car un 4xx interrompt une série propre du point de vue replay.
      append({ kind: "client_error_4xx", latencyMs: 0 });
    },
    snapshot() {
      if (size === 0) {
        return {
          total: 0,
          successRate: 0,
          failureRate: 0,
          p50LatencyMs: 0,
          p95LatencyMs: 0,
          consecutiveFailures: 0,
          consecutiveSuccesses: 0,
        };
      }
      let successes = 0;
      let failures = 0;
      const latencies: number[] = [];
      for (let i = 0; i < size; i++) {
        const s = buf[i];
        if (!s) continue;
        if (s.kind === "success") {
          successes++;
          latencies.push(s.latencyMs);
        } else if (s.kind === "network_fail") {
          failures++;
        }
      }
      latencies.sort((a, b) => a - b);
      const p50 = latencies.length ? latencies[Math.floor(latencies.length * 0.5)] : 0;
      const p95 = latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * 0.95))] : 0;
      return {
        total: size,
        successRate: successes / size,
        failureRate: failures / size,
        p50LatencyMs: p50,
        p95LatencyMs: p95,
        consecutiveFailures,
        consecutiveSuccesses,
      };
    },
    reset() {
      for (let i = 0; i < buf.length; i++) buf[i] = undefined;
      writeIdx = 0;
      size = 0;
      consecutiveFailures = 0;
      consecutiveSuccesses = 0;
    },
    capacity() { return capacity; },
  };
}
