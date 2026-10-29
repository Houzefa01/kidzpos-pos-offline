import { create } from "zustand";
import { persist } from "zustand/middleware";
import { api, tokenStore } from "@/lib/apiClient";
import { toast } from "sonner";

interface ExchangeState {
  rate: number;             // EUR -> AR
  fetchedAt: number | null;
  source: "default" | "live" | "manual";
  refresh: (silent?: boolean) => Promise<void>;
  setManual: (rate: number) => void;
}

const DEFAULT_RATE = 4900; // valeur de secours

export const useExchange = create<ExchangeState>()(
  persist(
    (set) => ({
      rate: DEFAULT_RATE,
      fetchedAt: null,
      source: "default",
      refresh: async (silent = false) => {
        // 1) Tenter le backend SI authentifié — sinon /api/exchange/refresh
        // renvoie 403 (endpoint sous auth). Cas typique : refresh au boot avant
        // login. On saute directement au fallback navigateur dans ce cas.
        let backendLive = false;
        if (tokenStore.get()) {
          try {
            const r = await api<{ rate: number; fetchedAt: string | null; source: string }>("/api/exchange/refresh", {
              method: "POST", timeoutMs: 3000,
            });
            if (r.source === "live") {
              set({ rate: r.rate, fetchedAt: Date.now(), source: "live" });
              if (!silent) toast.success(`Taux mis à jour : 1 € = ${Math.round(r.rate)} Ar`);
              return;
            }
            // Backend a répondu mais avec cached/default — internet indispo côté backend
            // → tenter depuis le navigateur
            backendLive = false;
          } catch (_) { /* on essaie le fallback navigateur */ }
        }

        // 2) Si le backend n'a pas eu de taux frais, tenter depuis le navigateur
        // I7 : open.er-api.com (gratuit sans clé) au lieu de api.exchangerate.host (paywall depuis 2024)
        if (navigator.onLine && !backendLive) {
          try {
            const ctrl = new AbortController();
            const t = setTimeout(() => ctrl.abort(), 6000);
            const res = await fetch("https://open.er-api.com/v6/latest/EUR", { signal: ctrl.signal });
            clearTimeout(t);
            if (!res.ok) throw new Error("HTTP " + res.status);
            const j = await res.json();
            const rate = j?.rates?.MGA;
            if (typeof rate !== "number" || rate <= 0) throw new Error("Taux invalide");
            set({ rate, fetchedAt: Date.now(), source: "live" });
            if (!silent) toast.success(`Taux mis à jour : 1 € = ${Math.round(rate)} Ar`);
            return;
          } catch (e: unknown) {
            if (!silent) toast.error("Impossible de récupérer le taux : " + (e instanceof Error ? e.message : "erreur réseau"));
          }
        } else if (!navigator.onLine) {
          if (!silent) toast.error("Pas d'internet — taux par défaut conservé");
        }
      },
      setManual: (rate) => set({ rate, fetchedAt: Date.now(), source: "manual" }),
    }),
    { name: "kidzpos-exchange" }
  )
);
