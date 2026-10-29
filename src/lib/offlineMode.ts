/**
 * offlineMode — Point d'entrée minimal de l'architecture multi-serveurs.
 *
 * Pourquoi ce fichier ?
 * ─────────────────────
 * KidzPOS dispose DÉJÀ d'une infra offline-first complète :
 *   - Détection online/offline : useBackend.lanReachable (ping + SSE)
 *   - File d'actions hors-ligne : outbox (localStorage, FIFO bounded)
 *   - Replay adaptatif         : syncService (state machine + per-item backoff)
 *
 * Ce module N'INTRODUIT AUCUNE LOGIQUE NOUVELLE — c'est un wrapper d'API
 * stable que les futurs modules de sync (push/pull vers central, ETAPE 4+)
 * peuvent consommer sans avoir à connaître les internals des stores Zustand.
 *
 * Conséquence : zéro impact sur l'UI existante. Aucun import depuis les
 * pages POS / Sales / Stock / Settings / etc.
 */

import { useBackend } from "@/store/backend";
import { outbox } from "@/lib/outbox";
import { syncService } from "@/lib/syncService";

/** True si le backend LAN répond (ping OK ou SSE ouverte). */
export function isOnline(): boolean {
  return useBackend.getState().lanReachable;
}

/**
 * S'abonne aux changements d'état online/offline.
 * Retourne la fonction de désinscription (à appeler dans le cleanup).
 *
 * Usage :
 *   const unsub = subscribeOnline((online) => { ... });
 *   ...plus tard : unsub();
 */
export function subscribeOnline(handler: (online: boolean) => void): () => void {
  return useBackend.subscribe((state) => handler(state.lanReachable));
}

/**
 * Enfile une action arbitraire dans la file offline.
 * Délègue à l'outbox existant — mêmes garanties (dédoublonnage PUT/DELETE
 * par ref, validation Zod, FIFO bornée à 12 000 entrées).
 */
export function queueOfflineAction(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  body?: unknown,
  ref?: string,
): void {
  outbox.enqueue({ path, method, body, ref });
}

/** Nombre d'actions actuellement en attente de replay. */
export function pendingOfflineCount(): number {
  return outbox.size();
}

/**
 * Force un replay de la file offline (best-effort).
 * Délègue à syncService.replay() — pilote adaptatif déjà en place.
 * No-op si le backend est injoignable (le service garde la file en l'état).
 */
export async function flushOfflineQueue(): Promise<{ sent: number; failed: number }> {
  return syncService.replay();
}
