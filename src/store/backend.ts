import { create } from "zustand";
import { api, pingBackend, tokenStore } from "@/lib/apiClient";
import { outbox } from "@/lib/outbox";
import { startSse, stopSse, isSseOpen } from "@/lib/sse";
import { syncService } from "@/lib/syncService";

/**
 * T15 — Snapshot de l'état du backend SYNC central pour cette caisse.
 *
 * Distinguer 2 niveaux :
 *  • {@link BackendState.pendingCount}  : outbox LOCAL (caisse → backend store).
 *  • {@link CentralSyncStatus.pending}  : operation_log LOCAL (backend store → central).
 *
 * Le second n'est visible qu'en lisant /api/sync/status (le backend store
 * connaît son propre lag central). Permet à l'UI d'afficher "X ops vers le
 * central — Y s de retard" plutôt qu'un simple "online".
 */
export interface CentralSyncStatus {
  /** Type du nœud backend : "central" | "local" | "standalone". */
  nodeRole: string;
  /** Nombre d'events operation_log.synced=false côté backend store. */
  pending: number;
  /** Age (s) du plus vieux event non syncé. 0 = tout est à jour. */
  lagSeconds: number;
  /** Horodatage local du dernier fetch réussi. */
  lastFetchAt: number;
}

interface BackendState {
  lanReachable: boolean;
  pendingCount: number;
  lastSync: number | null;
  centralSync: CentralSyncStatus | null;
  setLan: (v: boolean) => void;
  refreshPending: () => void;
  pulse: () => Promise<void>;
}

export const useBackend = create<BackendState>((set, get) => ({
  lanReachable: false,
  pendingCount: outbox.size(),
  lastSync: null,
  centralSync: null,
  setLan: (v) => set({ lanReachable: v }),
  refreshPending: () => set({ pendingCount: outbox.size() }),
  pulse: async () => {
    const wasReachable = get().lanReachable;
    // Si SSE est ouverte, le backend répond forcément — inutile de le pinguer
    const ok = isSseOpen() || await pingBackend();
    if (ok !== wasReachable) {
      set({ lanReachable: ok });
      if (ok) {
        // hydrateFromBackend déclenche startSse() en interne au succès.
        // Pas de .finally(startSse) ici : (1) ça dédoublait l'appel pendant la
        // fenêtre async POST /api/events/auth (deux EventSource ouverts au boot),
        // (2) en cas d'échec hydrate, ouvrir SSE est inutile (backend unreachable).
        import("@/lib/syncBackend")
          .then(({ hydrateFromBackend }) => hydrateFromBackend())
          .catch(() => {});
      } else {
        stopSse();
      }
    }
    if (ok && outbox.size() > 0) {
      const { sent } = await syncService.replay();
      if (sent > 0) set({ lastSync: Date.now() });
    }
    set({ pendingCount: outbox.size() });

    // T15 — Rafraîchit l'état de la sync central (best-effort, silencieux).
    // Skip si offline OU pas authentifié (pas de token = login screen).
    if (ok && tokenStore.get()) {
      try {
        const s = await api<{
          nodeRole: string; nodeId: string; pendingCount: number;
          oldestPending: string | null; lagSeconds: number;
        }>("/api/sync/status", { timeoutMs: 2000 });
        set({
          centralSync: {
            nodeRole: s.nodeRole,
            pending: s.pendingCount,
            lagSeconds: s.lagSeconds,
            lastFetchAt: Date.now(),
          },
        });
      } catch {
        // Best-effort : si /api/sync/status échoue (endpoint absent en mode
        // standalone), on garde l'ancienne valeur. Le banner se débrouillera.
      }
    }
  },
}));

let started = false;
export function startBackendWatcher() {
  if (started) return () => {};
  started = true;

  // Backoff intelligent : ping souvent quand offline (5s), peu quand online (30s)
  let id: number | null = null;
  const schedule = () => {
    if (id) clearInterval(id);
    const interval = useBackend.getState().lanReachable ? 30_000 : 5_000;
    id = window.setInterval(async () => {
      await useBackend.getState().pulse();
      // Si l'état change → reschedule pour ajuster la fréquence
      schedule();
    }, interval) as unknown as number;
  };

  // Ping initial — fire-and-forget : `schedule` enchaîne la boucle au .then.
  // Le `.then` rend la promesse non-floating ; le `void` documente l'intention.
  void useBackend.getState().pulse().then(schedule);

  const onOnline = () => void useBackend.getState().pulse();
  window.addEventListener("online", onOnline);

  const onOutbox = () => useBackend.getState().refreshPending();
  window.addEventListener("outbox:change", onOutbox);

  // Quand l'URL API change (depuis Paramètres) → reset connexion
  const onApiChange = () => {
    stopSse();
    useBackend.setState({ lanReachable: false });
    void useBackend.getState().pulse();
  };
  window.addEventListener("api-url:change", onApiChange);

  // Re-pulse immédiatement quand l'onglet redevient visible (ex: retour après veille)
  const onVisible = () => {
    if (document.visibilityState === "visible") {
      void useBackend.getState().pulse().then(schedule);
    }
  };
  document.addEventListener("visibilitychange", onVisible);

  return () => {
    if (id) clearInterval(id);
    stopSse();
    window.removeEventListener("online", onOnline);
    window.removeEventListener("outbox:change", onOutbox);
    window.removeEventListener("api-url:change", onApiChange);
    document.removeEventListener("visibilitychange", onVisible);
    started = false;
  };
}

/**
 * Wrapper de compatibilité — délègue à syncService.submit (P1 anti-corruption layer).
 *
 * Conservé pour ne pas casser les 24 call-sites existants dans les stores. Toute
 * nouvelle mutation devrait appeler directement `syncService.submit({...})` qui
 * accepte aussi `sensitive: true` en override explicite.
 *
 * La logique (offline → outbox, online → api → fallback outbox ou refus sensible,
 * détection sensitive via regex /api/users) vit désormais dans syncService.ts.
 */
export async function pushMutation(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  body?: unknown,
  ref?: string,
) {
  return syncService.submit({ path, method, body, ref });
}
