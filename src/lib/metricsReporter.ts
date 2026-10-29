/**
 * P3 — Export périodique des métriques offline-first vers le backend.
 *
 * Toutes les `INTERVAL_MS` (30s par défaut) :
 *   1. Lit l'état courant (outbox size, failedReplays count, latency samples)
 *   2. Si rien n'a changé ET aucun sample → skip (silence)
 *   3. POST /api/metrics/frontend (auth Bearer ; cookies httpOnly à part)
 *   4. Échec → silencieux (pas de toast, pas de log bruyant — c'est de la
 *      télémétrie best-effort)
 *
 * Skipped si :
 *   - pas d'access token (utilisateur non connecté)
 *   - lanReachable = false (backend HS)
 *
 * Testable : startMetricsReporter accepte un override de transport (api) +
 * interval, utilisés par metricsReporter.test.ts avec fake timers.
 */
import { api as defaultApi } from "@/lib/apiClient";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { tokenStore } from "@/lib/apiClient";
import { drainLatencySamples, drainReplayStats } from "@/lib/syncService";

export interface ReporterDeps {
  api?: typeof defaultApi;
  intervalMs?: number;
  /** Surcharge pour les tests : lit l'état à l'instant t. */
  snapshot?: () => Snapshot;
}

interface Snapshot {
  outboxSize: number;
  failedReplaysCount: number;
  syncLatencyMs: number[];
  // P4 — compteurs de replay contrôlé. Optionnels (le backend les ignore si
  // absents, cf FrontendMetricsReq côté DTO).
  replayBatchSize?: number;
  replayThrottleDelayMs?: number;
  replayBackoffRetries?: number;
  // P5 — état du replay adaptatif self-healing.
  replayMode?: "NORMAL" | "DEGRADED" | "RECOVERY";
  replayAdaptiveRps?: number;
  replayAdaptiveBatchSize?: number;
  replayDegradedEntriesTotal?: number;
}

const DEFAULT_INTERVAL_MS = 30_000;

function defaultSnapshot(): Snapshot {
  const stats = drainReplayStats();
  return {
    outboxSize: useBackend.getState().pendingCount,
    failedReplaysCount: useFailedReplays.getState().failures.length,
    syncLatencyMs: drainLatencySamples(),
    replayBatchSize: stats.batchSize,
    replayThrottleDelayMs: stats.throttleDelayMs,
    replayBackoffRetries: stats.backoffRetries,
    replayMode: stats.mode,
    replayAdaptiveRps: stats.currentRps,
    replayAdaptiveBatchSize: stats.currentBatchSize,
    replayDegradedEntriesTotal: stats.degradedEntriesTotal,
  };
}

let timer: ReturnType<typeof setInterval> | null = null;
let lastSent: { outboxSize: number; failedReplaysCount: number } | null = null;

export function startMetricsReporter(deps: ReporterDeps = {}): () => void {
  if (timer) return stopMetricsReporter;
  const api = deps.api ?? defaultApi;
  const interval = deps.intervalMs ?? DEFAULT_INTERVAL_MS;
  const snapshot = deps.snapshot ?? defaultSnapshot;

  async function tick() {
    // Skip si non authentifié ou backend HS — pas de bruit réseau inutile.
    if (!tokenStore.get() || !useBackend.getState().lanReachable) return;

    const s = snapshot();
    const sameAsLast =
      lastSent !== null
      && lastSent.outboxSize === s.outboxSize
      && lastSent.failedReplaysCount === s.failedReplaysCount;
    if (sameAsLast && s.syncLatencyMs.length === 0) return;

    try {
      await api("/api/metrics/frontend", {
        method: "POST",
        body: s,
        timeoutMs: 3000,
      });
      lastSent = { outboxSize: s.outboxSize, failedReplaysCount: s.failedReplaysCount };
    } catch {
      // Télémétrie best-effort : on absorbe silencieusement (offline, 5xx, etc.).
      // Pas de retry — le prochain tick repostera l'état courant.
    }
  }

  timer = setInterval(tick, interval);
  return stopMetricsReporter;
}

export function stopMetricsReporter(): void {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  lastSent = null;
}
