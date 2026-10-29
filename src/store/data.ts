import { create } from "zustand";
import { persist } from "zustand/middleware";
import { broadcastSync } from "@/lib/sync";
import { BackupSchema } from "@/lib/schemas";
import { pushMutation } from "@/store/backend";
import { newId } from "@/lib/ids";
// P1.3 : cycle data ↔ sales toléré — les références cross-stores ne sont lues
// qu'à l'intérieur des actions (call time), jamais à l'init du module.
// ESM live bindings garantissent la cohérence quand l'action est invoquée.
import { useSales } from "@/store/sales";

/** UUID stable pour l'idempotence des mouvements de stock. Identique pour tout replay
 *  d'une même mutation (le body persisté dans l'outbox conserve cet ID). */
function makeClientMovementId(): string {
  // P2.3 : délégué à newId — même garantie UUID + fallback HTTP LAN.
  return newId("mv-");
}

export interface Store {
  id: string;
  name: string;
  location: string;
}

export interface Product {
  id: string;
  name: string;
  price: number;
  stock: number;
  storeId: string;
  category?: string;
  sku: string;
  createdAt: string;
}

export interface SaleItem {
  productId: string;
  name: string;
  quantity: number;
  price: number;
}

export type PaymentMode = "CASH" | "CARD" | "MIXED" | "MOBILE_MONEY";
export type SaleCurrency = "AR" | "EUR";

/** Libellé FR uniformisé pour l'affichage. Source unique pour POS, Sales, PDF. */
export function paymentLabel(mode: PaymentMode): string {
  switch (mode) {
    case "CASH": return "Espèces";
    case "CARD": return "Carte";
    case "MIXED": return "Mixte";
    case "MOBILE_MONEY": return "Mobile Money";
  }
}

export interface Sale {
  id: string;
  seq: number;
  storeId: string;
  userId: string;
  userName: string;
  items: SaleItem[];
  subtotal: number;
  discount: number;
  total: number;
  date: string;
  customerId?: string;
  customerName?: string;
  pointsEarned: number;
  pointsRedeemed: number;
  paymentMode: PaymentMode;
  amountPaid?: number;
  change?: number;
  refundedFrom?: string;
  currency: SaleCurrency;
}

export type StockMoveType = "IN" | "OUT" | "ADJUST" | "TRANSFER" | "SALE" | "REFUND";

export interface StockMove {
  id: string;
  date: string;
  productId: string;
  productName: string;
  storeId: string;
  toStoreId?: string; // pour TRANSFER
  type: StockMoveType;
  delta: number; // signé
  reason?: string;
  userId?: string;
  userName?: string;
}

export interface ParkedCart {
  id: string;
  label: string;
  storeId: string;
  userId: string;
  items: SaleItem[];
  customerId?: string;
  customerName?: string;
  createdAt: string;
}

interface DataState {
  stores: Store[];
  products: Product[];
  moves: StockMove[];
  parked: ParkedCart[];
  addProduct: (p: Omit<Product, "id" | "createdAt">) => { ok: boolean; error?: string; product?: Product };
  updateProduct: (id: string, patch: Partial<Product>) => void;
  deleteProduct: (id: string) => void;
  bulkImportProducts: (rows: Omit<Product, "id" | "createdAt">[]) => { added: number; skipped: number };
  adjustStock: (productId: string, delta: number, reason: string, by?: { userId: string; userName: string }) => { ok: boolean; error?: string };
  transferStock: (productId: string, fromStoreId: string, toStoreId: string, qty: number, by?: { userId: string; userName: string }) => { ok: boolean; error?: string };
  parkCart: (cart: Omit<ParkedCart, "id" | "createdAt">) => void;
  unparkCart: (id: string) => ParkedCart | undefined;
  addStore: (s: Omit<Store, "id">) => { ok: boolean; error?: string; store?: Store };
  updateStore: (id: string, patch: Partial<Store>) => void;
  deleteStore: (id: string) => { ok: boolean; error?: string };
  exportAll: () => string;
  importAll: (json: string) => { ok: boolean; error?: string };
  resetAll: () => void;
}

// État initial vide : le backend Postgres est l'unique source de vérité.
// `syncBackend` peuple stores/products/customers + useSales au démarrage et via SSE.
//
// P1.3 : sales/saleSeq + addSale/refundSale ont été déplacés dans `@/store/sales`
// (useSales, NON persisté). Cf src/store/sales.ts pour la justification.
export const useData = create<DataState>()(
  persist(
    (set, get) => ({
      stores: [],
      products: [],
      moves: [],
      parked: [],

      addProduct: (p) => {
        const existing = get().products.find(
          (x) => x.storeId === p.storeId && x.sku.toLowerCase() === p.sku.toLowerCase()
        );
        if (existing) return { ok: false, error: "Référence (SKU) déjà utilisée dans ce magasin" };
        const product: Product = { ...p, id: newId("p-"), createdAt: new Date().toISOString() };
        set((s) => ({ products: [...s.products, product] }));
        broadcastSync("kidzpos-data");
        void pushMutation("/api/products", "POST", product, `product:${product.id}`);
        return { ok: true, product };
      },
      updateProduct: (id, patch) => {
        set((s) => ({ products: s.products.map((x) => (x.id === id ? { ...x, ...patch } : x)) }));
        broadcastSync("kidzpos-data");
        const updated = get().products.find((p) => p.id === id);
        if (updated) void pushMutation(`/api/products/${id}`, "PUT", updated, `product:${id}`);
      },
      deleteProduct: (id) => {
        set((s) => ({ products: s.products.filter((x) => x.id !== id) }));
        broadcastSync("kidzpos-data");
        void pushMutation(`/api/products/${id}`, "DELETE", undefined, `product:${id}`);
      },
      bulkImportProducts: (rows) => {
        let added = 0, skipped = 0;
        const createdAt = new Date().toISOString();
        set((s) => {
          const products = [...s.products];
          rows.forEach((r) => {
            const dup = products.find((x) => x.storeId === r.storeId && x.sku.toLowerCase() === r.sku.toLowerCase());
            if (dup) { skipped++; return; }
            // P2.3 : un UUID par produit (avant : `p${Date.now()}-${i}` partagé sur
            // toute la boucle synchrone → IDs prévisibles + risque collision avec
            // un autre import dans la même ms).
            products.push({ ...r, id: newId("p-"), createdAt });
            added++;
          });
          return { products };
        });
        broadcastSync("kidzpos-data");
        return { added, skipped };
      },
      adjustStock: (productId, delta, reason, by) => {
        const p = get().products.find((x) => x.id === productId);
        if (!p) return { ok: false, error: "Produit introuvable" };
        const newStock = p.stock + delta;
        if (newStock < 0) return { ok: false, error: "Stock négatif interdit" };
        // Idempotence : même UUID conservé dans le body persisté → replay outbox
        // détecté par le backend (uk_stock_movements_client_id, V8).
        const clientMovementId = makeClientMovementId();
        const move: StockMove = {
          id: newId("m-"),
          date: new Date().toISOString(),
          productId, productName: p.name, storeId: p.storeId,
          type: delta > 0 ? "IN" : delta < 0 ? "OUT" : "ADJUST",
          delta, reason, userId: by?.userId, userName: by?.userName,
        };
        set((s) => ({
          products: s.products.map((x) => (x.id === productId ? { ...x, stock: newStock } : x)),
          moves: [move, ...s.moves].slice(0, 1000),
        }));
        broadcastSync("kidzpos-data");
        void pushMutation(
          "/api/stock/adjust",
          "POST",
          { productId, delta, reason, clientMovementId },
          `adjust:${clientMovementId}`,
        );
        return { ok: true };
      },
      transferStock: (productId, fromStoreId, toStoreId, qty, by) => {
        if (qty <= 0) return { ok: false, error: "Quantité invalide" };
        if (fromStoreId === toStoreId) return { ok: false, error: "Même magasin" };
        const src = get().products.find((p) => p.id === productId && p.storeId === fromStoreId);
        if (!src) return { ok: false, error: "Produit introuvable dans le magasin source" };
        if (src.stock < qty) return { ok: false, error: "Stock source insuffisant" };
        const clientMovementId = makeClientMovementId();
        // Trouver ou créer la version destination par SKU
        let dst = get().products.find((p) => p.storeId === toStoreId && p.sku.toLowerCase() === src.sku.toLowerCase());
        const now = new Date().toISOString();
        set((s) => {
          let products = s.products.map((p) => (p.id === src.id ? { ...p, stock: p.stock - qty } : p));
          if (dst) {
            products = products.map((p) => (p.id === dst!.id ? { ...p, stock: p.stock + qty } : p));
          } else {
            const id = newId("p-");
            products = [...products, { ...src, id, storeId: toStoreId, stock: qty, createdAt: now }];
            dst = products[products.length - 1];
          }
          const moveOut: StockMove = {
            id: newId("m-"), date: now, productId: src.id, productName: src.name,
            storeId: fromStoreId, toStoreId, type: "TRANSFER", delta: -qty,
            reason: `Transfert vers ${toStoreId}`, userId: by?.userId, userName: by?.userName,
          };
          return { products, moves: [moveOut, ...s.moves].slice(0, 1000) };
        });
        broadcastSync("kidzpos-data");
        void pushMutation(
          "/api/stock/transfer",
          "POST",
          { productId, targetStoreId: toStoreId, quantity: qty, clientMovementId },
          `transfer:${clientMovementId}`,
        );
        return { ok: true };
      },
      parkCart: (cart) => {
        const full: ParkedCart = { ...cart, id: newId("pk-"), createdAt: new Date().toISOString() };
        set((s) => ({ parked: [full, ...s.parked].slice(0, 20) }));
        broadcastSync("kidzpos-data");
      },
      unparkCart: (id) => {
        const c = get().parked.find((x) => x.id === id);
        if (c) {
          set((s) => ({ parked: s.parked.filter((x) => x.id !== id) }));
          broadcastSync("kidzpos-data");
        }
        return c;
      },
      addStore: (input) => {
        const name = input.name?.trim();
        const location = input.location?.trim() ?? "";
        if (!name) return { ok: false, error: "Nom requis" };
        if (get().stores.some((s) => s.name.toLowerCase() === name.toLowerCase())) {
          return { ok: false, error: "Un magasin porte déjà ce nom" };
        }
        const id = newId("store-");
        const store: Store = { id, name, location };
        set((s) => ({ stores: [...s.stores, store] }));
        broadcastSync("kidzpos-data");
        void pushMutation("/api/stores", "POST", store, `store:${id}`);
        return { ok: true, store };
      },
      updateStore: (id, patch) => {
        set((s) => ({ stores: s.stores.map((x) => (x.id === id ? { ...x, ...patch } : x)) }));
        broadcastSync("kidzpos-data");
        const updated = get().stores.find((s) => s.id === id);
        if (updated) void pushMutation(`/api/stores/${id}`, "PUT", updated, `store:${id}`);
      },
      deleteStore: (id) => {
        if (get().products.some((p) => p.storeId === id)) {
          return { ok: false, error: "Magasin contient des produits — supprimer ou transférer d'abord" };
        }
        // P1.3 : les ventes vivent dans useSales (non persisté), pas dans ce store.
        // `get().sales` serait undefined → crash. cf exportAll plus bas.
        if (useSales.getState().sales.some((s) => s.storeId === id)) {
          return { ok: false, error: "Magasin a un historique de ventes — suppression bloquée" };
        }
        set((s) => ({ stores: s.stores.filter((x) => x.id !== id) }));
        broadcastSync("kidzpos-data");
        void pushMutation(`/api/stores/${id}`, "DELETE", undefined, `store:${id}`);
        return { ok: true };
      },
      exportAll: () => JSON.stringify({
        stores: get().stores,
        products: get().products,
        sales: useSales.getState().sales,
      }, null, 2),
      importAll: (json) => {
        try {
          const data = JSON.parse(json);
          const parsed = BackupSchema.safeParse(data);
          if (!parsed.success) return { ok: false, error: "Format invalide" };
          const seq: Record<string, number> = {};
          for (const s of parsed.data.sales) seq[s.storeId] = Math.max(seq[s.storeId] ?? 0, s.seq ?? 0);
          set({ stores: parsed.data.stores, products: parsed.data.products });
          useSales.getState().setAll(parsed.data.sales, seq);
          broadcastSync("kidzpos-data");
          return { ok: true };
        } catch (e: unknown) { return { ok: false, error: e instanceof Error ? e.message : "Erreur" }; }
      },
      resetAll: () => {
        // Vide l'état local : le prochain syncBackend réhydratera depuis Postgres.
        set({ stores: [], products: [], moves: [], parked: [] });
        useSales.getState().clear();
        broadcastSync("kidzpos-data");
      },
    }),
    {
      name: "kidzpos-data",
      version: 6,
      migrate: (persisted: unknown, version) => {
        if (!persisted || typeof persisted !== "object") return persisted;
        const state = persisted as Record<string, unknown>;
        if (version < 2) {
          state.products = ((state.products ?? []) as Record<string, unknown>[]).map((p) => ({
            ...p,
            category: (p.category as string) || undefined,
            createdAt: (p.createdAt as string) ?? new Date().toISOString(),
          }));
          state.sales = ((state.sales ?? []) as Record<string, unknown>[]).map((s) => ({
            ...s,
            pointsEarned: (s.pointsEarned as number) ?? 0,
            pointsRedeemed: (s.pointsRedeemed as number) ?? 0,
            paymentMode: (s.paymentMode as string) ?? "CASH",
          }));
        }
        if (version < 3) {
          const seq: Record<string, number> = {};
          state.sales = ((state.sales ?? []) as Record<string, unknown>[]).map((s) => {
            const storeId = (s as Record<string, unknown>).storeId as string;
            seq[storeId] = (seq[storeId] ?? 0) + 1;
            return { ...(s as Record<string, unknown>), seq: ((s as Record<string, unknown>).seq as number) ?? seq[storeId] };
          });
          state.saleSeq = seq;
          state.moves = (state.moves ?? []) as unknown[];
          state.parked = (state.parked ?? []) as unknown[];
        }
        if (version < 4) {
          // Suppression des données seed de l'ère SQLite. Postgres est désormais
          // la source de vérité, syncBackend repeuplera au prochain démarrage.
          state.stores = [];
          state.products = [];
          state.sales = [];
          state.moves = [];
          state.parked = [];
          state.saleSeq = {};
        }
        if (version < 5) {
          // Bascule devise canonique EUR → AR (V5 backend). Toutes les valeurs
          // monétaires persistées étaient en EUR — on wipe pour éviter que des
          // produits "10 €" soient interprétés comme "10 Ar" au réaffichage.
          state.stores = [];
          state.products = [];
          state.sales = [];
          state.moves = [];
          state.parked = [];
          state.saleSeq = {};
        }
        if (version < 6) {
          // P1.3 : sales + saleSeq déplacés vers useSales (non persisté).
          // On retire les résidus pour ne pas mentir au consumer.
          delete state.sales;
          delete state.saleSeq;
        }
        return state;
      },
      // P1.3 : whitelist explicite des champs persistés — exclut tout ajout
      // futur de state qu'on voudrait garder volatile.
      partialize: (state) => ({
        stores: state.stores,
        products: state.products,
        moves: state.moves,
        parked: state.parked,
      }),
    }
  )
);
