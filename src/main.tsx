import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import './index.css'
import { startBackendWatcher } from "@/store/backend";
import { useExchange } from "@/store/exchange";
import { refreshAccessToken } from "@/lib/apiClient";
import { useAuth } from "@/store/auth";
import { startMetricsReporter } from "@/lib/metricsReporter";

// Thème initial
const stored = localStorage.getItem("kidzpos-theme");
if (stored === "light") document.documentElement.classList.add("light");

createRoot(document.getElementById("root")!).render(<App />);

// IMPORTANT : démarrer après le render pour laisser Zustand hydrater depuis localStorage.
// Sinon hydrateFromBackend() peut écraser les données offline avant qu'elles ne soient chargées.
queueMicrotask(async () => {
  // P2 : silent refresh au boot.
  //   - Si l'utilisateur a un cookie kidzpos_rt valide → on récupère un access
  //     token frais en mémoire, l'app reprend exactement où elle s'était arrêtée.
  //   - Si pas de cookie / cookie invalide → tokenStore reste null, les routes
  //     protégées redirigeront vers /login (cf RequireRole dans AppLayout).
  //   - Offline (navigator.onLine === false) : on saute le refresh, l'app
  //     fonctionne avec les passwords locaux chiffrés (mode offline auth).
  if (navigator.onLine) {
    const persistedUser = useAuth.getState().user;
    if (persistedUser) {
      // L'utilisateur était authentifié au dernier reload : tenter le refresh.
      // S'il échoue, on laisse useAuth.user en place ; les appels API échoueront
      // en 401 → apiClient redirigera via auth:session-expired.
      await refreshAccessToken();
    }
  }
  startBackendWatcher();
  startMetricsReporter();
  if (navigator.onLine) {
    // fire-and-forget : refresh du taux EUR/AR est best-effort
    void useExchange.getState().refresh(true);
  }
});

window.addEventListener("online", () => void useExchange.getState().refresh(true));
