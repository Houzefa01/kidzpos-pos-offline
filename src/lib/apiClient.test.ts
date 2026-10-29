import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, refreshAccessToken, tokenStore } from "./apiClient";

/**
 * Tests P2 — interceptor 401 + single-flight refresh.
 *
 * Stratégie : on stub global.fetch et vi.fakeTime-free puisque la logique est
 * orchestrée par les Promises, pas par des timers. Chaque test décrit la
 * séquence des réponses HTTP attendues du serveur.
 *
 * Invariants vérifiés :
 *  1. Une requête authentifiée + 200 = succès direct
 *  2. 401 + refresh OK → retry une fois (request totale: 3 = orig 401 / refresh 200 / retry 200)
 *  3. 401 + refresh KO → ApiError + auth:session-expired event + tokenStore.set(null)
 *  4. Single-flight : N requêtes concurrentes qui 401 → 1 seul appel /refresh
 *  5. Retry échoue lui aussi 401 → pas de boucle infinie (_retried garde)
 */

const ORIGINAL_FETCH = globalThis.fetch;

function makeResponse(status: number, body: unknown = {}): Response {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  return new Response(text, { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  tokenStore.set("initial-access-token");
  globalThis.fetch = vi.fn();
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  tokenStore.set(null);
  vi.restoreAllMocks();
});

describe("apiClient.api — 200 direct", () => {
  it("requête authentifiée → réussite, 1 seul fetch", async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(makeResponse(200, { ok: true }));

    const r = await api<{ ok: boolean }>("/api/products");
    expect(r).toEqual({ ok: true });
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    // Header Authorization présent
    const init = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer initial-access-token");
    expect(init.credentials).toBe("include");
  });
});

describe("apiClient.api — 401 + refresh OK", () => {
  it("refresh silencieux + retry une fois", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock
      .mockResolvedValueOnce(makeResponse(401))                            // 1. original GET → 401
      .mockResolvedValueOnce(makeResponse(200, { accessToken: "fresh" }))  // 2. POST /refresh
      .mockResolvedValueOnce(makeResponse(200, { ok: true }));             // 3. retry → 200

    const r = await api<{ ok: boolean }>("/api/products");

    expect(r).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1][0]).toContain("/api/auth/refresh");
    // Retry porte le NOUVEAU token
    const retryInit = fetchMock.mock.calls[2][1];
    expect((retryInit.headers as Record<string, string>).Authorization).toBe("Bearer fresh");
    expect(tokenStore.get()).toBe("fresh");
  });
});

describe("apiClient.api — 401 + refresh KO", () => {
  it("session-expired + tokenStore vidé + ApiError 401", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock
      .mockResolvedValueOnce(makeResponse(401))
      .mockResolvedValueOnce(makeResponse(401));  // refresh refusé

    const dispatch = vi.spyOn(window, "dispatchEvent");

    await expect(api("/api/products")).rejects.toMatchObject({ status: 401 });
    expect(tokenStore.get()).toBeNull();
    // Cherche un CustomEvent "auth:session-expired" parmi tous les dispatched
    const fired = dispatch.mock.calls.find(([e]) => (e as CustomEvent).type === "auth:session-expired");
    expect(fired).toBeDefined();
  });

  it("ApiError est levée avec le status 401", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock
      .mockResolvedValueOnce(makeResponse(401))
      .mockResolvedValueOnce(makeResponse(401));

    try {
      await api("/api/products");
      throw new Error("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      expect((e as ApiError).status).toBe(401);
    }
  });
});

describe("apiClient — single-flight refresh", () => {
  it("N requêtes concurrentes qui 401 → 1 seul POST /refresh", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    // Toutes les requêtes API initiales → 401. Le refresh → 200. Tous les retries → 200.
    // L'ordre exact des appels concurrents est indéterministe ; on contrôle par URL.
    fetchMock.mockImplementation(async (url: string | URL) => {
      const u = String(url);
      if (u.includes("/api/auth/refresh")) {
        // Petit délai pour s'assurer que les autres requêtes attendent la même promise
        await new Promise((r) => setTimeout(r, 10));
        return makeResponse(200, { accessToken: "shared-fresh" });
      }
      // Premier passage : pas de _retried marker, on renvoie 401
      // (impossible de différencier orig/retry depuis fetch — on triche en utilisant
      // le token en header)
      // Hack : on renvoie 200 si Authorization = "Bearer shared-fresh", sinon 401
      return makeResponse(200, { ok: true });
    });
    // Plus simple : utiliser un compteur par-URL
    const initialPerUrl: Record<string, number> = {};
    fetchMock.mockImplementation(async (url: string | URL, init?: RequestInit) => {
      const u = String(url);
      if (u.includes("/api/auth/refresh")) {
        await new Promise((r) => setTimeout(r, 15));
        return makeResponse(200, { accessToken: "shared-fresh" });
      }
      const auth = ((init?.headers ?? {}) as Record<string, string>).Authorization;
      if (auth === "Bearer shared-fresh") return makeResponse(200, { ok: true, url: u });
      // 1er passage avec ancien token → 401
      initialPerUrl[u] = (initialPerUrl[u] ?? 0) + 1;
      return makeResponse(401);
    });

    const paths = ["/api/products", "/api/sales", "/api/customers", "/api/settings"];
    const results = await Promise.all(paths.map((p) => api<{ ok: boolean; url: string }>(p)));

    results.forEach((r) => expect(r.ok).toBe(true));

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes("/api/auth/refresh"));
    expect(refreshCalls).toHaveLength(1); // SINGLE-FLIGHT
    expect(tokenStore.get()).toBe("shared-fresh");
  });
});

describe("apiClient — pas de boucle si retry échoue aussi", () => {
  it("retry 401 → ApiError, pas de 3e tentative", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock
      .mockResolvedValueOnce(makeResponse(401))                              // orig
      .mockResolvedValueOnce(makeResponse(200, { accessToken: "new" }))     // refresh OK
      .mockResolvedValueOnce(makeResponse(401));                             // retry 401 → STOP

    await expect(api("/api/products")).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});

describe("refreshAccessToken()", () => {
  it("retourne le nouveau access token au 200", async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      makeResponse(200, { accessToken: "boot-fresh" })
    );

    const t = await refreshAccessToken();
    expect(t).toBe("boot-fresh");
    expect(tokenStore.get()).toBe("boot-fresh");
  });

  it("retourne null au 401 (pas de cookie / révoqué)", async () => {
    tokenStore.set(null);
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(makeResponse(401));

    const t = await refreshAccessToken();
    expect(t).toBeNull();
    expect(tokenStore.get()).toBeNull();
  });

  it("retourne null si fetch throw (réseau HS)", async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError("network"));

    const t = await refreshAccessToken();
    expect(t).toBeNull();
  });
});
