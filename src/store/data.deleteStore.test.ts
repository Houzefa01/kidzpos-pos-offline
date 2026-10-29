/**
 * Régression (audit 2026-06) — deleteStore lisait `get().sales`, or les ventes
 * vivent dans le store SÉPARÉ useSales (P1.3, non persisté) → `get().sales`
 * était `undefined` → `undefined.some(...)` crashait la page Settings dès qu'on
 * supprimait un magasin sans produits. Ce test fige les 3 branches :
 *   1. magasin avec produits   → bloqué
 *   2. magasin avec ventes      → bloqué (ne doit PAS crasher)
 *   3. magasin vide             → supprimé
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

// useSales mocké : on contrôle la liste des ventes lue par deleteStore.
const salesState = { sales: [] as Array<{ storeId: string }> };
vi.mock("@/store/sales", () => ({
  useSales: { getState: () => salesState },
}));
vi.mock("@/lib/sync", () => ({ broadcastSync: vi.fn() }));
vi.mock("@/store/backend", () => ({ pushMutation: vi.fn().mockResolvedValue({ queued: false }) }));

import { useData } from "@/store/data";

beforeEach(() => {
  salesState.sales = [];
  useData.setState({ stores: [], products: [] });
});

describe("deleteStore", () => {
  it("bloque si le magasin contient des produits", () => {
    useData.setState({
      stores: [{ id: "s1", name: "Mag 1", location: "" }],
      products: [{ id: "p1", name: "Jouet", price: 1000, stock: 5, storeId: "s1", sku: "SKU1", createdAt: new Date().toISOString() }],
    });
    const r = useData.getState().deleteStore("s1");
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/produits/i);
    expect(useData.getState().stores).toHaveLength(1);
  });

  it("bloque si le magasin a un historique de ventes (sans crasher)", () => {
    useData.setState({ stores: [{ id: "s1", name: "Mag 1", location: "" }], products: [] });
    salesState.sales = [{ storeId: "s1" }];
    const r = useData.getState().deleteStore("s1");
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/ventes/i);
    expect(useData.getState().stores).toHaveLength(1);
  });

  it("supprime un magasin vide (ni produits ni ventes)", () => {
    useData.setState({ stores: [{ id: "s1", name: "Mag 1", location: "" }], products: [] });
    const r = useData.getState().deleteStore("s1");
    expect(r.ok).toBe(true);
    expect(useData.getState().stores).toHaveLength(0);
  });
});
