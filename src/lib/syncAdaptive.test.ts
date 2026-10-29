import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { __resetReplayStateForTests, drainReplayStats } from "@/lib/syncService";
import { outbox } from "@/lib/outbox";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { tokenStore } from "@/lib/apiClient";

/**
 * Tests d'intégration P5 — replay adaptatif self-healing.
 *
 * Vérifie le comportement bout-en-bout via syncService.replay() :
 *   1. Mixed success/failure : items qui réussissent continuent de flow
 *      même quand certains échouent (isolation per-item)
 *   2. Per-item retry : un item 5xx est replanifié avec nextEligibleAt
 *      futur ; les autres NE sont PAS bloqués
 *   3. Dead-letter : un item dépassant maxAttempts est dead-lettered
 *   4. Hard limit : > 10k → pause + notification synthétique unique
 *   5. State machine : enters DEGRADED après 3 fails consécutifs
 */

const ORIGINAL_FETCH = globalThis.fetch;

function jsonResponse(status: number, body: unknown = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  tokenStore.set("test-token");
  useBackend.setState({ lanReachable: true, pendingCount: 0 });
  useFailedReplays.getState().clear();
  outbox.clear();
  __resetReplayStateForTests();
  drainReplayStats();
  globalThis.fetch = vi.fn();
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  tokenStore.set(null);
  outbox.clear();
  useFailedReplays.getState().clear();
  __resetReplayStateForTests();
});

describe("P5 — Per-item retry isolation", () => {
  it("un item 5xx est replanifié SANS bloquer les autres", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async (url: string | URL) => {
      // Seul /api/bad/* renvoie 503
      if (String(url).includes("/api/bad/")) return jsonResponse(503, { error: "down" });
      return jsonResponse(200);
    });

    outbox.enqueue({ path: "/api/good/1", method: "POST", body: {} });
    outbox.enqueue({ path: "/api/bad/x",  method: "POST", body: {} });
    outbox.enqueue({ path: "/api/good/2", method: "POST", body: {} });
    outbox.enqueue({ path: "/api/good/3", method: "POST", body: {} });

    const { syncService } = await import("@/lib/syncService");
    const r = await syncService.replay();

    // 3 good succès, 1 bad replanifié → toujours dans l'outbox
    expect(r.sent).toBe(3);
    expect(r.failed).toBe(1);
    expect(outbox.size()).toBe(1);
    const remaining = outbox.list()[0];
    expect(remaining.path).toBe("/api/bad/x");
    expect(remaining.failureCount).toBe(1);
    expect(remaining.nextEligibleAt).toBeGreaterThan(Date.now());
  }, 10_000);

  it("item replanifié → invisible au prochain drain immédiat (eligibilité future)", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async () => jsonResponse(503));

    outbox.enqueue({ path: "/api/x", method: "POST", body: {} });
    const { syncService } = await import("@/lib/syncService");

    await syncService.replay();
    expect(outbox.list()[0].nextEligibleAt).toBeGreaterThan(Date.now());

    // Second drain immédiat : l'item est filtré (nextEligibleAt > now)
    fetchMock.mockClear();
    const r2 = await syncService.replay();
    expect(r2.sent).toBe(0);
    expect(r2.failed).toBe(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("P5 — Dead letter (anti-starvation)", () => {
  it("item dépassant maxAttempts → failedReplays + retiré de l'outbox", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async () => jsonResponse(503));

    // Pré-pose un item avec failureCount juste sous le seuil
    const fakeEntry = {
      id: "dead-1",
      ts: Date.now() - 1_000_000,
      path: "/api/permafail",
      method: "POST" as const,
      body: {},
      retries: 0,
      failureCount: 49,           // < 50 (maxAttempts par défaut)
      nextEligibleAt: 0,          // éligible
    };
    localStorage.setItem("kidzpos-outbox", JSON.stringify([fakeEntry]));

    const { syncService } = await import("@/lib/syncService");
    await syncService.replay();

    expect(outbox.size()).toBe(0);  // retiré du fait du dead-letter
    const failures = useFailedReplays.getState().failures;
    expect(failures).toHaveLength(1);
    expect(failures[0].path).toBe("/api/permafail");
    expect(failures[0].error).toMatch(/Abandon après/);
  });
});

describe("P5 — Hard limit & overflow", () => {
  it("outbox > HARD_LIMIT → drain pausé + notification synthétique unique", async () => {
    const fakeList = Array.from({ length: 10_001 }, (_, i) => ({
      id: `id-${i}`, ts: Date.now(), path: `/api/x/${i}`,
      method: "POST" as const, body: {}, retries: 0,
    }));
    localStorage.setItem("kidzpos-outbox", JSON.stringify(fakeList));

    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async () => jsonResponse(200));

    const { syncService } = await import("@/lib/syncService");
    const r = await syncService.replay();

    expect(r).toEqual({ sent: 0, failed: 0 });
    expect(fetchMock).not.toHaveBeenCalled();
    const failures = useFailedReplays.getState().failures;
    expect(failures).toHaveLength(1);
    expect(failures[0].path).toBe("<replay-paused>");

    // Second appel : pas de duplication de la notification
    await syncService.replay();
    expect(useFailedReplays.getState().failures).toHaveLength(1);
  });
});

describe("P5 — State machine intégrée", () => {
  it("3 fails réseau consécutifs → bascule DEGRADED", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async () => jsonResponse(503));

    for (let i = 0; i < 5; i++) {
      outbox.enqueue({ path: `/api/x/${i}`, method: "POST", body: {} });
    }

    const { syncService } = await import("@/lib/syncService");
    await syncService.replay();

    const stats = drainReplayStats();
    expect(stats.mode).toBe("DEGRADED");
    expect(stats.degradedEntriesTotal).toBeGreaterThanOrEqual(1);
  }, 10_000);

  it("réseau OK après outage → bascule RECOVERY (DEGRADED → RECOVERY)", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;

    // Phase 1 : forcer en DEGRADED via 5 fails réseau.
    // En DEGRADED, la consigne est rps=2 batch=1 pause=500ms → drain lent.
    // On limite à 5 entrées pour rester sous le timeout vitest.
    fetchMock.mockImplementation(async () => jsonResponse(503));
    for (let i = 0; i < 5; i++) {
      outbox.enqueue({ path: `/api/down/${i}`, method: "POST", body: {} });
    }
    const { syncService } = await import("@/lib/syncService");
    await syncService.replay();
    expect(drainReplayStats().mode).toBe("DEGRADED");

    // Phase 2 : 3 succès consécutifs → RECOVERY.
    // RECOVERY → NORMAL n'est pas testé ici en intégration (nécessite > 50 ops
    // pour évincer les fails de la fenêtre rolling et atteindre failureRate < 0.1).
    // La transition pure est couverte par stateMachine.test.ts.
    outbox.clear();
    fetchMock.mockImplementation(async () => jsonResponse(200));
    for (let i = 0; i < 3; i++) {
      outbox.enqueue({ path: `/api/up/${i}`, method: "POST", body: {} });
    }
    await syncService.replay();
    expect(drainReplayStats().mode).toBe("RECOVERY");
  }, 15_000);
});
