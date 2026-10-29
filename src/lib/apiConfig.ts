// Configuration API — pointe vers le backend Spring Boot du magasin.
// Priorité :
// 1. localStorage "kidzpos-api-url" (réglable depuis l'UI Paramètres)
// 2. VITE_API_URL au build
// 3. Fenêtre de fallback : http://<host actuel>:8080
function detectDefault(): string {
  if (typeof window === "undefined") return "http://localhost:8080";
  const host = window.location.hostname || "localhost";
  // Si on est sur un sous-domaine .lovable.app (preview), le ping local échouera
  // mais ça ne casse rien : tout reste fonctionnel offline-first.
  return `http://${host}:8080`;
}

export function getApiUrl(): string {
  // 1. Variable d'environnement au build (priorité absolue)
  const env = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, "");
  if (env) return env;

  // 2. localStorage uniquement en dev ET uniquement si on est sur localhost
  //    (sur une autre machine, detectDefault() est toujours correct)
  if (import.meta.env.DEV && typeof window !== "undefined") {
    const isLocal = ["localhost", "127.0.0.1"].includes(window.location.hostname);
    if (isLocal) {
      const stored = localStorage.getItem("kidzpos-api-url");
      if (stored) return stored.replace(/\/$/, "");
    }
  }

  // 3. Détection automatique : même hostname, port 8080
  return detectDefault();
}

export function setApiUrl(url: string) {
  const clean = url.trim().replace(/\/$/, "");
  if (clean) localStorage.setItem("kidzpos-api-url", clean);
  else localStorage.removeItem("kidzpos-api-url");
  window.dispatchEvent(new CustomEvent("api-url:change"));
}

// Compat : import { API_URL } existant
export const API_URL = getApiUrl();
