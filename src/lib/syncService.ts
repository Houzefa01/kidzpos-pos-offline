/**
 * syncService — Anti-corruption layer offline-first.
 *
 * Point d'entrée UNIQUE pour toutes les mutations du frontend. Encapsule :
 *  - la décision online/offline → envoi direct ou file outbox
 *  - le replay de la file au retour réseau
 *  - la traçabilité des 4xx rejetés (failedReplays + toast)
 *  - la garde des mutations sensibles (POST/PUT /api/users : password jamais
 *    persisté dans localStorage, même sur timeout/5xx)
 *
 * Le module est paramétré par un Transport injectable → testable avec un mock
 * (cf syncService.test.ts), pas de fetch réel pendant les tests.
 *
 * Migration progressive : pushMutation (cf store/backend.ts) délègue à
 * syncService.submit() depuis P1, mais conserve sa signature pour que les
 * 24 call-sites des stores n'aient pas besoin d'être adaptés.
 */

import { api, ApiError } from "@/lib/apiClient";
import { outbox } from "@/lib/outbox";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { toast } from "sonner";
import { createThrottle } from "@/lib/sync/throttle";
import { createMetricsWindow } from "@/lib/sync/metricsWindow";
import { createStateMachine, type ReplayMode } from "@/lib/sync/stateMachine";
import { createBackoffScheduler } from "@/lib/sync/backoffScheduler";
import { decide, smooth, DEFAULT_PARAMS, type ControllerParams } from "@/lib/sync/replayController";

export type HttpMethod = "POST" | "PUT" | "PATCH" | "DELETE";

export interface MutationSpec {
  path: string;
  method: HttpMethod;
  body?: unknown;
  /** Dédoublonnage outbox pour PUT/DELETE sur la même ressource logique. */
  ref?: string;
  /** Force le statut "sensible" (override de la détection automatique).
   *  Mutation sensible = jamais persistée localement (password en clair). */
  sensitive?: boolean;
}

export interface SubmitResult {
  queued: boolean;
  error?: string;
}

export interface ReplayResult {
  sent: number;
  failed: number;
}

/** Surface réseau / file abstraite. Remplaçable en test via createSyncService. */
export interface Transport {
  isOnline(): boolean;
  send(spec: MutationSpec): Promise<void>;
  enqueue(spec: MutationSpec, reason: "offline" | "failure-after-mark"): void;
  markUnreachable(): void;
  drainQueue(): Promise<ReplayResult>;
  pendingCount(): number;
}

export interface SyncService {
  submit(spec: MutationSpec): Promise<SubmitResult>;
  replay(): Promise<ReplayResult>;
  pendingCount(): number;
}

const SENSITIVE_AUTH_PATH = /^\/api\/users(\/|$)/;

function isSensitive(spec: MutationSpec): boolean {
  if (spec.sensitive) return true;
  if (spec.method !== "POST" && spec.method !== "PUT") return false;
  return SENSITIVE_AUTH_PATH.test(spec.path);
}

export function createSyncService(transport: Transport): SyncService {
  return {
    async submit(spec) {
      const sensitive = isSensitive(spec);

      if (!transport.isOnline()) {
        if (sensitive) {
          return { queued: false, error: "Connexion serveur requise pour cette opération" };
        }
        transport.enqueue(spec, "offline");
        return { queued: true };
      }

      try {
        await transport.send(spec);
        return { queued: false };
      } catch (err: unknown) {
        const status = (err as { status?: number })?.status;
        const message = err instanceof Error ? err.message : "Erreur inconnue";

        // 4xx : faute applicative côté client/données → ne pas enfiler, remonter l'erreur.
        if (status !== undefined && status >= 400 && status < 500) {
          return { queued: false, error: message };
        }

        // 5xx ou réseau : backend indisponible. Bascule lan=false dans tous les cas
        // (sensible ou non), puis enfile si non-sensible.
        transport.markUnreachable();
        if (sensitive) {
          return {
            queued: false,
            error: "Échec serveur — opération sensible non enregistrée, recommencez",
          };
        }
        transport.enqueue(spec, "failure-after-mark");
        return { queued: true };
      }
    },

    replay() {
      return transport.drainQueue();
    },

    pendingCount() {
      return transport.pendingCount();
    },
  };
}

// ────────────────────────────────────────────────────────────────────────────
// Transport par défaut : adapte apiClient + outbox + useBackend + failedReplays
// + sonner. Reproduit fidèlement le comportement de pushMutation + flushOutbox
// historique (mêmes side-effects sur lanReachable et pendingCount).
// ────────────────────────────────────────────────────────────────────────────

const defaultTransport: Transport = {
  isOnline: () => useBackend.getState().lanReachable,

  async send(spec) {
    // T6 — Instrumentation P5 : enregistre la latence des envois directs (chemin
    // online "happy path") en plus du replay outbox déjà instrumenté ligne ~216.
    // Auparavant les samples étaient drainés par metricsReporter mais alimentés
    // UNIQUEMENT depuis le replay — donc une caisse offline-rare sur LAN stable
    // exportait toujours un tableau vide vers Prometheus. Le replayController
    // adaptatif (P5) avait des décisions basées sur un signal manquant.
    const start = Date.now();
    try {
      await api(spec.path, { method: spec.method, body: spec.body, timeoutMs: 8000 });
      recordLatencySample(Date.now() - start);
    } catch (err) {
      // Sur erreur, on enregistre quand même la latence (utile pour mesurer les
      // timeouts vs les 5xx rapides). Ne masque PAS l'exception.
      recordLatencySample(Date.now() - start);
      throw err;
    }
  },

  enqueue(spec, _reason) {
    outbox.enqueue({ path: spec.path, method: spec.method, body: spec.body, ref: spec.ref });
    useBackend.getState().refreshPending();
  },

  markUnreachable() {
    useBackend.getState().setLan(false);
  },

  /**
   * P5 — Replay adaptatif self-healing.
   *
   *   • Per-item backoff isolation : chaque entry porte `failureCount` +
   *     `nextEligibleAt`. Un échec n'arrête PAS le drain ; l'item est
   *     replanifié individuellement. Les autres continuent.
   *   • State machine NORMAL/DEGRADED/RECOVERY pilote rps + batchSize +
   *     pauseMs. Transitions déterministes basées sur la sliding window.
   *   • Adaptive controller : decide() produit la consigne ; smooth() lisse
   *     les transitions (±1 RPS, ±5 batch par tick).
   *   • Overflow soft/hard : SOFT_LIMIT 3000 → bump du débit ;
   *     HARD_LIMIT 10000 → PAUSE + notification synthétique unique.
   *   • Dead letter : un item dépassant MAX_PER_ITEM_ATTEMPTS attempts
   *     est déplacé vers failedReplays (anti-starvation : la file ne
   *     croît pas indéfiniment d'items chroniquement défaillants).
   *
   * Contrat extérieur préservé : Promise<{sent, failed}> ; verrou réentrance.
   * FIFO préservée pour les items first-try success ; items réessayés
   * peuvent être re-séquencés (per-item isolation > strict global FIFO).
   */
  async drainQueue() {
    if (drainingRef.value) return { sent: 0, failed: 0 };

    const totalSize = outbox.size();

    // Hard limit : signal critique, on n'essaie même pas (back pressure).
    if (totalSize > HARD_LIMIT) {
      notifyOverflowOnce(totalSize);
      return { sent: 0, failed: 0 };
    }
    clearOverflowNotification();

    drainingRef.value = true;
    try {
      // 1. Décide la consigne courante depuis stats + queue + mode.
      const stats = metricsWindow.snapshot();
      const target = decide({
        mode: stateMachine.current(),
        stats,
        queueSize: totalSize,
        softLimit: SOFT_LIMIT,
        hardLimit: HARD_LIMIT,
      });
      currentParams = smooth(currentParams, target);
      replayThrottle.setRate(currentParams.rps);

      // 2. Filtre les items éligibles (nextEligibleAt absent ou passé).
      //    On préserve l'ordre FIFO d'enqueue dans la sélection.
      const now = Date.now();
      const allEntries = outbox.list();
      const eligible = allEntries.filter((e) => !e.nextEligibleAt || e.nextEligibleAt <= now);
      if (eligible.length === 0) return { sent: 0, failed: 0 };

      // 3. Drain par batches adaptatifs avec per-item retry.
      let sent = 0;
      let failed = 0;
      const { batchSize, batchPauseMs } = currentParams;

      for (let i = 0; i < eligible.length; i += batchSize) {
        const batch = eligible.slice(i, i + batchSize);
        for (const e of batch) {
          await replayThrottle.acquire();
          const start = Date.now();
          try {
            await api(e.path, { method: e.method, body: e.body, timeoutMs: 5000 });
            outbox.remove(e.id);
            sent++;
            const latency = Date.now() - e.ts;
            recordLatencySample(latency);
            metricsWindow.recordSuccess(Date.now() - start);
            batchItemsProcessed++;
          } catch (err) {
            if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
              // 4xx — erreur métier permanente. Notify + remove. Pas de retry.
              const body = err.body as { error?: string } | null;
              const message = body?.error ?? err.message ?? `HTTP ${err.status}`;
              useFailedReplays.getState().add({
                ts: Date.now(),
                path: e.path,
                method: e.method,
                body: e.body,
                ref: e.ref,
                status: err.status,
                error: message,
              });
              toast.error(`Mutation rejetée (${err.status}) : ${message}`, { duration: 6000 });
              outbox.remove(e.id);
              failed++;
              metricsWindow.recordClientError();
              batchItemsProcessed++;
            } else {
              // 5xx / réseau / timeout → backoff PER-ITEM. Le drain CONTINUE.
              metricsWindow.recordNetworkFailure();
              const newFailureCount = (e.failureCount ?? 0) + 1;
              const errMsg = err instanceof Error ? err.message : "unknown";

              if (newFailureCount >= backoffScheduler.maxAttempts) {
                // Dead letter — empêche le starvation (item chroniquement KO).
                useFailedReplays.getState().add({
                  ts: Date.now(),
                  path: e.path,
                  method: e.method,
                  body: e.body,
                  ref: e.ref,
                  status: 0,
                  error: `Abandon après ${newFailureCount} échecs réseau : ${errMsg}`,
                });
                outbox.remove(e.id);
                failed++;
                batchItemsProcessed++;
                deadLetterCount++;
              } else {
                // Replan individuel — l'item retentera au-delà de nextEligibleAt.
                const delay = backoffScheduler.delayForFailureCount(newFailureCount);
                outbox.update(e.id, {
                  failureCount: newFailureCount,
                  lastError: errMsg,
                  nextEligibleAt: now + delay,
                });
                failed++;
                perItemRetries++;
              }
            }
          }
        }
        // Pause inter-lots — laisse respirer le backend.
        if (i + batchSize < eligible.length) {
          await new Promise<void>((resolve) => setTimeout(resolve, batchPauseMs));
        }
      }

      // 4. Met à jour le state machine en fin de drain.
      const transition = stateMachine.advance(metricsWindow.snapshot());
      if (transition.changed && transition.to === "DEGRADED") {
        // Mode dégradé : laisse une trace dans la console (pas de toast — UX).
        console.warn("[syncService] Replay entered DEGRADED mode (backend instable)");
      }

      return { sent, failed };
    } finally {
      drainingRef.value = false;
    }
  },

  pendingCount: () => outbox.size(),
};

// Verrou réentrance pour drainQueue (cf flushing flag historique)
const drainingRef = { value: false };

// ────────────────────────────────────────────────────────────────────────────
// P5 — État interne du replay adaptatif self-healing
// ────────────────────────────────────────────────────────────────────────────

/** Soft limit : au-delà, le replayController augmente le débit. */
const SOFT_LIMIT = 3000;
/** Hard limit : au-delà, drainQueue se met en pause + notification. */
const HARD_LIMIT = 10_000;

const replayThrottle = createThrottle({ maxRequestsPerSecond: DEFAULT_PARAMS.rps });
const metricsWindow = createMetricsWindow({ capacity: 50 });
const stateMachine = createStateMachine();
const backoffScheduler = createBackoffScheduler({ baseDelayMs: 2000, maxDelayMs: 5 * 60_000 });

/** Consigne courante (mise à jour à chaque drainQueue via smooth()). */
let currentParams: ControllerParams = { ...DEFAULT_PARAMS };

/** Compteurs cumulés (deltas exposés au metricsReporter, reset() à chaque envoi). */
let batchItemsProcessed = 0;
let perItemRetries = 0;
let deadLetterCount = 0;

/** Lecture-destructive : appelé par metricsReporter pour expédier les deltas. */
export function drainReplayStats(): {
  batchSize: number;
  throttleDelayMs: number;
  backoffRetries: number;
  mode: ReplayMode;
  currentRps: number;
  currentBatchSize: number;
  degradedEntriesTotal: number;
} {
  const out = {
    batchSize: batchItemsProcessed,
    throttleDelayMs: Math.round(replayThrottle.totalDelayMs()),
    // perItemRetries est l'équivalent P5 de backoffRetries — mêmes sémantique
    // côté Prometheus (nombre d'opérations qui ont déclenché un backoff).
    backoffRetries: perItemRetries,
    mode: stateMachine.current(),
    currentRps: Math.round(replayThrottle.getRate() * 100) / 100,
    currentBatchSize: currentParams.batchSize,
    degradedEntriesTotal: stateMachine.degradedEntriesTotal(),
  };
  batchItemsProcessed = 0;
  perItemRetries = 0;
  deadLetterCount = 0;
  replayThrottle.reset();
  return out;
}

/** Pour les tests : remet à zéro toute la machinerie P5. */
export function __resetReplayStateForTests(): void {
  drainingRef.value = false;
  replayThrottle.reset();
  replayThrottle.setRate(DEFAULT_PARAMS.rps);
  metricsWindow.reset();
  stateMachine.reset();
  currentParams = { ...DEFAULT_PARAMS };
  batchItemsProcessed = 0;
  perItemRetries = 0;
  deadLetterCount = 0;
  overflowNotified = false;
}

// ── Overflow guard ──────────────────────────────────────────────────────────
let overflowNotified = false;
function notifyOverflowOnce(currentSize: number): void {
  if (overflowNotified) return;
  overflowNotified = true;
  // Entrée synthétique visible dans le FailedReplaysButton existant.
  useFailedReplays.getState().add({
    ts: Date.now(),
    path: "<replay-paused>",
    method: "POST",
    status: 0,
    error: `Replay en pause — file d'attente saturée (${currentSize} actions). Reprise automatique dès retrait de l'excédent.`,
  });
}
function clearOverflowNotification(): void {
  overflowNotified = false;
}

// ────────────────────────────────────────────────────────────────────────────
// Sync latency samples (P3) — exposés à metricsReporter.
// On garde un ring buffer borné pour éviter toute fuite mémoire si le reporter
// est désactivé ou ne tourne pas. Lecture-destructive (drainLatencySamples).
// ────────────────────────────────────────────────────────────────────────────
const LATENCY_BUFFER_MAX = 100;
const latencySamples: number[] = [];

function recordLatencySample(ms: number) {
  if (!Number.isFinite(ms) || ms < 0) return;
  if (latencySamples.length >= LATENCY_BUFFER_MAX) {
    latencySamples.shift();
  }
  latencySamples.push(ms);
}

/** Retourne ET vide le buffer interne. Appelé par metricsReporter à chaque tick. */
export function drainLatencySamples(): number[] {
  if (latencySamples.length === 0) return [];
  const out = latencySamples.slice();
  latencySamples.length = 0;
  return out;
}

/** Instance singleton utilisée par pushMutation et backend.pulse. */
export const syncService = createSyncService(defaultTransport);
