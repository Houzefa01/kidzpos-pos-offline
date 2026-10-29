import { getApiUrl } from "./apiConfig";

/**
 * Stockage de l'access token JWT.
 *
 * EN MÉMOIRE UNIQUEMENT (P2 — sécurité). Plus de localStorage : un attaquant
 * XSS ne peut pas exfiltrer le token via DOM. La persistance entre reloads
 * est assurée par le cookie httpOnly `kidzpos_rt` (refresh token rotatif) :
 * au boot, `bootstrapAuth()` (cf main.tsx / store/auth.ts) appelle
 * `POST /api/auth/refresh` qui repose silencieusement un access token frais
 * si le cookie est valide.
 *
 * Conséquence : si l'utilisateur reload pendant un long calcul backend, un
 * refresh silencieux a lieu — invisible côté UX.
 */
let accessToken: string | null = null;

function isJwtExpired(token: string): boolean {
  try {
    const b64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(atob(b64)) as { exp?: number };
    return typeof payload.exp === "number" && payload.exp * 1000 < Date.now();
  } catch {
    return false;
  }
}

export const tokenStore = {
  get: (): string | null => {
    if (accessToken && isJwtExpired(accessToken)) {
      accessToken = null;
    }
    return accessToken;
  },
  set: (t: string | null) => {
    accessToken = t;
  },
};

export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, body: unknown, msg?: string) {
    super(msg ?? (body as Record<string, string>)?.error ?? `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export interface ApiOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  timeoutMs?: number;
  noAuth?: boolean;
  /** Headers additionnels (ex: If-Match). */
  headers?: Record<string, string>;
  /** Interne : marqueur pour empêcher la boucle de retry après refresh. */
  _retried?: boolean;
}

// ────────────────────────────────────────────────────────────────────────────
// Single-flight refresh : un seul POST /api/auth/refresh à la fois, même si N
// requêtes 401 arrivent simultanément. Tous les callers attendent la même
// Promise et obtiennent le même nouveau token (ou null en cas d'échec).
// ────────────────────────────────────────────────────────────────────────────

let refreshInFlight: Promise<string | null> | null = null;

/**
 * Tente un refresh silencieux. Retourne le nouvel access token ou null.
 * Le cookie httpOnly `kidzpos_rt` est attaché automatiquement grâce à
 * credentials: "include".
 *
 * Exposé pour bootstrapAuth() au démarrage de l'app, et utilisé en interne
 * par api() sur 401.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      const res = await fetch(`${getApiUrl()}/api/auth/refresh`, {
        method: "POST",
        credentials: "include",
      });
      if (!res.ok) return null;
      const data = (await res.json()) as { accessToken?: string };
      const next = data.accessToken ?? null;
      tokenStore.set(next);
      return next;
    } catch {
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

export async function api<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const token = opts.noAuth ? null : tokenStore.get();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), opts.timeoutMs ?? 4000);
  try {
    const res = await fetch(`${getApiUrl()}${path}`, {
      method: opts.method ?? "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(opts.headers ?? {}),
      },
      // credentials: include → le cookie httpOnly refresh est carrié sur /auth/refresh.
      // CORS doit allowCredentials=true côté backend (déjà le cas pour origines explicites).
      credentials: "include",
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: ctrl.signal,
    });

    // 401 + on a un access token → tenter un refresh silencieux + retry une fois.
    // _retried garde-fou : pas de boucle si le retry lui-même 401.
    if (res.status === 401 && !opts.noAuth && !opts._retried) {
      const newToken = await refreshAccessToken();
      if (newToken) {
        return api<T>(path, { ...opts, _retried: true });
      }
      // Refresh KO → session morte. Notifier l'UI pour rediriger vers /login.
      tokenStore.set(null);
      window.dispatchEvent(new CustomEvent("auth:session-expired"));
    }

    const text = await res.text();
    const data = text ? safeJson(text) : null;
    if (!res.ok) {
      throw new ApiError(res.status, data);
    }
    return data as T;
  } finally {
    clearTimeout(timer);
  }
}

function safeJson(t: string): unknown {
  try { return JSON.parse(t); } catch { return t; }
}

// ────────────────────────────────────────────────────────────────────────────
// PR http-hardening — Cache HTTP via If-None-Match / 304 Not Modified.
//
// Helper séparé (n'altère pas `api<T>` ni ses 24 call-sites) pour les GET sur
// lesquels le backend expose un ETag (cf ProductController.list). Le caller :
//   1. Conserve l'ETag retourné entre deux appels
//   2. Le repasse en `ifNoneMatch` au call suivant
//   3. Si `notModified === true` → garde son état local, économise le payload
//
// Auth : reprend la même logique que api<T> (Bearer + refresh silencieux).
// Pas de retry _retried distinct : le refresh-on-401 est partagé via le
// single-flight `refreshAccessToken()`.
// ────────────────────────────────────────────────────────────────────────────

export interface EtagResult<T> {
  /** True si le serveur a répondu 304 : `data` est null, le caller garde son état. */
  notModified: boolean;
  /** ETag de la réponse (présent en 200 ET en 304, cf RFC 7232 §4.1). */
  etag: string | null;
  /** Payload parsé. Null si 304 ou si la réponse est vide. */
  data: T | null;
}

export async function apiWithEtag<T = unknown>(
  path: string,
  ifNoneMatch?: string,
  opts: Omit<ApiOptions, "method" | "body"> = {},
): Promise<EtagResult<T>> {
  const token = opts.noAuth ? null : tokenStore.get();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), opts.timeoutMs ?? 4000);
  try {
    const res = await fetch(`${getApiUrl()}${path}`, {
      method: "GET",
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(ifNoneMatch ? { "If-None-Match": ifNoneMatch } : {}),
        ...(opts.headers ?? {}),
      },
      credentials: "include",
      signal: ctrl.signal,
    });

    // 401 → refresh silencieux + retry une fois (même contrat que api<T>).
    if (res.status === 401 && !opts.noAuth && !opts._retried) {
      const newToken = await refreshAccessToken();
      if (newToken) {
        return apiWithEtag<T>(path, ifNoneMatch, { ...opts, _retried: true });
      }
      tokenStore.set(null);
      window.dispatchEvent(new CustomEvent("auth:session-expired"));
    }

    const etag = res.headers.get("ETag");
    if (res.status === 304) {
      return { notModified: true, etag, data: null };
    }
    const text = await res.text();
    const data = text ? (safeJson(text) as T) : null;
    if (!res.ok) {
      throw new ApiError(res.status, data);
    }
    return { notModified: false, etag, data };
  } finally {
    clearTimeout(timer);
  }
}

/** Ping rapide pour détecter si le backend répond. Silencieux. */
export async function pingBackend(timeoutMs = 1500): Promise<boolean> {
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const res = await fetch(`${getApiUrl()}/actuator/health`, {
      signal: ctrl.signal,
      mode: "cors",
    }).catch(() => null);
    clearTimeout(timer);
    return !!res && res.ok;
  } catch {
    return false;
  }
}
