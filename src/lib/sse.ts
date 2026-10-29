import { api } from "@/lib/apiClient";
import { getApiUrl } from "@/lib/apiConfig";
import { hydrateFromBackend } from "@/lib/syncBackend";
import { useBackend } from "@/store/backend";

let es: EventSource | null = null;
let retryTimer: number | null = null;
let retryDelay = 2000;
let hydrateTimer: number | null = null;
let lastHeartbeatAt = Date.now();
let heartbeatMonitor: number | null = null;
// In-flight guard : la phase async (POST /api/events/auth) crée une fenêtre
// pendant laquelle `es` est encore null. Sans flag, deux appels concurrents
// (syncBackend + backend.pulse au boot) ouvrent chacun leur connexion.
let starting = false;

function scheduleHydrate() {
  if (hydrateTimer) return;
  hydrateTimer = window.setTimeout(() => {
    hydrateTimer = null;
    hydrateFromBackend().catch(() => {
      // En cas d'échec, prochain event SSE relancera (hydrateTimer déjà null)
    });
  }, 400);
}

function onChange() {
  lastHeartbeatAt = Date.now();
  scheduleHydrate();
}

function onPing() {
  lastHeartbeatAt = Date.now();
  retryDelay = 2000; // Reset le backoff puisque la connexion est vivante
}

function startHeartbeatMonitor() {
  if (heartbeatMonitor) clearInterval(heartbeatMonitor);
  heartbeatMonitor = window.setInterval(() => {
    // Pas de message depuis 90s → connexion zombie → reconnecter
    // (le backend ping toutes les 30s, on tolère 3 manqués)
    if (Date.now() - lastHeartbeatAt > 90_000) {
      stopSse();
      retryTimer = window.setTimeout(() => { void startSse(); }, 1000);
    }
  }, 30_000);
}

export async function startSse() {
  if (starting) return;
  if (es && (es.readyState === EventSource.OPEN || es.readyState === EventSource.CONNECTING)) return;
  starting = true;
  try {
    stopSse();
    // M5 : EventSource ne peut pas envoyer Authorization. On demande un token
    // single-use (60s) via POST /api/events/auth, qu'on passe en query param.
    const auth = await api<{ token: string }>("/api/events/auth", { method: "POST" });
    const url = `${getApiUrl()}/api/events/stream?token=${encodeURIComponent(auth.token)}`;
    es = new EventSource(url);
    lastHeartbeatAt = Date.now();
    es.onopen = () => {
      retryDelay = 2000;
      lastHeartbeatAt = Date.now();
      useBackend.setState({ lanReachable: true });
      startHeartbeatMonitor();
    };
    es.addEventListener("change", onChange);
    es.addEventListener("ping", onPing);
    es.addEventListener("hello", onPing);
    es.onerror = () => {
      stopSse();
      retryDelay = Math.min(retryDelay * 1.5, 30_000);
      retryTimer = window.setTimeout(() => { void startSse(); }, retryDelay);
    };
  } catch (_e) {
    es = null;
    retryDelay = Math.min(retryDelay * 1.5, 30_000);
    retryTimer = window.setTimeout(() => { void startSse(); }, retryDelay);
  } finally {
    starting = false;
  }
}

export function stopSse() {
  if (es) {
    try {
      es.removeEventListener("change", onChange);
      es.removeEventListener("ping", onPing);
      es.removeEventListener("hello", onPing);
      es.close();
    } catch (_e) { /* close jamais throw */ }
    es = null;
  }
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
  if (hydrateTimer) { clearTimeout(hydrateTimer); hydrateTimer = null; }
  if (heartbeatMonitor) { clearInterval(heartbeatMonitor); heartbeatMonitor = null; }
}

export function isSseOpen(): boolean {
  return es !== null && es.readyState === EventSource.OPEN;
}
