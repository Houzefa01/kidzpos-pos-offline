import { create } from "zustand";
import { persist } from "zustand/middleware";
import { newId } from "@/lib/ids";

/**
 * Mutations rejetées par le backend (4xx) au moment du flush outbox.
 * Persisté pour qu'un caissier ne perde pas la trace après reload.
 * Cap à 100 entrées — au-delà, les plus anciennes sont évincées.
 *
 * Affiché dans OfflineBanner et la page Sales (badge + détail).
 * L'opérateur peut acquitter (remove) ou tout effacer (clear).
 */
export interface FailedReplay {
  id: string;
  ts: number;
  path: string;
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  ref?: string;
  status: number;
  error: string;
}

interface FailedReplaysState {
  failures: FailedReplay[];
  add: (f: Omit<FailedReplay, "id">) => void;
  remove: (id: string) => void;
  clear: () => void;
}

export const useFailedReplays = create<FailedReplaysState>()(
  persist(
    (set) => ({
      failures: [],
      add: (f) =>
        set((s) => ({
          failures: [
            { ...f, id: newId("fr-") },
            ...s.failures,
          ].slice(0, 100),
        })),
      remove: (id) => set((s) => ({ failures: s.failures.filter((f) => f.id !== id) })),
      clear: () => set({ failures: [] }),
    }),
    { name: "kidzpos-failed-replays", version: 1 },
  ),
);
