import { useMemo, useRef, useState } from "react";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { useHotkeys } from "@/hooks/useHotkeys";
import { useAuth } from "@/store/auth";
import { useData, Product, SaleItem, Sale, PaymentMode } from "@/store/data";
import { useSales } from "@/store/sales";
import { useSettings } from "@/store/settings";
import { useExchange } from "@/store/exchange";
import { useCustomers, Customer } from "@/store/customers";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { CategoryCombobox } from "@/components/CategoryCombobox";
import { CustomerPicker } from "@/components/CustomerPicker";
import {
  Plus, Minus, Trash2, Banknote, Star, Keyboard, ScanLine,
  Pause, Play, CreditCard, Smartphone, Shuffle, Wallet, ShoppingCart, X,
} from "lucide-react";
import { toast } from "sonner";
import { useFormatMoney, parseMoneyToAr, currencySymbol } from "@/lib/money";
import { Button, CategoryIcon, PageHeader, SearchInput, FilterSelect, KbdHint, ReceiptOverlay, StatusDot } from "@/components/ds";

export default function POS() {
  const { user } = useAuth();
  const fmt = useFormatMoney();
  const { products, stores, addProduct, parked, parkCart, unparkCart } = useData();
  const addSale = useSales((s) => s.addSale);
  const { settings } = useSettings();
  const rate = useExchange((s) => s.rate);
  const { applyPurchase } = useCustomers();
  const isAdmin = user?.role === "ADMIN";
  const [activeStore, setActiveStore] = useState<string>(user?.storeId ?? "s1");
  const storeId = isAdmin ? activeStore : (user?.storeId ?? "s1");

  const [search, setSearch] = useState("");
  const debouncedSearch = useDebouncedValue(search, 100);
  const [discount, setDiscount] = useState(0);
  const [cart, setCart] = useState<SaleItem[]>([]);
  const [receipt, setReceipt] = useState<Sale | null>(null);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [redeemPoints, setRedeemPoints] = useState(0);
  const [paymentMode, setPaymentMode] = useState<PaymentMode>("CASH");
  const [amountPaidInput, setAmountPaidInput] = useState<number>(0);
  const [saleCurrency, setSaleCurrency] = useState<"AR" | "EUR">(settings.currency);
  const [quickOpen, setQuickOpen] = useState(false);
  const [showHotkeys, setShowHotkeys] = useState(false);
  const [cartOpen, setCartOpen] = useState(false);

  const searchRef = useRef<HTMLInputElement>(null);

  const storeIndex = useMemo(() => {
    const list: { product: Product; haystack: string; skuLower: string }[] = [];
    for (const p of products) {
      if (p.storeId !== storeId || p.stock <= 0) continue;
      list.push({
        product: p,
        haystack: `${p.name} ${p.sku} ${p.category ?? ""}`.toLowerCase(),
        skuLower: p.sku.toLowerCase(),
      });
    }
    return list;
  }, [products, storeId]);

  const filtered = useMemo(() => {
    const q = debouncedSearch.trim().toLowerCase();
    if (!q) return storeIndex.slice(0, 24).map((e) => e.product);
    const exactSku = storeIndex.find((e) => e.skuLower === q);
    if (exactSku) return [exactSku.product];
    const out: Product[] = [];
    for (const e of storeIndex) {
      if (e.haystack.includes(q)) {
        out.push(e.product);
        if (out.length >= 24) break;
      }
    }
    return out;
  }, [storeIndex, debouncedSearch]);

  const allCategories = useMemo(() => {
    const s = new Set<string>();
    products.forEach((p) => p.category && s.add(p.category));
    return Array.from(s).sort();
  }, [products]);

  const addToCart = (p: Product) => {
    setCart((c) => {
      const existing = c.find((i) => i.productId === p.id);
      if (existing) {
        if (existing.quantity >= p.stock) { toast.error("Stock insuffisant"); return c; }
        return c.map((i) => i.productId === p.id ? { ...i, quantity: i.quantity + 1 } : i);
      }
      return [...c, { productId: p.id, name: p.name, price: p.price, quantity: 1 }];
    });
  };

  const updateQty = (id: string, delta: number) => {
    setCart((c) =>
      c.flatMap((i) => {
        if (i.productId !== id) return [i];
        const product = products.find((p) => p.id === id);
        const newQ = i.quantity + delta;
        if (newQ <= 0) return [];
        if (product && newQ > product.stock) { toast.error("Stock insuffisant"); return [i]; }
        return [{ ...i, quantity: newQ }];
      })
    );
  };

  // ── Computations ──
  const subtotal = +cart.reduce((a, i) => a + i.price * i.quantity, 0).toFixed(2);
  const maxDiscount = isAdmin ? 100 : settings.maxDiscountPercent;
  const safeDiscount = Math.max(0, Math.min(maxDiscount, discount));
  const discountAmt = +(subtotal * (safeDiscount / 100)).toFixed(2);
  const maxRedeemable = customer ? customer.points : 0;
  const usedPoints = Math.max(0, Math.min(maxRedeemable, redeemPoints));
  const pointsValue = +(usedPoints * settings.arPerPoint).toFixed(2);
  const total = +Math.max(0, subtotal - discountAmt - pointsValue).toFixed(2);
  const amountPaidAr = parseMoneyToAr(amountPaidInput, saleCurrency, rate);
  const change = paymentMode === "CASH" && amountPaidAr > 0 ? +(amountPaidAr - total).toFixed(2) : 0;

  const cartCount = cart.reduce((a, i) => a + i.quantity, 0);

  const checkout = () => {
    if (!user) { toast.error("Session expirée"); return; }
    if (cart.length === 0) return;
    if (paymentMode === "CASH") {
      if (amountPaidAr <= 0) { toast.error("Saisissez le montant reçu"); return; }
      if (amountPaidAr < total) { toast.error("Montant reçu insuffisant"); return; }
    }
    const pointsEarned = customer ? Math.floor(total * settings.pointsPerAr) : 0;
    const sale = addSale({
      storeId,
      userId: user.id,
      userName: user.name,
      items: cart,
      subtotal,
      discount: discountAmt,
      total,
      customerId: customer?.id,
      customerName: customer?.name || customer?.phone,
      pointsEarned,
      pointsRedeemed: usedPoints,
      paymentMode,
      amountPaid: paymentMode === "CASH" ? amountPaidAr : total,
      change,
      currency: saleCurrency,
    });
    if (customer) applyPurchase(customer.id, total, pointsEarned, usedPoints);
    setReceipt(sale);
    setCart([]);
    setDiscount(0);
    setCustomer(null);
    setRedeemPoints(0);
    setPaymentMode("CASH");
    setAmountPaidInput(0);
    setCartOpen(false);
    toast.success("Vente validée");
  };

  useHotkeys({
    f2: (e) => { e.preventDefault(); searchRef.current?.focus(); searchRef.current?.select(); },
    f9: (e) => { e.preventDefault(); checkout(); },
    escape: () => { setSearch(""); },
  });

  const onSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      const q = search.trim().toLowerCase();
      if (!q) return;
      const exact = storeIndex.find((p) => p.skuLower === q);
      if (exact) {
        addToCart(exact.product);
        setSearch("");
      } else if (filtered.length === 1) {
        addToCart(filtered[0]);
        setSearch("");
      }
    }
  };

  return (
    <div className="pb-24 md:flex md:h-[calc(100vh-7rem)] md:flex-col md:gap-4 md:pb-0">
      {/* ═══ STOCK : zone compressée sur desktop (shrink-0 + grid plus haut → moins de colonnes verticales)
           Le caissier scanne / cherche en priorité — la grille est un fallback visuel. */}
      <section className="flex flex-col gap-4 md:shrink-0 md:gap-5">
        <PageHeader
          eyebrow="Caisse"
          title="Encaissement"
          actions={
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setShowHotkeys(true)}
              className="h-9 rounded-md text-muted-foreground hover:text-foreground"
              title="Raccourcis"
              aria-label="Afficher les raccourcis clavier"
            >
              <Keyboard className="mr-2 h-4 w-4" aria-hidden="true" />
              <span className="hidden sm:inline">Raccourcis</span>
            </Button>
          }
        />

        {/* Search + actions bar */}
        <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:flex-wrap">
          {/* Search input + dropdown résultats live (visible uniquement pendant la recherche) */}
          <div className="relative min-w-0 flex-1">
            <SearchInput
              ref={searchRef}
              icon={ScanLine}
              kbdHint="F2"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={onSearchKeyDown}
              placeholder="Rechercher / scanner code-barres ou SKU"
              aria-label="Rechercher / scanner un produit"
              autoFocus
              className="h-10 rounded-lg border-border bg-card pl-10 text-sm focus-visible:ring-2 focus-visible:ring-primary/30"
            />
            {debouncedSearch.trim() && (
              <div
                role="listbox"
                aria-label="Résultats de recherche"
                className="absolute inset-x-0 top-full z-30 mt-1 max-h-80 overflow-y-auto rounded-lg border border-border bg-card shadow-elevated"
              >
                {filtered.length === 0 ? (
                  <p className="p-4 text-center text-sm text-muted-foreground">
                    Aucun produit ne correspond à « {debouncedSearch.trim()} ».
                  </p>
                ) : (
                  <ul>
                    {filtered.map((p) => {
                      const low = p.stock <= 3;
                      return (
                        <li key={p.id}>
                          <button
                            type="button"
                            role="option"
                            aria-selected="false"
                            onClick={() => { addToCart(p); setSearch(""); searchRef.current?.focus(); }}
                            className="flex w-full items-center justify-between gap-3 border-b border-border px-4 py-3 text-left transition last:border-b-0 hover:bg-secondary focus-visible:bg-secondary focus-visible:outline-none"
                          >
                            <div className="flex min-w-0 items-center gap-3">
                              <CategoryIcon category={p.category} />
                              <div className="min-w-0">
                                <p className="truncate text-sm font-medium">{p.name}</p>
                                <p className="mt-0.5 truncate font-mono text-2xs uppercase tracking-eyebrow text-muted-foreground">
                                  {p.sku}{p.category ? ` · ${p.category}` : ""}
                                </p>
                              </div>
                            </div>
                            <div className="flex shrink-0 items-center gap-2">
                              <span className="font-mono text-sm font-semibold tabular-nums">
                                {fmt(p.price, saleCurrency)}
                              </span>
                              <span className={`rounded px-1.5 py-0.5 font-mono text-2xs font-medium ${low ? "bg-warning/15 text-warning-foreground" : "bg-secondary text-muted-foreground"}`}>
                                ×{p.stock}
                              </span>
                            </div>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            )}
          </div>
          {isAdmin && (
            <FilterSelect
              value={activeStore}
              onValueChange={setActiveStore}
              options={stores.map((s) => ({ value: s.id, label: s.name }))}
              allLabel={null}
              aria-label="Magasin actif"
              triggerClassName="h-10 rounded-lg border-border bg-card sm:w-48"
            />
          )}
          {isAdmin && (
            <Button
              variant="outline"
              onClick={() => setQuickOpen(true)}
              className="h-10 rounded-lg border-border bg-card hover:bg-secondary"
              title="Nouveau produit rapide"
              aria-label="Créer un nouveau produit rapidement"
            >
              <Plus className="mr-1.5 h-4 w-4" aria-hidden="true" /> Produit
            </Button>
          )}
          <Button
            variant="outline"
            onClick={() => {
              if (cart.length === 0) { toast.error("Panier vide"); return; }
              parkCart({ storeId, userId: user!.id, items: cart, customerId: customer?.id, customerName: customer?.name, label: `${cart.length} art.` });
              setCart([]); setCustomer(null);
              toast.success("Panier mis en attente");
            }}
            className="h-10 rounded-lg border-border bg-card hover:bg-secondary"
            title="Mettre en attente"
            aria-label="Mettre le panier en attente"
          >
            <Pause className="mr-1.5 h-4 w-4" aria-hidden="true" /> Park
          </Button>
          {parked.length > 0 && (
            <Select
              onValueChange={(id) => {
                const c = unparkCart(id);
                if (c) { setCart(c.items); toast.success("Panier repris"); }
              }}
            >
              <SelectTrigger
                className="h-10 w-36 rounded-lg border-border bg-card"
                aria-label={`Reprendre un panier en attente (${parked.length})`}
              >
                <Play className="mr-1 h-3 w-3" aria-hidden="true" />
                <SelectValue placeholder={`${parked.length} en attente`} />
              </SelectTrigger>
              <SelectContent>
                {parked.map((p) => (
                  <SelectItem key={p.id} value={p.id}>
                    {p.label} — {new Date(p.createdAt).toLocaleTimeString("fr-FR")}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

      </section>

      {/* Mobile backdrop when cart sheet is open */}
      {cartOpen && (
        <div
          onClick={() => setCartOpen(false)}
          aria-hidden="true"
          className="fixed inset-0 z-40 bg-foreground/40 backdrop-blur-sm md:hidden"
        />
      )}

      {/* ═══ Cart panel — 2 colonnes sur desktop, sheet 1 col empilée sur mobile
          DESKTOP (md+) : `md:flex-row md:flex-1`
            ├ COLUMN 1 (md:flex-1)  : panier articles + calculs scrollables
            └ COLUMN 2 (md:w-96)    : paiement / devise / reçu — puis TOTAL + Encaisser pinned bas
          MOBILE  : `flex-col` (fixed bottom sheet), COLUMN 1 au-dessus de COLUMN 2.
          La structure 2-col rend les articles immédiatement visibles à gauche,
          le caissier voit le panier en un coup d'œil — pas besoin de scroller.
       */}
      <aside
        aria-label="Panier"
        className={`fixed inset-x-0 bottom-0 z-50 flex max-h-[92dvh] flex-col overflow-hidden rounded-t-2xl border-t border-border bg-card shadow-elevated transition-transform duration-300 md:relative md:z-auto md:max-h-full md:min-h-0 md:flex-1 md:flex-row md:rounded-xl md:border md:shadow-card md:transition-none ${
          cartOpen ? "translate-y-0" : "translate-y-full md:translate-y-0"
        }`}
      >
        {/* Mobile-only close button + drag handle */}
        <div className="flex shrink-0 items-center justify-center py-2 md:hidden" aria-hidden="true">
          <span className="h-1 w-10 rounded-full bg-border" />
        </div>
        <button
          type="button"
          onClick={() => setCartOpen(false)}
          aria-label="Fermer le panier"
          className="absolute right-3 top-3 z-10 grid h-8 w-8 place-items-center rounded-full text-muted-foreground transition hover:bg-secondary hover:text-foreground md:hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <X className="h-4 w-4" aria-hidden="true" />
        </button>

        {/* ═══ COLUMN 1 : articles + calculs (gauche desktop, haut mobile) ═══ */}
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
          {/* HEADER : titre + customer */}
          <div className="shrink-0">
            <div className="flex items-center justify-between border-b border-border px-5 py-3.5">
              <div className="flex items-baseline gap-2.5">
                <h2 className="text-base font-semibold tracking-display">Panier</h2>
                <span className="font-mono text-xs text-muted-foreground" aria-label={`${cartCount} articles`}>
                  {cartCount} art.
                </span>
              </div>
              <StatusDot tone="primary" />
            </div>
            <div className="border-b border-border px-5 py-3">
              <CustomerPicker value={customer} onChange={(c) => { setCustomer(c); setRedeemPoints(0); }} />
            </div>
          </div>

          {/* BODY scrollable : articles + calculs intermédiaires */}
          <div className="min-h-0 flex-1 overflow-y-auto">
            <div className="space-y-1.5 px-5 py-3">
              {cart.length === 0 && (
                <div className="flex flex-col items-center justify-center py-12 text-center" role="status">
                  <p className="text-sm text-muted-foreground">
                    Sélectionnez des produits…
                  </p>
                </div>
              )}
              {cart.map((i) => (
                <div
                  key={i.productId}
                  className="group rounded-lg border border-border bg-card p-3 transition hover:border-foreground/15"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium leading-snug">{i.name}</p>
                      <p className="mt-0.5 font-mono text-2xs text-muted-foreground">
                        {fmt(i.price, saleCurrency)} · l'unité
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setCart((c) => c.filter((x) => x.productId !== i.productId))}
                      aria-label={`Retirer ${i.name} du panier`}
                      className="grid h-8 w-8 place-items-center rounded-md text-muted-foreground/60 transition hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      title="Retirer"
                    >
                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </div>
                  <div className="mt-2 flex items-center justify-between">
                    <div className="inline-flex items-center rounded-md border border-border bg-secondary p-0.5">
                      <button
                        type="button"
                        onClick={() => updateQty(i.productId, -1)}
                        aria-label={`Diminuer la quantité de ${i.name}`}
                        className="grid h-8 w-8 place-items-center rounded-sm text-muted-foreground transition hover:bg-background hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        <Minus className="h-3.5 w-3.5" aria-hidden="true" />
                      </button>
                      <span className="w-8 text-center font-mono text-xs font-semibold" aria-live="polite">{i.quantity}</span>
                      <button
                        type="button"
                        onClick={() => updateQty(i.productId, 1)}
                        aria-label={`Augmenter la quantité de ${i.name}`}
                        className="grid h-8 w-8 place-items-center rounded-sm text-muted-foreground transition hover:bg-background hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
                      </button>
                    </div>
                    <span className="font-mono text-sm font-semibold tracking-display">
                      {fmt(i.quantity * i.price, saleCurrency)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* ═══ COLUMN 2 : calculs + paiement + TOTAL + CTA (droite desktop, bas mobile) ═══ */}
        <div className="flex shrink-0 flex-col border-t border-border bg-card md:w-96 md:border-l md:border-t-0">
          {/* Contrôles paiement — scrollable si trop hauts (rare) */}
          <div className="space-y-4 px-5 py-4 md:min-h-0 md:flex-1 md:overflow-y-auto">
            {/* Calculs : sous-total, remise, points — visibles si panier non vide */}
            {cart.length > 0 && (
              <div className="space-y-2">
                <Row label="Sous-total" value={fmt(subtotal, saleCurrency)} />

                <div className="flex items-center justify-between text-sm">
                  <label className="text-muted-foreground" htmlFor="discount">
                    Remise % {!isAdmin && <span className="text-2xs">(max {settings.maxDiscountPercent}%)</span>}
                  </label>
                  <Input
                    id="discount"
                    type="number"
                    min={0}
                    max={maxDiscount}
                    value={discount}
                    onChange={(e) => {
                      const v = +e.target.value || 0;
                      if (!isAdmin && v > settings.maxDiscountPercent) {
                        toast.error(`Remise plafonnée à ${settings.maxDiscountPercent}%`);
                      }
                      setDiscount(Math.max(0, Math.min(maxDiscount, v)));
                    }}
                    className="h-8 w-20 rounded-md border-border bg-secondary text-right font-mono text-xs"
                  />
                </div>

                {customer && customer.points > 0 && (
                  <div className="flex items-center justify-between rounded-md bg-warning/10 px-3 py-1.5 text-sm">
                    <label className="flex items-center gap-1.5 text-warning-foreground/85" htmlFor="points">
                      <Star className="h-3.5 w-3.5 text-warning" aria-hidden="true" /> Points (max {customer.points})
                    </label>
                    <Input
                      id="points"
                      type="number"
                      min={0}
                      max={maxRedeemable}
                      value={redeemPoints}
                      onChange={(e) => setRedeemPoints(Math.max(0, Math.min(maxRedeemable, +e.target.value || 0)))}
                      className="h-8 w-20 rounded-md border-warning/40 bg-background text-right font-mono text-xs"
                    />
                  </div>
                )}
                {usedPoints > 0 && (
                  <Row label="− Réduction fidélité" value={`−${fmt(pointsValue, saleCurrency)}`} tone="warning" />
                )}
              </div>
            )}

            {/* Mode de paiement */}
            <div>
              <p className="mb-1.5 text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">
                Paiement
              </p>
              <div className="grid grid-cols-4 gap-1.5" role="radiogroup" aria-label="Mode de paiement">
                <PayChip active={paymentMode === "CASH"} onClick={() => setPaymentMode("CASH")} icon={<Wallet className="h-4 w-4" aria-hidden="true" />} label="Espèces" />
                <PayChip active={paymentMode === "CARD"} onClick={() => setPaymentMode("CARD")} icon={<CreditCard className="h-4 w-4" aria-hidden="true" />} label="Carte" />
                <PayChip active={paymentMode === "MOBILE_MONEY"} onClick={() => setPaymentMode("MOBILE_MONEY")} icon={<Smartphone className="h-4 w-4" aria-hidden="true" />} label="Mobile" />
                <PayChip active={paymentMode === "MIXED"} onClick={() => setPaymentMode("MIXED")} icon={<Shuffle className="h-4 w-4" aria-hidden="true" />} label="Mixte" />
              </div>
            </div>

            {/* Devise */}
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground" id="currency-label">Devise</span>
              <div className="inline-flex rounded-md border border-border bg-secondary p-0.5 text-xs" role="group" aria-labelledby="currency-label">
                <button
                  type="button"
                  onClick={() => setSaleCurrency("AR")}
                  aria-pressed={saleCurrency === "AR"}
                  aria-label="Devise Ariary"
                  className={`rounded-sm px-2.5 py-1 font-mono transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    saleCurrency === "AR" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  Ar
                </button>
                <button
                  type="button"
                  onClick={() => setSaleCurrency("EUR")}
                  aria-pressed={saleCurrency === "EUR"}
                  aria-label="Devise Euro"
                  className={`rounded-sm px-2.5 py-1 font-mono transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    saleCurrency === "EUR" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  €
                </button>
              </div>
            </div>

            {/* Reçu (CASH) + rendu monnaie */}
            {paymentMode === "CASH" && (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between text-sm">
                  <label className="text-muted-foreground" htmlFor="amount-paid">Reçu ({currencySymbol(saleCurrency)})</label>
                  <Input
                    id="amount-paid"
                    type="number"
                    step={saleCurrency === "AR" ? "1" : "0.01"}
                    min={0}
                    value={amountPaidInput || ""}
                    onChange={(e) => setAmountPaidInput(+e.target.value || 0)}
                    placeholder={saleCurrency === "AR" ? "0" : "0.00"}
                    className="h-8 w-24 rounded-md border-border bg-secondary text-right font-mono text-xs"
                  />
                </div>
                {change > 0 && (
                  <div className="flex animate-fade-in items-center justify-between rounded-md bg-success/10 px-3 py-1.5 text-sm font-medium text-success" role="status">
                    <span>Rendu monnaie</span>
                    <span className="font-mono">{fmt(change, saleCurrency)}</span>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* TOTAL + Encaisser — toujours visibles au bas de COLUMN 2 */}
          <div className="shrink-0 space-y-3 border-t border-border bg-card px-5 py-4">
            <div className="flex items-baseline justify-between">
              <span className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">
                Total
              </span>
              <div className="flex items-baseline gap-1">
                <span className="font-mono text-4xl font-semibold leading-none tracking-display">
                  {fmt(total, saleCurrency, { withSymbol: false })}
                </span>
                <span className="text-sm font-medium text-muted-foreground">
                  {currencySymbol(saleCurrency)}
                </span>
              </div>
            </div>

            <Button
              variant="gradient"
              disabled={cart.length === 0}
              onClick={checkout}
              aria-label="Encaisser la vente (F9)"
              title="Encaisser (F9)"
              className="h-12 w-full rounded-lg text-sm font-medium transition-transform active:scale-[0.99] disabled:opacity-50"
            >
              <Banknote className="mr-2 h-4 w-4" aria-hidden="true" />
              Encaisser
            </Button>
          </div>
        </div>
      </aside>

      {/* Mobile-only floating CTA — opens the cart sheet */}
      {!cartOpen && (
        <button
          type="button"
          onClick={() => setCartOpen(true)}
          aria-label={cart.length === 0 ? "Ouvrir le panier (vide)" : `Ouvrir le panier (${cartCount} articles, ${fmt(total, saleCurrency)})`}
          className="fixed bottom-4 right-4 z-30 flex h-12 items-center gap-3 rounded-full bg-primary px-4 pr-5 text-sm font-medium text-primary-foreground shadow-lg shadow-primary/30 transition hover:bg-primary/90 md:hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          <span className="relative grid h-7 w-7 place-items-center rounded-full bg-primary-foreground/15" aria-hidden="true">
            <ShoppingCart className="h-3.5 w-3.5" />
            {cartCount > 0 && (
              <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-accent px-1 font-mono text-2xs font-bold text-accent-foreground">
                {cartCount}
              </span>
            )}
          </span>
          <span className="font-mono tabular-nums">
            {cart.length === 0 ? "Panier" : fmt(total, saleCurrency)}
          </span>
        </button>
      )}

      {/* Quick-add product */}
      {isAdmin && (
        <QuickProductDialog
          open={quickOpen}
          onOpenChange={setQuickOpen}
          storeId={storeId}
          categories={allCategories}
          onCreate={(form) => {
            const res = addProduct({ ...form, storeId });
            if (!res.ok) { toast.error(res.error ?? "Erreur"); return false; }
            toast.success("Produit ajouté");
            return true;
          }}
        />
      )}

      {/* Hotkeys help */}
      <Dialog open={showHotkeys} onOpenChange={setShowHotkeys}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="text-lg font-semibold tracking-display">Raccourcis clavier</DialogTitle>
          </DialogHeader>
          <div className="space-y-2 text-sm">
            <HotkeyRow label="Focus recherche" k="F2" />
            <HotkeyRow label="Encaisser" k="F9" />
            <HotkeyRow label="Vider la recherche" k="Échap" />
            <HotkeyRow label="Ajouter SKU exact" k="Entrée" />
          </div>
        </DialogContent>
      </Dialog>

      {/* Receipt overlay (DS) */}
      <ReceiptOverlay
        sale={receipt}
        store={receipt ? stores.find((s) => s.id === receipt.storeId) : undefined}
        settings={settings}
        onClose={() => setReceipt(null)}
      />
    </div>
  );
}

/* ── Tiny building blocks ───────────────────────────────────────── */

function Row({ label, value, tone }: { label: string; value: string; tone?: "warning" }) {
  return (
    <div className={`flex items-center justify-between text-sm ${tone === "warning" ? "text-warning-foreground/85" : ""}`}>
      <span className="text-muted-foreground">{label}</span>
      <span className="font-mono">{value}</span>
    </div>
  );
}

function PayChip({
  active, onClick, icon, label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={active}
      onClick={onClick}
      aria-label={`Paiement : ${label}`}
      className={`flex h-12 flex-col items-center justify-center gap-1 rounded-md border px-2 py-2 text-label font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 ${
        active
          ? "border-primary bg-primary/10 text-primary"
          : "border-border bg-card text-muted-foreground hover:border-foreground/20 hover:text-foreground"
      }`}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function HotkeyRow({ label, k }: { label: string; k: string }) {
  return (
    <div className="flex items-center justify-between rounded-lg bg-secondary/40 px-3 py-2">
      <span>{label}</span>
      <KbdHint variant="inline">{k}</KbdHint>
    </div>
  );
}

function QuickProductDialog({
  open, onOpenChange, storeId, categories, onCreate,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  storeId: string;
  categories: string[];
  onCreate: (form: { name: string; sku: string; price: number; stock: number; category?: string }) => boolean;
}) {
  const [form, setForm] = useState({ name: "", sku: "", price: 0, stock: 1, category: undefined as string | undefined });

  const submit = (keepOpen: boolean) => {
    if (!form.name.trim() || form.price <= 0) {
      toast.error("Nom et prix requis");
      return;
    }
    const sku = form.sku.trim() || `KZ-${storeId.toUpperCase()}-${Date.now().toString().slice(-5)}`;
    const ok = onCreate({ ...form, sku, name: form.name.trim() });
    if (ok) {
      setForm({ name: "", sku: "", price: 0, stock: 1, category: form.category });
      if (!keepOpen) onOpenChange(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="text-lg font-semibold tracking-display">Nouveau produit</DialogTitle>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <div className="col-span-2 space-y-1.5">
            <Label htmlFor="qp-name">Nom *</Label>
            <Input id="qp-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="qp-sku">Référence (SKU)</Label>
            <Input id="qp-sku" value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} placeholder="auto" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="qp-price">Prix (Ar HT) *</Label>
            <Input id="qp-price" type="number" step="1" min={0} value={form.price || ""} onChange={(e) => setForm({ ...form, price: +e.target.value || 0 })} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="qp-stock">Stock</Label>
            <Input id="qp-stock" type="number" value={form.stock} onChange={(e) => setForm({ ...form, stock: +e.target.value || 0 })} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="qp-category">Catégorie</Label>
            <CategoryCombobox value={form.category} onChange={(v) => setForm({ ...form, category: v })} options={categories} />
          </div>
        </div>
        <DialogFooter className="gap-2">
          <Button variant="outline" className="rounded-lg" onClick={() => submit(true)}>
            Ajouter &amp; continuer
          </Button>
          <Button variant="gradient" className="rounded-lg" onClick={() => submit(false)}>
            Ajouter
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

