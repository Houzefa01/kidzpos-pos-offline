/**
 * Processeur de replay par batches. Remplace la boucle naïve historique
 * (drain en cascade jusqu'à la première erreur).
 *
 * Comportement :
 *  1. Lit la liste outbox (ordre FIFO)
 *  2. Tranche en lots de `batchSize` (15 par défaut)
 *  3. Dans chaque lot, attend `throttle.acquire()` avant chaque requête
 *  4. Pause `batchPauseMs` entre deux lots (laisse respirer le backend)
 *  5. Sur 4xx → enregistre via onFailure4xx + retire de l'outbox
 *  6. Sur 5xx/réseau → on5xxOrNetwork + STOPPE la boucle (retentera au tick suivant)
 *  7. Toutes les opérations sont strictement SÉQUENTIELLES (pas de Promise.all)
 *     → préserve la FIFO order + évite les rafales.
 *
 * Pas de dépendance Zustand / sonner / outbox ici : tout est injecté.
 * Testable unitairement avec des doubles.
 */

import type { OutboxEntry } from "@/lib/outbox";
import type { ThrottleController } from "./throttle";

export interface BatchProcessorDeps {
  /** Snapshot courant de la file. Appelé une fois au début de processBatches. */
  outboxList: () => OutboxEntry[];
  /** Suppression idempotente après succès ou 4xx. */
  outboxRemove: (id: string) => void;
  /** Envoi réseau. Throw ApiError ou Error (réseau). */
  apiSend: (entry: OutboxEntry) => Promise<void>;
  /** Hook succès — utilisé pour mesurer latency syncService. */
  onSuccess?: (entry: OutboxEntry) => void;
  /** Hook 4xx — route vers failedReplays + toast. err.status est garanti dans [400, 500). */
  onFailure4xx: (entry: OutboxEntry, err: Error & { status: number }) => void;
  /** Hook 5xx ou réseau — appelé une seule fois quand on stoppe le drain. */
  on5xxOrNetwork?: () => void;
  /** Compteur batch — incrémenté à chaque entrée traitée avec succès OU 4xx. */
  onItemProcessed?: () => void;
  throttle: ThrottleController;
  batchSize?: number;
  batchPauseMs?: number;
  /** Pour les tests : remplace setTimeout. */
  setTimeout?: (cb: () => void, ms: number) => unknown;
}

export interface BatchResult {
  sent: number;
  failed: number;
  /** True si la boucle a été interrompue par une erreur 5xx/réseau. */
  stoppedOnError: boolean;
}

function isApiErrorLike(e: unknown): e is Error & { status: number } {
  return e instanceof Error && typeof (e as { status?: unknown }).status === "number";
}

export async function processBatches(deps: BatchProcessorDeps): Promise<BatchResult> {
  const batchSize = Math.max(1, deps.batchSize ?? 15);
  const batchPauseMs = Math.max(0, deps.batchPauseMs ?? 50);
  const setTimeoutFn = deps.setTimeout ?? globalThis.setTimeout;

  const entries = deps.outboxList();
  let sent = 0;
  let failed = 0;
  let stopped = false;

  outer: for (let i = 0; i < entries.length; i += batchSize) {
    const batch = entries.slice(i, i + batchSize);
    for (const e of batch) {
      await deps.throttle.acquire();
      try {
        await deps.apiSend(e);
        deps.outboxRemove(e.id);
        sent++;
        deps.onSuccess?.(e);
        deps.onItemProcessed?.();
      } catch (err) {
        if (isApiErrorLike(err) && err.status >= 400 && err.status < 500) {
          deps.onFailure4xx(e, err);
          deps.outboxRemove(e.id);
          failed++;
          deps.onItemProcessed?.();
        } else {
          // 5xx, timeout, network : pas de retrait, on stoppe le drain.
          failed++;
          stopped = true;
          deps.on5xxOrNetwork?.();
          break outer;
        }
      }
    }
    // Pause entre batches si encore du travail.
    if (i + batchSize < entries.length) {
      await new Promise<void>((resolve) => setTimeoutFn(resolve, batchPauseMs));
    }
  }

  return { sent, failed, stoppedOnError: stopped };
}
