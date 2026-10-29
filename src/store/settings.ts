import { create } from "zustand";
import { persist } from "zustand/middleware";
import { pushMutation } from "@/store/backend";

export type Currency = "AR" | "EUR";

export interface Settings {
  maxDiscountPercent: number;
  /** Points gagnés par Ariary dépensé. Ex: 0.0002 → 1 point par 5000 Ar. */
  pointsPerAr: number;
  /** Valeur en Ariary d'un point fidélité. Ex: 100 → 1 point = 100 Ar de réduction. */
  arPerPoint: number;
  shopName: string;
  currency: Currency; // devise d'AFFICHAGE par défaut. Stockage = AR.
}

interface SettingsState {
  settings: Settings;
  update: (patch: Partial<Settings>) => void;
  reset: () => void;
}

const DEFAULTS: Settings = {
  maxDiscountPercent: 10,
  // 5000 Ar dépensés → 1 point ; 1 point = 100 Ar (∼2% de retour).
  pointsPerAr: 0.0002,
  arPerPoint: 100,
  shopName: "KidzPOS",
  currency: "AR",
};

export const useSettings = create<SettingsState>()(
  persist(
    (set) => ({
      settings: DEFAULTS,
      update: (patch) => {
        set((s) => ({ settings: { ...s.settings, ...patch } }));
        const cur = useSettings.getState().settings;
        void pushMutation("/api/settings", "PUT", cur, "settings");
      },
      reset: () => {
        set({ settings: DEFAULTS });
        void pushMutation("/api/settings", "PUT", DEFAULTS, "settings");
      },
    }),
    {
      name: "kidzpos-settings",
      version: 3,
      migrate: (persisted: unknown, version) => {
        const state = persisted as Record<string, Record<string, unknown>>;
        if (state?.settings && !state.settings.currency) {
          state.settings.currency = "AR";
        }
        if (version < 3 && state?.settings) {
          // Migration EUR→AR : on n'essaie pas de convertir les anciennes valeurs
          // (qui étaient en EUR), on remet les defaults Ariary.
          state.settings.pointsPerAr = 0.0002;
          state.settings.arPerPoint = 100;
          delete state.settings.pointsPerEuro;
          delete state.settings.euroPerPoint;
        }
        return state;
      },
    }
  )
);
