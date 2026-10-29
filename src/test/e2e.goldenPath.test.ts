import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { useSales } from "@/store/sales";
import { useSettings } from "@/store/settings";
import { useCustomers } from "@/store/customers";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { outbox } from "@/lib/outbox";
import { tokenStore } from "@/lib/apiClient";
import { syncService, __resetReplayStateForTests } from "@/lib/syncService";

/**
 * P3.3 — Test E2E (runtime-level) du golden path KidzPOS :
 *
 *    Login → Checkout (CASH) → Sync vers backend
 *
 * Niveau : runtime application complet (vrais stores Zustand, vrais services
 * apiClient/syncService/outbox), avec `fetch` mocké au niveau global pour
 * simuler le backend. Pas de browser, pas de docker, pas de Postgres requis
 * → exécutable en CI sur n'importe quelle machine.
 *
 * Pourquoi pas Playwright : nécessite browser binary + un backend Spring qui
 * tourne + Postgres → trop fragile pour la valeur ajoutée vs ce test runtime.
 * Le test ici exerce le MÊME code que celui qui tourne en prod (apiClient,
 * syncService, outbox, stores) ; seul le transport HTTP est mocké.
 *
 * Couverture :
 *   - Login backend (POST /api/auth/login) → access token + user state
 *   - Online → addSale → POST /api/sales/checkout immédiat, outbox vide
 *   - Offline → addSale → enqueue dans l'outbox, pas de fetch
 *   - Retour online → syncService.replay() flush l'outbox vers le backend
 */

interface FetchCall {
  url: string;
  method: string;
  body?: unknown;
  authHeader?: string;
}

const calls: FetchCall[] = [];

function makeMockFetch() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url, method, body, authHeader: headers.Authorization });

    // Pathname extraction robuste (gère les query params type ?storeId=s1
    // ajoutés par syncBackend pour la politique GET cross-store P4).
    const path = (() => {
      try { return new URL(url, "http://x").pathname; }
      catch { return url; }
    })();
    const pathEnds = (p: string) => path.endsWith(p);

    // ──── AUTH ──────────────────────────────────────────────────────────────
    if (url.endsWith("/api/auth/login") && method === "POST") {
      return jsonResponse(200, {
        token: "fake-access-token-xyz",
        user: {
          id: "u-1",
          name: "Sarah",
          email: "sarah@kidzpos.com",
          role: "EMPLOYEE",
          storeId: "s1",
          active: true,
        },
      });
    }
    if (url.endsWith("/api/auth/refresh") && method === "POST") {
      return jsonResponse(200, { accessToken: "fake-access-token-refreshed" });
    }
    if (url.endsWith("/api/auth/logout") && method === "POST") {
      return new Response(null, { status: 204 });
    }

    // ──── HYDRATE (vides — on n'a pas besoin de données seed pour ce test) ──
    if (method === "GET" && (
      pathEnds("/api/stores")
      || pathEnds("/api/products")
      || pathEnds("/api/sales")
      || pathEnds("/api/customers")
      || pathEnds("/api/users")
    )) {
      return jsonResponse(200, []);
    }
    if (pathEnds("/api/settings") && method === "GET") {
      return jsonResponse(200, {
        maxDiscountPercent: 10,
        pointsPerAr: 0.0002,
        arPerPoint: 100,
        shopName: "KidzPOS",
        currency: "AR",
      });
    }
    if (url.includes("/actuator/health")) {
      return jsonResponse(200, { status: "UP" });
    }
    if (pathEnds("/api/events/auth") && method === "POST") {
      return jsonResponse(200, { token: "fake-sse-token" });
    }

    // ──── CHECKOUT ──────────────────────────────────────────────────────────
    if (url.endsWith("/api/sales/checkout") && method === "POST") {
      const b = body as { clientSaleId: string; storeId: string; items: Array<{ productId: string; quantity: number }> };
      return jsonResponse(200, {
        id: b.clientSaleId,
        seq: 1,
        storeId: b.storeId,
        userId: "u-1",
        userName: "Sarah",
        items: b.items.map((i) => ({ productId: i.productId, name: "X", quantity: i.quantity, price: 1000 })),
        subtotal: 1000,
        discount: 0,
        total: 1000,
        date: new Date().toISOString(),
        pointsEarned: 0,
        pointsRedeemed: 0,
        paymentMode: "CASH",
        amountPaid: 1000,
        change: 0,
        currency: "AR",
      });
    }

    return new Response("not mocked: " + url, { status: 404 });
  });
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function resetAll() {
  calls.length = 0;
  outbox.clear();
  useFailedReplays.setState({ failures: [] });
  tokenStore.set(null);
  __resetReplayStateForTests();
  useAuth.setState({ user: null, users: [], passwords: {}, attempts: {} });
  useData.setState({ stores: [], products: [], moves: [], parked: [] });
  useSales.setState({ sales: [], saleSeq: {} });
  useCustomers.setState({ customers: [] });
  useSettings.setState({
    settings: {
      maxDiscountPercent: 10,
      pointsPerAr: 0.0002,
      arPerPoint: 100,
      shopName: "KidzPOS",
      currency: "AR",
    },
  });
  useBackend.setState({ lanReachable: false, pendingCount: 0, lastSync: null });
}

describe("E2E golden path — login → checkout → sync", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", makeMockFetch());
    resetAll();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    resetAll();
  });

  it("login authentifie l'utilisateur et pose l'access token en mémoire", async () => {
    const res = await useAuth.getState().login("sarah@kidzpos.com", "secret");

    expect(res.ok).toBe(true);
    expect(res.user?.id).toBe("u-1");
    expect(res.user?.role).toBe("EMPLOYEE");
    expect(useAuth.getState().user?.id).toBe("u-1");
    expect(tokenStore.get()).toBe("fake-access-token-xyz");

    // L'appel HTTP a bien été fait avec le bon body
    const loginCall = calls.find((c) => c.url.endsWith("/api/auth/login"));
    expect(loginCall).toBeDefined();
    expect(loginCall?.method).toBe("POST");
    expect(loginCall?.body).toEqual({ email: "sarah@kidzpos.com", password: "secret" });
  });

  it("checkout ONLINE pousse directement au backend, outbox reste vide", async () => {
    // Pré-requis : login + lanReachable + seed produit local pour le décrément stock
    await useAuth.getState().login("sarah@kidzpos.com", "secret");
    useBackend.setState({ lanReachable: true });
    useData.setState({
      products: [{
        id: "p-1", name: "Ours", price: 1000, stock: 5,
        storeId: "s1", sku: "K1", createdAt: new Date().toISOString(),
      }],
    });

    const sale = useSales.getState().addSale({
      storeId: "s1",
      userId: "u-1",
      userName: "Sarah",
      items: [{ productId: "p-1", name: "Ours", quantity: 1, price: 1000 }],
      subtotal: 1000, discount: 0, total: 1000,
      pointsEarned: 0, pointsRedeemed: 0,
      paymentMode: "CASH", amountPaid: 1000, change: 0,
      currency: "AR",
    });

    // Vente créée en local immédiatement (UI réactive)
    expect(useSales.getState().sales).toHaveLength(1);
    expect(sale.id).toMatch(/^sale-/);

    // Laisser microtasks faire leur travail (api() est async)
    await vi.waitFor(() => {
      const checkoutCall = calls.find((c) => c.url.endsWith("/api/sales/checkout"));
      expect(checkoutCall).toBeDefined();
    });

    const checkoutCall = calls.find((c) => c.url.endsWith("/api/sales/checkout"))!;
    expect(checkoutCall.method).toBe("POST");
    expect(checkoutCall.authHeader).toBe("Bearer fake-access-token-xyz");
    const body = checkoutCall.body as { clientSaleId: string; storeId: string };
    expect(body.clientSaleId).toBe(sale.id);  // idempotence I8
    expect(body.storeId).toBe("s1");

    // Outbox vide : push immédiat OK
    expect(outbox.size()).toBe(0);
  });

  it("checkout OFFLINE enqueue dans l'outbox sans appeler fetch", async () => {
    await useAuth.getState().login("sarah@kidzpos.com", "secret");
    // Reset des calls login pour observer seulement la suite
    calls.length = 0;
    // Offline
    useBackend.setState({ lanReachable: false });
    useData.setState({
      products: [{
        id: "p-1", name: "Ours", price: 1000, stock: 5,
        storeId: "s1", sku: "K1", createdAt: new Date().toISOString(),
      }],
    });

    const sale = useSales.getState().addSale({
      storeId: "s1", userId: "u-1", userName: "Sarah",
      items: [{ productId: "p-1", name: "Ours", quantity: 1, price: 1000 }],
      subtotal: 1000, discount: 0, total: 1000,
      pointsEarned: 0, pointsRedeemed: 0,
      paymentMode: "CASH", amountPaid: 1000, change: 0,
      currency: "AR",
    });

    // Laisser le temps à syncService de prendre la décision
    await Promise.resolve();
    await Promise.resolve();

    // Aucun fetch checkout : offline → directement outbox
    expect(calls.find((c) => c.url.endsWith("/api/sales/checkout"))).toBeUndefined();

    // Outbox contient la mutation avec clientSaleId pour idempotence au replay
    const queue = outbox.list();
    expect(queue).toHaveLength(1);
    expect(queue[0].path).toBe("/api/sales/checkout");
    expect(queue[0].method).toBe("POST");
    expect((queue[0].body as { clientSaleId: string }).clientSaleId).toBe(sale.id);
  });

  it("flow complet : login + offline checkout + retour online + replay flush l'outbox", async () => {
    // 1. Login
    await useAuth.getState().login("sarah@kidzpos.com", "secret");
    calls.length = 0;

    // 2. Offline : checkout
    useBackend.setState({ lanReachable: false });
    useData.setState({
      products: [{
        id: "p-1", name: "Ours", price: 1000, stock: 5,
        storeId: "s1", sku: "K1", createdAt: new Date().toISOString(),
      }],
    });
    const sale = useSales.getState().addSale({
      storeId: "s1", userId: "u-1", userName: "Sarah",
      items: [{ productId: "p-1", name: "Ours", quantity: 1, price: 1000 }],
      subtotal: 1000, discount: 0, total: 1000,
      pointsEarned: 0, pointsRedeemed: 0,
      paymentMode: "CASH", amountPaid: 1000, change: 0,
      currency: "AR",
    });
    await Promise.resolve();
    expect(outbox.size()).toBe(1);
    expect(calls).toHaveLength(0);  // rien envoyé

    // 3. Retour online → replay
    useBackend.setState({ lanReachable: true });
    const replayResult = await syncService.replay();

    // Flush OK : 1 mutation envoyée, 0 échec
    expect(replayResult.sent).toBe(1);
    expect(replayResult.failed).toBe(0);
    expect(outbox.size()).toBe(0);

    // Le backend a bien reçu le checkout AVEC le clientSaleId (idempotence respectée)
    const replayCall = calls.find((c) => c.url.endsWith("/api/sales/checkout"));
    expect(replayCall).toBeDefined();
    expect(replayCall?.authHeader).toBe("Bearer fake-access-token-xyz");
    expect((replayCall?.body as { clientSaleId: string }).clientSaleId).toBe(sale.id);
  });
});
