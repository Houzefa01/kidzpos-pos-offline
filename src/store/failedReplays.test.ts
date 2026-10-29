import { beforeEach, describe, expect, it } from "vitest";
import { useFailedReplays } from "./failedReplays";

/**
 * Tests du store useFailedReplays — invariants critiques pour l'UI :
 *  - add() préfixe (les plus récents en premier)
 *  - cap 100 entrées (FIFO eviction des plus anciennes)
 *  - remove(id) retire par id
 *  - clear() vide tout
 *
 * Le store est un singleton ; on remet à zéro entre chaque test via clear().
 */

const baseEntry = {
  ts: 0,
  path: "/api/products",
  method: "POST" as const,
  status: 400,
  error: "test",
};

describe("useFailedReplays", () => {
  beforeEach(() => {
    useFailedReplays.getState().clear();
  });

  it("add() préfixe : le plus récent en premier", () => {
    const { add, failures } = useFailedReplays.getState();
    add({ ...baseEntry, ts: 1 });
    add({ ...baseEntry, ts: 2 });
    add({ ...baseEntry, ts: 3 });
    const list = useFailedReplays.getState().failures;
    expect(list).toHaveLength(3);
    expect(list[0].ts).toBe(3);
    expect(list[2].ts).toBe(1);
    expect(failures).toEqual([]); // snapshot pris avant les add, prouve l'isolation
  });

  it("cap à 100 entrées : les plus anciennes sont évincées (FIFO)", () => {
    const { add } = useFailedReplays.getState();
    for (let i = 0; i < 120; i++) {
      add({ ...baseEntry, ts: i });
    }
    const list = useFailedReplays.getState().failures;
    expect(list).toHaveLength(100);
    // Les 100 entrées conservées sont les plus récentes (ts 119 → 20)
    expect(list[0].ts).toBe(119);
    expect(list[99].ts).toBe(20);
  });

  it("remove(id) retire par id sans toucher aux autres", () => {
    const { add, remove } = useFailedReplays.getState();
    add({ ...baseEntry, ts: 1 });
    add({ ...baseEntry, ts: 2 });
    const before = useFailedReplays.getState().failures;
    const targetId = before[0].id;
    remove(targetId);
    const after = useFailedReplays.getState().failures;
    expect(after).toHaveLength(1);
    expect(after[0].id).not.toBe(targetId);
  });

  it("clear() vide tout", () => {
    const { add, clear } = useFailedReplays.getState();
    add({ ...baseEntry, ts: 1 });
    add({ ...baseEntry, ts: 2 });
    expect(useFailedReplays.getState().failures).toHaveLength(2);
    clear();
    expect(useFailedReplays.getState().failures).toHaveLength(0);
  });

  it("add() génère un id unique pour chaque entrée", () => {
    const { add } = useFailedReplays.getState();
    for (let i = 0; i < 10; i++) add({ ...baseEntry, ts: i });
    const ids = useFailedReplays.getState().failures.map((f) => f.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
