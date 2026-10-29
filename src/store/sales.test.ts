/**
 * T14 — Tests unitaires sur le store sales (refund flow + invariants).
 *
 * Couvre les invariants critiques :
 *  - refundSale envoie clientRefundId au backend (V22, anti double-remboursement)
 *  - refundSale refuse une vente déjà remboursée
 *  - refundSale refuse de rembourser un refund
 *  - addSale + refundSale enchaînés maintiennent les compteurs (seq, stock)
 *  - Stock restocké de la quantité exacte du refund
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

// Mocks d'infra. vi.hoisted permet de partager pushMutationMock entre vi.mock
// (hissé au top) et les expectations dans les tests.
const { pushMutationMock } = vi.hoisted(() => ({
  pushMutationMock: vi.fn().mockResolvedValue({ queued: false }),
}));
vi.mock("@/lib/sync", () => ({ broadcastSync: vi.fn() }));
vi.mock("@/lib/apiClient", () => ({
  api: vi.fn(),
  tokenStore: { get: () => null, set: vi.fn() },
}));
vi.mock("@/store/backend", () => ({
  useBackend: { getState: () => ({ lanReachable: true }), setState: vi.fn() },
  pushMutation: pushMutationMock,
}));
vi.mock("@/store/customers", () => ({
  useCustomers: { getState: () => ({ applyPurchase: vi.fn() }) },
}));

import { useSales } from "@/store/sales";
import { useData, type Product, type Sale } from "@/store/data";

function seedProduct(): Product {
  return {
    id: "p1", name: "T-shirt", price: 1000, stock: 10,
    storeId: "s1", sku: "SKU-P1", createdAt: new Date().toISOString(),
  };
}

function makeSale(): Omit<Sale, "id" | "date" | "seq"> {
  return {
    storeId: "s1",
    userId: "u1", userName: "Tester",
    items: [{ productId: "p1", name: "T-shirt", quantity: 3, price: 1000 }],
    subtotal: 3000, discount: 0, total: 3000,
    pointsEarned: 0, pointsRedeemed: 0,
    paymentMode: "CASH", amountPaid: 3000, change: 0,
    currency: "AR",
  };
}

beforeEach(() => {
  pushMutationMock.mockClear();
  useData.setState({
    stores: [{ id: "s1", name: "S1", location: "" }],
    products: [seedProduct()],
    moves: [],
  }, false);
  useSales.getState().setAll([], {});
});

describe("useSales.addSale", () => {
  it("incrémente seq par store + décrémente le stock + appelle pushMutation /checkout", () => {
    const sale = useSales.getState().addSale(makeSale());
    expect(sale.seq).toBe(1);
    expect(sale.id).toBeTruthy();
    expect(useData.getState().products[0].stock).toBe(7);    // 10 - 3

    // Push mutation /api/sales/checkout avec clientSaleId
    expect(pushMutationMock).toHaveBeenCalledTimes(1);
    const [path, method, body] = pushMutationMock.mock.calls[0];
    expect(path).toBe("/api/sales/checkout");
    expect(method).toBe("POST");
    expect(body).toMatchObject({ clientSaleId: sale.id, storeId: "s1" });
  });

  it("seq continue à 2 pour la 2e vente du même store", () => {
    const s1 = useSales.getState().addSale(makeSale());
    const s2 = useSales.getState().addSale(makeSale());
    expect(s1.seq).toBe(1);
    expect(s2.seq).toBe(2);
  });
});

describe("useSales.refundSale", () => {
  it("V22 — envoie clientRefundId au backend pour idempotence", () => {
    const sale = useSales.getState().addSale(makeSale());
    pushMutationMock.mockClear();

    const res = useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    expect(res.ok).toBe(true);
    expect(res.refund).toBeDefined();

    const refundCall = pushMutationMock.mock.calls.find((c) => c[0] === "/api/sales/refund");
    expect(refundCall).toBeDefined();
    const body = refundCall![2] as { saleId: string; clientRefundId: string };
    expect(body.saleId).toBe(sale.id);
    expect(body.clientRefundId).toBe(res.refund!.id);
  });

  it("restocke la quantité exacte du refund", () => {
    const sale = useSales.getState().addSale(makeSale());     // stock 10 → 7
    expect(useData.getState().products[0].stock).toBe(7);
    useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    expect(useData.getState().products[0].stock).toBe(10);    // restocké
  });

  it("ajoute un mouvement REFUND au journal", () => {
    const sale = useSales.getState().addSale(makeSale());
    useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    const refundMoves = useData.getState().moves.filter((m) => m.type === "REFUND");
    expect(refundMoves).toHaveLength(1);
    expect(refundMoves[0].delta).toBe(3);
  });

  it("refuse de rembourser une vente déjà remboursée", () => {
    const sale = useSales.getState().addSale(makeSale());
    const r1 = useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    expect(r1.ok).toBe(true);

    const r2 = useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    expect(r2.ok).toBe(false);
    expect(r2.error).toMatch(/déjà remboursée/i);
  });

  it("refuse de rembourser un refund (refund-d'un-refund)", () => {
    const sale = useSales.getState().addSale(makeSale());
    const r = useSales.getState().refundSale(sale.id, { userId: "u1", userName: "T" });
    expect(r.ok).toBe(true);

    const rr = useSales.getState().refundSale(r.refund!.id, { userId: "u1", userName: "T" });
    expect(rr.ok).toBe(false);
    expect(rr.error).toMatch(/remboursement/i);
  });

  it("refuse une vente introuvable", () => {
    const res = useSales.getState().refundSale("inexistant", { userId: "u1", userName: "T" });
    expect(res.ok).toBe(false);
    expect(res.error).toMatch(/introuvable/i);
  });
});

describe("useSales.setAll", () => {
  it("réhydrate sales + saleSeq depuis le backend", () => {
    const sale: Sale = {
      id: "x", seq: 42, storeId: "s1", userId: "u1", userName: "Test",
      items: [], subtotal: 0, discount: 0, total: 0,
      date: new Date().toISOString(),
      pointsEarned: 0, pointsRedeemed: 0,
      paymentMode: "CASH", currency: "AR",
    };
    useSales.getState().setAll([sale], { s1: 42 });
    expect(useSales.getState().sales).toHaveLength(1);
    expect(useSales.getState().saleSeq.s1).toBe(42);
  });

  it("clear remet à zéro", () => {
    useSales.getState().addSale(makeSale());
    useSales.getState().clear();
    expect(useSales.getState().sales).toHaveLength(0);
    expect(useSales.getState().saleSeq).toEqual({});
  });
});
