import { create } from "zustand";
import { broadcastSync } from "@/lib/sync";
import { pushMutation } from "@/store/backend";
import { useData, type Sale, type StockMove } from "@/store/data";
import { useCustomers } from "@/store/customers";
import { newId } from "@/lib/ids";

/**
 * Store des ventes — VOLATILE par design (pas de middleware persist).
 *
 * Pourquoi pas de persist :
 *  - 1000 ventes × ~1 KB = ~1 MB localStorage par caisse → atteint la limite
 *    navigateur (~5 MB par origin) et casse aussi les autres stores persistés.
 *  - Le backend Postgres est l'unique source de vérité ; persister double les données.
 *  - Au boot, syncBackend.hydrateFromBackend rappelle setAll() avec les ventes
 *    fraîches du serveur.
 *
 * Offline-first :
 *  - addSale / refundSale appliquent l'effet en mémoire immédiatement
 *    (UI réactive instantanément), puis pushMutation pousse au backend via
 *    l'outbox si LAN injoignable.
 *  - Les side-effects sur products/moves sont délégués à useData.setState
 *    pour garder ces collections cohérentes en cas d'offline.
 */
interface SalesState {
  sales: Sale[];
  /** Numérotation incrémentale par store, recalculée depuis sales à l'hydratation. */
  saleSeq: Record<string, number>;
  setAll: (sales: Sale[], saleSeq: Record<string, number>) => void;
  addSale: (s: Omit<Sale, "id" | "date" | "seq">) => Sale;
  refundSale: (id: string, by: { userId: string; userName: string }) => { ok: boolean; error?: string; refund?: Sale };
  clear: () => void;
}

// P1.3 + P2.3 : IDs via newId() — UUID v4 si crypto.randomUUID dispo (secure
// context), sinon 16 octets crypto.getRandomValues (dispo en HTTP LAN), sinon
// fallback Date.now+Math.random. Aucun risque de collision en pratique.
const newSaleId = () => newId("sale-");
const newRefundId = () => `${newId("sale-")}-r`;
const newMoveId = () => newId("m-");

export const useSales = create<SalesState>()((set, get) => ({
  sales: [],
  saleSeq: {},

  setAll: (sales, saleSeq) => {
    set({ sales, saleSeq });
    broadcastSync("kidzpos-sales");
  },

  addSale: (sale) => {
    const seq = (get().saleSeq[sale.storeId] ?? 0) + 1;
    const full: Sale = {
      ...sale,
      id: newSaleId(),
      date: new Date().toISOString(),
      seq,
    };
    set((s) => ({
      sales: [full, ...s.sales],
      saleSeq: { ...s.saleSeq, [sale.storeId]: seq },
    }));
    // Side-effects products/moves restent dans useData pour cohérence offline
    useData.setState((s) => {
      const products = s.products.map((p) => {
        const it = sale.items.find((i) => i.productId === p.id);
        return it ? { ...p, stock: Math.max(0, p.stock - it.quantity) } : p;
      });
      const moves: StockMove[] = sale.items.map((it) => ({
        id: newMoveId(),
        date: full.date,
        productId: it.productId,
        productName: it.name,
        storeId: sale.storeId,
        type: "SALE",
        delta: -it.quantity,
        userId: sale.userId,
        userName: sale.userName,
        reason: `Vente #${seq}`,
      }));
      return {
        products,
        moves: [...moves, ...s.moves].slice(0, 1000),
      };
    });
    broadcastSync("kidzpos-sales");
    broadcastSync("kidzpos-data");
    // I8-FE : clientSaleId = id local → idempotence du replay outbox après reconnexion
    // (le backend renvoie la vente existante au lieu d'en créer une nouvelle).
    void pushMutation("/api/sales/checkout", "POST", {
      clientSaleId: full.id,
      storeId: sale.storeId,
      items: sale.items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
      discount: sale.discount,
      paymentMode: sale.paymentMode,
      amountPaid: sale.amountPaid,
      customerId: sale.customerId,
      pointsRedeemed: sale.pointsRedeemed,
      currency: sale.currency,
    }, `sale:${full.id}`);
    return full;
  },

  refundSale: (id, by) => {
    const original = get().sales.find((s) => s.id === id);
    if (!original) return { ok: false, error: "Vente introuvable" };
    if (original.refundedFrom) return { ok: false, error: "C'est déjà un remboursement" };
    if (get().sales.some((s) => s.refundedFrom === id)) return { ok: false, error: "Déjà remboursée" };
    const seq = (get().saleSeq[original.storeId] ?? 0) + 1;
    const refund: Sale = {
      ...original,
      id: newRefundId(),
      seq,
      date: new Date().toISOString(),
      items: original.items.map((i) => ({ ...i })),
      subtotal: -original.subtotal,
      discount: -original.discount,
      total: -original.total,
      pointsEarned: -original.pointsEarned,
      pointsRedeemed: -original.pointsRedeemed,
      amountPaid: -(original.amountPaid ?? original.total),
      change: 0,
      userId: by.userId,
      userName: by.userName,
      refundedFrom: original.id,
    };
    set((s) => ({
      sales: [refund, ...s.sales],
      saleSeq: { ...s.saleSeq, [original.storeId]: seq },
    }));
    useData.setState((s) => {
      // Réintégrer le stock
      const products = s.products.map((p) => {
        const it = original.items.find((i) => i.productId === p.id);
        return it ? { ...p, stock: p.stock + it.quantity } : p;
      });
      const moves: StockMove[] = original.items.map((it) => ({
        id: newMoveId(),
        date: refund.date,
        productId: it.productId,
        productName: it.name,
        storeId: original.storeId,
        type: "REFUND",
        delta: it.quantity,
        userId: by.userId,
        userName: by.userName,
        reason: `Remboursement vente #${original.seq}`,
      }));
      return {
        products,
        moves: [...moves, ...s.moves].slice(0, 1000),
      };
    });
    broadcastSync("kidzpos-sales");
    broadcastSync("kidzpos-data");
    // Retirer les points gagnés lors de la vente originale
    if (original.customerId && original.pointsEarned > 0) {
      useCustomers.getState().applyPurchase(
        original.customerId,
        -original.total,
        -original.pointsEarned,
        0
      );
    }
    // V22 — clientRefundId : UUID stable côté client pour l'idempotence du refund
    // au replay outbox (réseau coupé en plein POST → retry → double-remboursement
    // sinon). Le backend renvoie le refund existant si déjà connu.
    void pushMutation("/api/sales/refund", "POST", {
      saleId: id,
      clientRefundId: refund.id,
    }, `refund:${id}`);
    return { ok: true, refund };
  },

  clear: () => {
    set({ sales: [], saleSeq: {} });
    broadcastSync("kidzpos-sales");
  },
}));
