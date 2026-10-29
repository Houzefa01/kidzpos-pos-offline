import { describe, it, expect, vi, beforeEach } from "vitest";
import { createSyncService, type Transport, type MutationSpec } from "./syncService";

/**
 * Tests unitaires de syncService avec Transport mocké — aucun fetch réel,
 * aucun appel au localStorage. La matrice couvre les 6 combinaisons critiques :
 *   offline × (sensible | normal)
 *   online + 200
 *   online + 4xx
 *   online + 5xx × (sensible | normal)
 * Plus 3 tests sur replay() (drainQueue côté Transport).
 */

function makeTransport(overrides: Partial<Transport> = {}): Transport {
  return {
    isOnline: vi.fn(() => true),
    send: vi.fn(async () => undefined),
    enqueue: vi.fn(),
    markUnreachable: vi.fn(),
    drainQueue: vi.fn(async () => ({ sent: 0, failed: 0 })),
    pendingCount: vi.fn(() => 0),
    ...overrides,
  };
}

const PRODUCT_POST: MutationSpec = { path: "/api/products", method: "POST", body: { name: "X" } };
const USERS_POST: MutationSpec = {
  path: "/api/users",
  method: "POST",
  body: { name: "X", password: "secret" },
};
const USERS_PUT: MutationSpec = {
  path: "/api/users/u1",
  method: "PUT",
  body: { password: "new" },
};
const USERS_DELETE: MutationSpec = { path: "/api/users/u1", method: "DELETE" };

describe("syncService.submit — branche offline", () => {
  it("offline + normal → enqueue, queued=true", async () => {
    const t = makeTransport({ isOnline: () => false });
    const r = await createSyncService(t).submit(PRODUCT_POST);

    expect(r).toEqual({ queued: true });
    expect(t.enqueue).toHaveBeenCalledTimes(1);
    expect(t.enqueue).toHaveBeenCalledWith(PRODUCT_POST, "offline");
    expect(t.send).not.toHaveBeenCalled();
    expect(t.markUnreachable).not.toHaveBeenCalled();
  });

  it("offline + sensible (POST /api/users) → refus, jamais enfilé", async () => {
    const t = makeTransport({ isOnline: () => false });
    const r = await createSyncService(t).submit(USERS_POST);

    expect(r.queued).toBe(false);
    expect(r.error).toMatch(/Connexion serveur requise/);
    expect(t.enqueue).not.toHaveBeenCalled();
    expect(t.send).not.toHaveBeenCalled();
  });

  it("offline + sensible (PUT /api/users/x) → refus", async () => {
    const t = makeTransport({ isOnline: () => false });
    const r = await createSyncService(t).submit(USERS_PUT);

    expect(r.queued).toBe(false);
    expect(t.enqueue).not.toHaveBeenCalled();
  });

  it("offline + DELETE /api/users → enfilé (pas de body sensible)", async () => {
    const t = makeTransport({ isOnline: () => false });
    const r = await createSyncService(t).submit(USERS_DELETE);

    expect(r.queued).toBe(true);
    expect(t.enqueue).toHaveBeenCalledTimes(1);
  });
});

describe("syncService.submit — branche online", () => {
  it("online + send 200 → queued=false, pas d'enqueue", async () => {
    const t = makeTransport();
    const r = await createSyncService(t).submit(PRODUCT_POST);

    expect(r).toEqual({ queued: false });
    expect(t.send).toHaveBeenCalledTimes(1);
    expect(t.enqueue).not.toHaveBeenCalled();
    expect(t.markUnreachable).not.toHaveBeenCalled();
  });

  it("online + 4xx → erreur remontée, pas d'enqueue, lan reste online", async () => {
    const err = Object.assign(new Error("Stock insuffisant"), { status: 400 });
    const t = makeTransport({ send: vi.fn(async () => { throw err; }) });
    const r = await createSyncService(t).submit(PRODUCT_POST);

    expect(r.queued).toBe(false);
    expect(r.error).toBe("Stock insuffisant");
    expect(t.enqueue).not.toHaveBeenCalled();
    expect(t.markUnreachable).not.toHaveBeenCalled();
  });

  it("online + 5xx + normal → markUnreachable + enqueue", async () => {
    const err = Object.assign(new Error("server"), { status: 503 });
    const t = makeTransport({ send: vi.fn(async () => { throw err; }) });
    const r = await createSyncService(t).submit(PRODUCT_POST);

    expect(r.queued).toBe(true);
    expect(t.markUnreachable).toHaveBeenCalledTimes(1);
    expect(t.enqueue).toHaveBeenCalledWith(PRODUCT_POST, "failure-after-mark");
  });

  it("online + timeout (pas de status) → markUnreachable + enqueue", async () => {
    const t = makeTransport({ send: vi.fn(async () => { throw new Error("timeout"); }) });
    const r = await createSyncService(t).submit(PRODUCT_POST);

    expect(r.queued).toBe(true);
    expect(t.markUnreachable).toHaveBeenCalledTimes(1);
    expect(t.enqueue).toHaveBeenCalledTimes(1);
  });

  it("online + 5xx + sensible → markUnreachable mais JAMAIS enqueue", async () => {
    const err = Object.assign(new Error("server"), { status: 503 });
    const t = makeTransport({ send: vi.fn(async () => { throw err; }) });
    const r = await createSyncService(t).submit(USERS_POST);

    expect(r.queued).toBe(false);
    expect(r.error).toMatch(/sensible non enregistrée/);
    expect(t.markUnreachable).toHaveBeenCalledTimes(1);
    expect(t.enqueue).not.toHaveBeenCalled();
  });

  it("override explicite sensitive=true → bloque l'enqueue même hors /api/users", async () => {
    const err = Object.assign(new Error("server"), { status: 503 });
    const t = makeTransport({ send: vi.fn(async () => { throw err; }) });
    const spec: MutationSpec = { ...PRODUCT_POST, sensitive: true };
    const r = await createSyncService(t).submit(spec);

    expect(r.queued).toBe(false);
    expect(t.enqueue).not.toHaveBeenCalled();
  });
});

describe("syncService.replay / pendingCount", () => {
  it("replay() délègue à transport.drainQueue", async () => {
    const t = makeTransport({
      drainQueue: vi.fn(async () => ({ sent: 3, failed: 1 })),
    });
    const r = await createSyncService(t).replay();

    expect(r).toEqual({ sent: 3, failed: 1 });
    expect(t.drainQueue).toHaveBeenCalledTimes(1);
  });

  it("pendingCount() délègue à transport.pendingCount", () => {
    const t = makeTransport({ pendingCount: vi.fn(() => 7) });
    expect(createSyncService(t).pendingCount()).toBe(7);
  });
});

describe("syncService — détection sensitive", () => {
  beforeEach(() => vi.clearAllMocks());

  it.each<[string, MutationSpec["method"], boolean]>([
    ["/api/users", "POST", true],
    ["/api/users/u1", "PUT", true],
    ["/api/users/u1", "DELETE", false],
    ["/api/users/u1", "PATCH", false],
    ["/api/products", "POST", false],
    ["/api/usersx", "POST", false],
    ["/api/products/users", "POST", false],
  ])("path=%s method=%s → sensitive=%s", async (path, method, expectedSensitive) => {
    const t = makeTransport({ isOnline: () => false });
    const r = await createSyncService(t).submit({ path, method, body: {} });

    if (expectedSensitive) {
      expect(r.queued).toBe(false);
      expect(t.enqueue).not.toHaveBeenCalled();
    } else {
      expect(r.queued).toBe(true);
      expect(t.enqueue).toHaveBeenCalledTimes(1);
    }
  });
});
