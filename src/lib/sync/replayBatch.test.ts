import { describe, it, expect, vi } from "vitest";
import { processBatches } from "./replayBatch";
import type { OutboxEntry } from "@/lib/outbox";
import { createThrottle } from "./throttle";

function makeEntry(id: string): OutboxEntry {
  return {
    id,
    ts: Date.now(),
    path: `/api/test/${id}`,
    method: "POST",
    body: { id },
    retries: 0,
  };
}

function noWaitThrottle() {
  return createThrottle({
    maxRequestsPerSecond: 1000,
    setTimeout: ((cb: () => void) => { cb(); return 0 as never; }) as never,
  });
}

function noWaitSetTimeout() {
  return ((cb: () => void) => { cb(); return 0 as never; }) as never;
}

describe("processBatches", () => {
  it("traite toutes les entrées en mode succès, en ordre FIFO", async () => {
    const entries = [makeEntry("a"), makeEntry("b"), makeEntry("c"), makeEntry("d"), makeEntry("e")];
    const sent: string[] = [];
    const removed: string[] = [];

    const r = await processBatches({
      outboxList: () => entries,
      outboxRemove: (id) => { removed.push(id); },
      apiSend: async (e) => { sent.push(e.id); },
      onFailure4xx: () => { /* unused */ },
      throttle: noWaitThrottle(),
      batchSize: 2,
      batchPauseMs: 0,
      setTimeout: noWaitSetTimeout(),
    });

    expect(r).toEqual({ sent: 5, failed: 0, stoppedOnError: false });
    expect(sent).toEqual(["a", "b", "c", "d", "e"]);  // FIFO préservé
    expect(removed).toEqual(["a", "b", "c", "d", "e"]);
  });

  it("4xx → onFailure4xx + remove + continue", async () => {
    const entries = [makeEntry("a"), makeEntry("b"), makeEntry("c")];
    const failed4xx: string[] = [];
    const removed: string[] = [];
    let sentCount = 0;

    const r = await processBatches({
      outboxList: () => entries,
      outboxRemove: (id) => { removed.push(id); },
      apiSend: async (e) => {
        if (e.id === "b") {
          throw Object.assign(new Error("validation"), { status: 400 });
        }
        sentCount++;
      },
      onFailure4xx: (e) => { failed4xx.push(e.id); },
      throttle: noWaitThrottle(),
      batchSize: 5,
      setTimeout: noWaitSetTimeout(),
    });

    expect(r.sent).toBe(2);          // a + c
    expect(r.failed).toBe(1);        // b
    expect(r.stoppedOnError).toBe(false);
    expect(failed4xx).toEqual(["b"]);
    expect(removed).toEqual(["a", "b", "c"]);  // b retiré aussi (sinon boucle)
  });

  it("5xx STOPPE le drain (entrée non retirée)", async () => {
    const entries = [makeEntry("a"), makeEntry("b"), makeEntry("c")];
    const removed: string[] = [];
    const fail4xx = vi.fn();
    const on5xx = vi.fn();

    const r = await processBatches({
      outboxList: () => entries,
      outboxRemove: (id) => { removed.push(id); },
      apiSend: async (e) => {
        if (e.id === "b") throw Object.assign(new Error("server"), { status: 503 });
      },
      onFailure4xx: fail4xx,
      on5xxOrNetwork: on5xx,
      throttle: noWaitThrottle(),
      batchSize: 5,
      setTimeout: noWaitSetTimeout(),
    });

    expect(r.stoppedOnError).toBe(true);
    expect(r.sent).toBe(1);
    expect(r.failed).toBe(1);
    expect(removed).toEqual(["a"]);  // b NON retiré, c jamais tenté
    expect(fail4xx).not.toHaveBeenCalled();
    expect(on5xx).toHaveBeenCalledTimes(1);
  });

  it("erreur sans status (réseau / timeout) → STOPPE le drain", async () => {
    const entries = [makeEntry("a"), makeEntry("b")];
    const removed: string[] = [];

    const r = await processBatches({
      outboxList: () => entries,
      outboxRemove: (id) => { removed.push(id); },
      apiSend: async () => { throw new Error("timeout"); },
      onFailure4xx: () => {},
      throttle: noWaitThrottle(),
      batchSize: 5,
      setTimeout: noWaitSetTimeout(),
    });

    expect(r.stoppedOnError).toBe(true);
    expect(r.sent).toBe(0);
    expect(removed).toEqual([]);
  });

  it("pause entre batches : setTimeout appelé entre chaque lot", async () => {
    const entries = [makeEntry("a"), makeEntry("b"), makeEntry("c"), makeEntry("d")];
    const pauseDurations: number[] = [];
    const customSetTimeout = (cb: () => void, ms: number) => {
      pauseDurations.push(ms);
      cb();
      return 0 as never;
    };

    await processBatches({
      outboxList: () => entries,
      outboxRemove: () => {},
      apiSend: async () => {},
      onFailure4xx: () => {},
      throttle: noWaitThrottle(),
      batchSize: 2,
      batchPauseMs: 25,
      setTimeout: customSetTimeout as never,
    });

    // 4 entrées en lots de 2 = 2 lots → 1 pause inter-lots (pas après le dernier)
    expect(pauseDurations).toEqual([25]);
  });

  it("compteur onItemProcessed incrémenté pour succès ET 4xx", async () => {
    const entries = [makeEntry("a"), makeEntry("b"), makeEntry("c")];
    let processed = 0;

    await processBatches({
      outboxList: () => entries,
      outboxRemove: () => {},
      apiSend: async (e) => {
        if (e.id === "b") throw Object.assign(new Error("bad"), { status: 400 });
      },
      onFailure4xx: () => {},
      onItemProcessed: () => { processed++; },
      throttle: noWaitThrottle(),
      batchSize: 5,
      setTimeout: noWaitSetTimeout(),
    });

    expect(processed).toBe(3);
  });

  it("scénario haute charge : 1000 entrées, batch=50, sans crash", async () => {
    const entries = Array.from({ length: 1000 }, (_, i) => makeEntry(`e${i}`));
    let processed = 0;

    const r = await processBatches({
      outboxList: () => entries,
      outboxRemove: () => {},
      apiSend: async () => { processed++; },
      onFailure4xx: () => {},
      throttle: noWaitThrottle(),
      batchSize: 50,
      batchPauseMs: 0,
      setTimeout: noWaitSetTimeout(),
    });

    expect(r.sent).toBe(1000);
    expect(r.failed).toBe(0);
    expect(processed).toBe(1000);
  });
});
