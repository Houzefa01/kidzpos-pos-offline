import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, act, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { MemoryRouter } from "react-router-dom";
import POS from "./POS";
import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { useSales } from "@/store/sales";
import { useSettings } from "@/store/settings";
import { useCustomers } from "@/store/customers";
import { useExchange } from "@/store/exchange";
import { useBackend } from "@/store/backend";
import { outbox } from "@/lib/outbox";

/**
 * P3.2 — Test composant POS golden path checkout.
 *
 * Couvre : recherche produit → ajout panier → saisie montant CASH → clic
 * "Encaisser" → vente créée dans useSales → mutation enqueued dans outbox
 * (offline par défaut, useBackend.lanReachable === false).
 *
 * Choix de design : on utilise les vrais stores Zustand (setState directe pour
 * seeder), pas de mocks de stores. Le pushMutation passe par syncService qui
 * détecte lanReachable=false et enqueue dans outbox au lieu de fetcher → pas
 * besoin de mock fetch.
 */

// Sonner monte un Toaster portail global qui pollue le DOM des tests + déclenche
// des warnings React. On le neutralise : les toasts ne sont pas l'objet du test.
vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
  Toaster: () => null,
}));

const SEED_USER = {
  id: "u-admin",
  name: "Admin Test",
  email: "admin@test.com",
  role: "ADMIN" as const,
  storeId: null,
  active: true,
};

const SEED_STORE = { id: "s1", name: "Magasin Test", location: "" };

const SEED_PRODUCT = {
  id: "p-test-1",
  name: "Ours en peluche",
  price: 35000,         // Ariary
  stock: 5,
  storeId: "s1",
  sku: "KZ-S1-100",
  category: "Peluches",
  createdAt: new Date().toISOString(),
};

function seedStores() {
  // Reset complet entre tests pour isolation
  useAuth.setState({ user: SEED_USER, users: [SEED_USER], passwords: {}, attempts: {} });
  useData.setState({
    stores: [SEED_STORE],
    products: [SEED_PRODUCT],
    moves: [],
    parked: [],
  });
  useSales.setState({ sales: [], saleSeq: {} });
  useSettings.setState({
    settings: {
      maxDiscountPercent: 10,
      pointsPerAr: 0.0002,
      arPerPoint: 100,
      shopName: "KidzPOS",
      currency: "AR",
    },
  });
  useCustomers.setState({ customers: [] });
  useExchange.setState({ rate: 4900, fetchedAt: 0, source: "manual" });
  useBackend.setState({ lanReachable: false, pendingCount: 0, lastSync: null });
  outbox.clear();
}

function renderPOS() {
  return render(
    <MemoryRouter>
      <POS />
    </MemoryRouter>,
  );
}

describe("POS — golden path checkout", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    seedStores();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it("ajoute un produit au panier, encaisse en CASH et crée la vente dans useSales", async () => {
    renderPOS();

    // 1. Le champ de recherche est focusable et présent
    const searchInput = screen.getByLabelText(/Rechercher \/ scanner/i);
    expect(searchInput).toBeInTheDocument();

    // 2. Taper le nom du produit → debouncedSearch (100ms) fait apparaître la listbox
    fireEvent.change(searchInput, { target: { value: "Ours" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(150);
    });

    // 3. La listbox doit contenir le produit
    const option = await screen.findByRole("option", { name: /Ours en peluche/i });
    expect(option).toBeInTheDocument();

    // 4. Clic ajoute au panier (cart state interne)
    fireEvent.click(option);

    // 5. Mode paiement = CASH par défaut → input "Reçu" visible. Saisir 50_000 Ar
    //    (assez pour couvrir le total 35_000).
    const amountPaid = screen.getByLabelText(/Reçu \(/i);
    fireEvent.change(amountPaid, { target: { value: "50000" } });

    // 6. Clic Encaisser
    const checkoutBtn = screen.getByRole("button", { name: /Encaisser la vente/i });
    expect(checkoutBtn).not.toBeDisabled();
    fireEvent.click(checkoutBtn);

    // 7. Vente créée côté useSales (source de vérité depuis P1.3)
    const sales = useSales.getState().sales;
    expect(sales).toHaveLength(1);
    const sale = sales[0];
    expect(sale.storeId).toBe("s1");
    expect(sale.userId).toBe("u-admin");
    expect(sale.items).toHaveLength(1);
    expect(sale.items[0]).toMatchObject({
      productId: "p-test-1",
      name: "Ours en peluche",
      quantity: 1,
      price: 35000,
    });
    expect(sale.subtotal).toBe(35000);
    expect(sale.total).toBe(35000);
    expect(sale.paymentMode).toBe("CASH");
    expect(sale.amountPaid).toBe(50000);
    expect(sale.change).toBe(15000);
    expect(sale.seq).toBe(1);
    // P2.3 : ID au format UUID (avec préfixe sale- ou sale-...-r pour refund)
    expect(sale.id).toMatch(/^sale-/);

    // 8. Stock décrémenté côté useData
    const products = useData.getState().products;
    expect(products[0].stock).toBe(4);

    // 9. Offline (lanReachable=false) → la mutation a été enqueued dans l'outbox,
    //    pas envoyée. Garde l'idempotence offline-first.
    const queue = outbox.list();
    expect(queue).toHaveLength(1);
    expect(queue[0].path).toBe("/api/sales/checkout");
    expect(queue[0].method).toBe("POST");
    expect(queue[0].ref).toBe(`sale:${sale.id}`);
    // Le body porte clientSaleId = sale.id (idempotence I8 backend)
    expect((queue[0].body as { clientSaleId: string }).clientSaleId).toBe(sale.id);
  });

  it("refuse le checkout si le montant CASH est insuffisant", async () => {
    renderPOS();
    const { toast } = await import("sonner");

    const searchInput = screen.getByLabelText(/Rechercher \/ scanner/i);
    fireEvent.change(searchInput, { target: { value: "Ours" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(150);
    });
    fireEvent.click(await screen.findByRole("option", { name: /Ours en peluche/i }));

    // Montant insuffisant : 10_000 < total 35_000
    fireEvent.change(screen.getByLabelText(/Reçu \(/i), { target: { value: "10000" } });
    fireEvent.click(screen.getByRole("button", { name: /Encaisser la vente/i }));

    // Aucune vente créée, aucune mutation enfilée
    expect(useSales.getState().sales).toHaveLength(0);
    expect(outbox.size()).toBe(0);
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/Montant.*insuffisant/i));
  });
});

// Import différé pour éviter d'altérer le top-level avant le mock sonner.
import { afterEach } from "vitest";
