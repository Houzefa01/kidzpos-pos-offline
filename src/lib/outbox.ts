// File d'attente offline : quand le backend LAN n'est pas joignable,
// on enfile les mutations et on les rejoue dès qu'il revient.
// Stocké en localStorage pour survivre à un reload.
//
// Module bas-niveau : pure persistance. Toute la logique de submit/replay vit
// désormais dans @/lib/syncService (P1 anti-corruption layer).
//
// P5 — Champs additifs nextEligibleAt + failureCount pour le per-item backoff.
// update() permet de modifier en place un entry sans changer son id/ts (préserve
// l'ordre FIFO d'enqueue, donc l'âge initial dans le tri du replay).

import { z } from "zod";
import { newId } from "@/lib/ids";

export interface OutboxEntry {
  id: string;
  ts: number;
  path: string;
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Identifiant logique (ex: "sale:sale-123") pour dédoublonnage UI. */
  ref?: string;
  retries: number;
  lastError?: string;
  /** P5 — timestamp ms à partir duquel cet item est de nouveau éligible au replay.
   *  Non défini → éligible immédiatement (cas nominal first-try). */
  nextEligibleAt?: number;
  /** P5 — compteur d'échecs réseau/5xx isolés à cet item.
   *  Au-delà de MAX_PER_ITEM_ATTEMPTS, l'item est dead-lettered (failedReplays). */
  failureCount?: number;
}

const OutboxEntrySchema = z.object({
  id: z.string(),
  ts: z.number(),
  path: z.string(),
  method: z.enum(["POST", "PUT", "PATCH", "DELETE"]),
  body: z.unknown().optional(),
  ref: z.string().optional(),
  retries: z.number(),
  lastError: z.string().optional(),
  nextEligibleAt: z.number().optional(),
  failureCount: z.number().optional(),
});

const KEY = "kidzpos-outbox";
/**
 * P5 — Plafond augmenté à 12000 (cf P5 SOFT_LIMIT 3000 / HARD_LIMIT 10000).
 * Reste un garde-fou ultime contre la saturation localStorage : si syncService
 * laisse passer la file au-delà du HARD limit (bug de la logique de pause),
 * outbox évince les plus récentes (slice(-MAX_ENTRIES)) plutôt que de tout perdre.
 */
const MAX_ENTRIES = 12000;

function read(): OutboxEntry[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? "[]");
    return z.array(OutboxEntrySchema).parse(raw);
  } catch { return []; }
}
function write(list: OutboxEntry[]) {
  localStorage.setItem(KEY, JSON.stringify(list));
  window.dispatchEvent(new CustomEvent("outbox:change"));
}

export const outbox = {
  list: () => read(),
  size: () => read().length,
  enqueue(entry: Omit<OutboxEntry, "id" | "ts" | "retries">) {
    let all = read();
    // I6 : dédoublonnage par `ref` pour PUT et DELETE — un nouvel update/delete remplace
    // les précédents pour la même ressource (évite N PUT redondants pour un seul produit).
    // POST n'est PAS dédupliqué : chaque vente / création client est une mutation distincte.
    if (entry.ref && (entry.method === "PUT" || entry.method === "DELETE")) {
      all = all.filter((e) => !(e.ref === entry.ref && (e.method === "PUT" || e.method === "DELETE")));
    }
    all.push({
      ...entry,
      // P2.3 : UUID au lieu de Date.now()-random4 (collision possible si > 1
      // enqueue/ms dans un bulk synchrone).
      id: newId("ob-"),
      ts: Date.now(),  // timestamp d'enqueue (sémantique horloge, pas un ID)
      retries: 0,
    });
    // I6 : FIFO bounded — on garde les MAX_ENTRIES plus récentes.
    if (all.length > MAX_ENTRIES) {
      all = all.slice(-MAX_ENTRIES);
    }
    write(all);
  },
  remove(id: string) {
    write(read().filter((e) => e.id !== id));
  },
  /** P5 — Patch in place. Préserve id/ts (et donc l'ordre FIFO d'origine).
   *  No-op silencieux si l'id n'existe pas (race avec un remove concurrent). */
  update(id: string, patch: Partial<Omit<OutboxEntry, "id" | "ts">>): void {
    const all = read();
    let changed = false;
    const next = all.map((e) => {
      if (e.id !== id) return e;
      changed = true;
      return { ...e, ...patch };
    });
    if (changed) write(next);
  },
  clear() {
    write([]);
  },
};
