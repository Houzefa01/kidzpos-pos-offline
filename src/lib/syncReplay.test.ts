import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { __resetReplayStateForTests, drainReplayStats } from "@/lib/syncService";
import { outbox } from "@/lib/outbox";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { tokenStore } from "@/lib/apiClient";

/**
 * Tests d'intégration P4 : valident le comportement de defaultTransport.drainQueue
 * (via syncService.replay() — c'est l'unique entrée pour drain l'outbox).
 *
 * On mock globalThis.fetch pour contrôler les statuts HTTP. tokenStore est posé
 * pour que api() ajoute Authorization (n'a pas d'impact car fetch est mocké, mais
 * cohérent avec les tests apiClient.test.ts).
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
  drainReplayStats(); // vide les compteurs résiduels
  globalThis.fetch = vi.fn();
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  tokenStore.set(null);
  outbox.clear();
  useFailedReplays.getState().clear();
  __resetReplayStateForTests();
});

describe("syncService.replay — P4 controlled replay", () => {
  it("drain 10 entrées en mode succès → batchSize stat = 10, FIFO respecté", async () => {
    // 10 entrées × 200 ms throttle (5 rps) ≃ 2 s — fits dans le timeout vitest 5 s.
    // Le scénario "haute charge 1000" est validé en unitaire dans replayBatch.test.ts.
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    const seenPaths: string[] = [];
    fetchMock.mockImplementation(async (url: string | URL) => {
      seenPaths.push(String(url));
      return jsonResponse(200);
    });

    for (let i = 0; i < 10; i++) {
      outbox.enqueue({ path: `/api/x/${i}`, method: "POST", body: { i } });
    }
    const { syncService } = await import("@/lib/syncService");
    const r = await syncService.replay();

    expect(r).toEqual({ sent: 10, failed: 0 });
    expect(outbox.size()).toBe(0);
    // FIFO : les paths arrivent dans l'ordre d'enqueue
    const apiPaths = seenPaths.filter((p) => p.includes("/api/x/"));
    expect(apiPaths.map((p) => p.match(/\/api\/x\/(\d+)/)?.[1])).toEqual(
      Array.from({ length: 10 }, (_, i) => String(i))
    );
    // Compteurs P4 incrémentés
    const stats = drainReplayStats();
    expect(stats.batchSize).toBe(10);
  }, 10_000);

  it("4xx → entrée déplacée vers failedReplays + retirée de l'outbox", async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    fetchMock.mockImplementation(async () => jsonResponse(400, { error: "stock négatif" }));

    outbox.enqueue({ path: "/api/stock/adjust", method: "POST", body: { delta: -100 } });
    const { syncService } = await import("@/lib/syncService");

    await syncService.replay();

    expect(outbox.size()).toBe(0);
    const failures = useFailedReplays.getState().failures;
    expect(failures).toHaveLength(1);
    expect(failures[0].status).toBe(400);
    expect(failures[0].error).toBe("stock négatif");
  });

  // Note : les tests 5xx-break et overflow > 5000 ont été retirés avec P5
  // (per-item retry + HARD_LIMIT 10000). Leurs équivalents adaptés se trouvent
  // dans syncAdaptive.test.ts.
});
