import { useMemo, useState } from "react";
import { useAuth } from "@/store/auth";
import { useData, Product, StockMove } from "@/store/data";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { CategoryCombobox } from "@/components/CategoryCombobox";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Plus, Pencil, Trash2, ArrowUp, ArrowDown, ArrowUpDown, Upload, ArrowLeftRight, PackagePlus } from "lucide-react";
import { toast } from "sonner";
import { ProductCsvRowSchema } from "@/lib/schemas";
import { useFormatMoney } from "@/lib/money";
import { Button, CategoryIcon, IconButton, PageHeader, Section, SearchInput, FilterSelect } from "@/components/ds";

type SortKey = "name" | "sku" | "category" | "price" | "stock" | "createdAt";
type SortDir = "asc" | "desc";

export default function Stock() {
  const { user } = useAuth();
  const fmt = useFormatMoney();
  const { products, stores, moves, addProduct, updateProduct, deleteProduct, adjustStock, transferStock, bulkImportProducts } = useData();
  const isAdmin = user?.role === "ADMIN";

  const [search, setSearch] = useState("");
  const [filterStore, setFilterStore] = useState<string>(isAdmin ? "all" : (user?.storeId ?? "all"));
  const [filterCategory, setFilterCategory] = useState<string>("all");
  const [stockFilter, setStockFilter] = useState<"all" | "low" | "out">("all");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  const [editing, setEditing] = useState<Product | null>(null);
  const [open, setOpen] = useState(false);
  const [adjustOpen, setAdjustOpen] = useState<Product | null>(null);
  const [transferOpen, setTransferOpen] = useState<Product | null>(null);

  const allCategories = useMemo(() => {
    const s = new Set<string>();
    products.forEach((p) => p.category && s.add(p.category));
    return Array.from(s).sort();
  }, [products]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = products.filter((p) => {
      if (!isAdmin && p.storeId !== user?.storeId) return false;
      if (filterStore !== "all" && p.storeId !== filterStore) return false;
      if (filterCategory !== "all" && (p.category ?? "") !== filterCategory) return false;
      if (stockFilter === "low" && (p.stock === 0 || p.stock > 3)) return false;
      if (stockFilter === "out" && p.stock !== 0) return false;
      if (q && !`${p.name} ${p.sku} ${p.category ?? ""}`.toLowerCase().includes(q)) return false;
      return true;
    });
    const dir = sortDir === "asc" ? 1 : -1;
    list.sort((a, b) => {
      const av = (a[sortKey] ?? "") as string | number;
      const bv = (b[sortKey] ?? "") as string | number;
      if (typeof av === "number" && typeof bv === "number") return (av - bv) * dir;
      return String(av).localeCompare(String(bv), "fr") * dir;
    });
    return list;
  }, [products, search, filterStore, filterCategory, stockFilter, isAdmin, user, sortKey, sortDir]);

  const scopedMoves = useMemo(
    () => isAdmin ? moves : moves.filter((m) => m.storeId === user?.storeId),
    [moves, isAdmin, user]
  );

  const toggleSort = (k: SortKey) => {
    if (sortKey === k) setSortDir(sortDir === "asc" ? "desc" : "asc");
    else { setSortKey(k); setSortDir("asc"); }
  };

  const SortHeader = ({ k, label, align = "left" }: { k: SortKey; label: string; align?: "left" | "right" }) => (
    <button onClick={() => toggleSort(k)} className={`flex w-full items-center gap-1 ${align === "right" ? "justify-end" : ""} hover:text-primary`}>
      {label}
      {sortKey === k ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />) : <ArrowUpDown className="h-3 w-3 opacity-40" />}
    </button>
  );

  const onSave = (form: Omit<Product, "id" | "createdAt">) => {
    if (editing) {
      const dup = products.find((p) => p.id !== editing.id && p.storeId === form.storeId && p.sku.toLowerCase() === form.sku.toLowerCase());
      if (dup) { toast.error("SKU déjà utilisé dans ce magasin"); return; }
      updateProduct(editing.id, form);
      toast.success("Produit mis à jour");
    } else {
      const res = addProduct(form);
      if (!res.ok) { toast.error(res.error ?? "Erreur"); return; }
      toast.success("Produit ajouté");
    }
    setOpen(false);
    setEditing(null);
  };

  const onCsvImport = (file: File) => {
    if (file.size > 2_000_000) { toast.error("Fichier trop volumineux (max 2 Mo)"); return; }
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const text = String(reader.result);
        const lines = text.split(/\r?\n/).filter((l) => l.trim());
        if (lines.length < 2) return toast.error("CSV vide");
        if (lines.length > 10_000) return toast.error("CSV trop volumineux (max 10 000 lignes)");
        const headers = lines[0].split(/[,;]/).map((h) => h.trim().toLowerCase().replace(/^"|"$/g, ""));
        const rows: ReturnType<typeof ProductCsvRowSchema.parse>[] = [];
        const errors: string[] = [];
        for (let i = 1; i < lines.length; i++) {
          const cells = lines[i].split(/[,;]/).map((c) => c.trim().replace(/^"|"$/g, "").replace(/[<>]/g, ""));
          const obj: Record<string, string> = {};
          headers.forEach((h, idx) => { obj[h] = cells[idx] ?? ""; });
          const parsed = ProductCsvRowSchema.safeParse(obj);
          if (!parsed.success) errors.push(`Ligne ${i + 1}: ${parsed.error.issues[0].message}`);
          else rows.push(parsed.data);
        }
        if (errors.length && !rows.length) {
          toast.error(`Import échoué : ${errors[0]}`);
          return;
        }
        const res = bulkImportProducts(rows);
        toast.success(`${res.added} ajoutés, ${res.skipped} ignorés (doublons)${errors.length ? `, ${errors.length} erreurs` : ""}`);
      } catch (e: unknown) {
        toast.error(`Erreur CSV : ${e instanceof Error ? e.message : "Fichier invalide"}`);
      }
    };
    reader.readAsText(file);
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Inventaire"
        title="Stock & Produits"
        subtitle={`${filtered.length} produit(s) affiché(s)`}
        actions={
          isAdmin ? (
            <>
              <label>
                <input
                  type="file"
                  accept=".csv"
                  className="hidden"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) onCsvImport(f); e.target.value = ""; }}
                />
                <Button variant="outline" asChild>
                  <span><Upload className="mr-2 h-4 w-4" /> Import CSV</span>
                </Button>
              </label>
              <Dialog open={open} onOpenChange={(v) => { setOpen(v); if (!v) setEditing(null); }}>
                <DialogTrigger asChild>
                  <Button variant="gradient">
                    <Plus className="mr-2 h-4 w-4" /> Nouveau produit
                  </Button>
                </DialogTrigger>
                <ProductDialog editing={editing} onSave={onSave} stores={stores} categories={allCategories} />
              </Dialog>
            </>
          ) : undefined
        }
      />

      <Tabs defaultValue="list">
        <TabsList>
          <TabsTrigger value="list">Produits</TabsTrigger>
          <TabsTrigger value="moves">Mouvements ({scopedMoves.length})</TabsTrigger>
        </TabsList>

        <TabsContent value="list" className="space-y-4">
          <Section padding="sm">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <SearchInput
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Rechercher nom, référence, catégorie..."
                aria-label="Rechercher un produit"
                wrapperClassName="lg:col-span-2"
              />
              {isAdmin && (
                <FilterSelect
                  value={filterStore}
                  onValueChange={setFilterStore}
                  options={stores.map((s) => ({ value: s.id, label: s.name }))}
                  allLabel="Tous les magasins"
                  aria-label="Filtre magasin"
                />
              )}
              <FilterSelect
                value={filterCategory}
                onValueChange={setFilterCategory}
                options={allCategories.map((c) => ({ value: c, label: c }))}
                allLabel="Toutes catégories"
                aria-label="Filtre catégorie"
              />
              <FilterSelect
                value={stockFilter}
                onValueChange={(v) => setStockFilter(v as "all" | "low" | "out")}
                options={[
                  { value: "low", label: "Stock faible (≤3)" },
                  { value: "out", label: "Rupture" },
                ]}
                allLabel="Tout stock"
                aria-label="Filtre niveau de stock"
              />
            </div>
          </Section>

          <Section padding="none">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead><SortHeader k="name" label="Produit" /></TableHead>
                  <TableHead><SortHeader k="sku" label="Référence" /></TableHead>
                  <TableHead><SortHeader k="category" label="Catégorie" /></TableHead>
                  <TableHead>Magasin</TableHead>
                  <TableHead className="text-right"><SortHeader k="price" label="Prix" align="right" /></TableHead>
                  <TableHead className="text-right"><SortHeader k="stock" label="Stock" align="right" /></TableHead>
                  {isAdmin && <TableHead className="w-32" />}
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((p) => {
                  const store = stores.find((s) => s.id === p.storeId);
                  return (
                    <TableRow key={p.id}>
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2.5">
                          <CategoryIcon category={p.category} className="h-7 w-7" iconClassName="h-4 w-4" />
                          <span className="truncate">{p.name}</span>
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">{p.sku}</TableCell>
                      <TableCell>{p.category ? <Badge variant="outline">{p.category}</Badge> : <span className="text-xs text-muted-foreground">—</span>}</TableCell>
                      <TableCell className="text-xs">{store?.name.split("—")[0]}</TableCell>
                      <TableCell className="text-right font-mono">{fmt(p.price)}</TableCell>
                      <TableCell className="text-right">
                        {isAdmin ? (
                          <button
                            type="button"
                            onClick={() => setAdjustOpen(p)}
                            title="Cliquer pour ajuster"
                            aria-label={`Ajuster le stock de ${p.name}`}
                            className="rounded-md transition hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                          >
                            <Badge
                              variant={p.stock === 0 ? "destructive" : p.stock <= 3 ? "secondary" : "outline"}
                              className={p.stock <= 3 && p.stock > 0 ? "border-warning/50 text-warning" : ""}
                            >
                              {p.stock}
                            </Badge>
                          </button>
                        ) : (
                          <Badge
                            variant={p.stock === 0 ? "destructive" : p.stock <= 3 ? "secondary" : "outline"}
                            className={p.stock <= 3 && p.stock > 0 ? "border-warning/50 text-warning" : ""}
                          >
                            {p.stock}
                          </Badge>
                        )}
                      </TableCell>
                      {isAdmin && (
                        <TableCell>
                          <div className="flex justify-end gap-1">
                            <IconButton icon={PackagePlus} title="Ajuster stock" aria-label={`Ajuster le stock de ${p.name}`} onClick={() => setAdjustOpen(p)} />
                            <IconButton icon={ArrowLeftRight} title="Transférer" aria-label={`Transférer ${p.name}`} onClick={() => setTransferOpen(p)} />
                            <IconButton icon={Pencil} title="Modifier" aria-label={`Modifier ${p.name}`} onClick={() => { setEditing(p); setOpen(true); }} />
                            <ConfirmDialog
                              trigger={<IconButton icon={Trash2} tone="destructive" title="Supprimer" aria-label={`Supprimer ${p.name}`} />}
                              title={`Supprimer "${p.name}" ?`}
                              description="Le produit sera définitivement retiré. Les ventes passées restent intactes."
                              destructive
                              onConfirm={() => { deleteProduct(p.id); toast.success("Supprimé"); }}
                            />
                          </div>
                        </TableCell>
                      )}
                    </TableRow>
                  );
                })}
                {filtered.length === 0 && (
                  <TableRow><TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">Aucun produit.</TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </Section>
        </TabsContent>

        <TabsContent value="moves">
          <Section padding="none">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Date</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Produit</TableHead>
                  <TableHead>Magasin</TableHead>
                  <TableHead className="text-right">Quantité</TableHead>
                  <TableHead>Motif</TableHead>
                  <TableHead>Par</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {scopedMoves.slice(0, 200).map((m) => <MoveRow key={m.id} m={m} stores={stores} />)}
                {scopedMoves.length === 0 && (
                  <TableRow><TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">Aucun mouvement.</TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </Section>
        </TabsContent>
      </Tabs>

      {adjustOpen && (
        <AdjustDialog product={adjustOpen} onClose={() => setAdjustOpen(null)} onApply={(delta, reason) => {
          const r = adjustStock(adjustOpen.id, delta, reason, user ? { userId: user.id, userName: user.name } : undefined);
          if (!r.ok) toast.error(r.error ?? "Erreur"); else { toast.success("Stock ajusté"); setAdjustOpen(null); }
        }} />
      )}
      {transferOpen && (
        <TransferDialog product={transferOpen} stores={stores} onClose={() => setTransferOpen(null)} onApply={(toStoreId, qty) => {
          const r = transferStock(transferOpen.id, transferOpen.storeId, toStoreId, qty, user ? { userId: user.id, userName: user.name } : undefined);
          if (!r.ok) toast.error(r.error ?? "Erreur"); else { toast.success("Transfert effectué"); setTransferOpen(null); }
        }} />
      )}
    </div>
  );
}

function MoveRow({ m, stores }: { m: StockMove; stores: { id: string; name: string }[] }) {
  const colors: Record<string, string> = {
    IN: "text-success", OUT: "text-warning", ADJUST: "text-muted-foreground",
    TRANSFER: "text-accent", SALE: "text-primary", REFUND: "text-destructive",
  };
  return (
    <TableRow>
      <TableCell className="text-xs">{new Date(m.date).toLocaleString("fr-FR")}</TableCell>
      <TableCell><Badge variant="outline" className={colors[m.type]}>{m.type}</Badge></TableCell>
      <TableCell className="text-xs">{m.productName}</TableCell>
      <TableCell className="text-xs">{stores.find((s) => s.id === m.storeId)?.name.split("—")[0]}{m.toStoreId && ` → ${stores.find((s) => s.id === m.toStoreId)?.name.split("—")[0]}`}</TableCell>
      <TableCell className={`text-right font-mono ${m.delta >= 0 ? "text-success" : "text-destructive"}`}>{m.delta > 0 ? "+" : ""}{m.delta}</TableCell>
      <TableCell className="text-xs text-muted-foreground">{m.reason ?? "—"}</TableCell>
      <TableCell className="text-xs">{m.userName ?? "—"}</TableCell>
    </TableRow>
  );
}

function AdjustDialog({ product, onClose, onApply }: { product: Product; onClose: () => void; onApply: (delta: number, reason: string) => void }) {
  const [delta, setDelta] = useState(0);
  const [reason, setReason] = useState("");
  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader><DialogTitle>Ajuster le stock — {product.name}</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">Stock actuel : <span className="font-mono font-bold">{product.stock}</span></p>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="adj-delta">Variation (positive = entrée, négative = sortie)</Label>
            <Input id="adj-delta" type="number" value={delta} onChange={(e) => setDelta(parseInt(e.target.value) || 0)} autoFocus />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="adj-reason">Motif</Label>
            <Input id="adj-reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Réception fournisseur, casse, inventaire..." />
          </div>
          <p className="text-sm">Nouveau stock : <span className="font-mono font-bold">{product.stock + delta}</span></p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Annuler</Button>
          <Button variant="gradient" disabled={delta === 0 || !reason.trim()} onClick={() => onApply(delta, reason.trim())}>Appliquer</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function TransferDialog({ product, stores, onClose, onApply }: { product: Product; stores: { id: string; name: string }[]; onClose: () => void; onApply: (toStoreId: string, qty: number) => void }) {
  const others = stores.filter((s) => s.id !== product.storeId);
  const [toStoreId, setToStoreId] = useState(others[0]?.id ?? "");
  const [qty, setQty] = useState(1);
  if (others.length === 0) {
    return (
      <Dialog open onOpenChange={onClose}>
        <DialogContent>
          <DialogHeader><DialogTitle>Transfert impossible</DialogTitle></DialogHeader>
          <p className="text-sm text-muted-foreground">Au moins 2 magasins sont requis pour effectuer un transfert.</p>
          <DialogFooter><Button onClick={onClose}>Fermer</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    );
  }
  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader><DialogTitle>Transférer — {product.name}</DialogTitle></DialogHeader>
        <p className="text-xs text-muted-foreground">Stock disponible : <span className="font-mono font-bold">{product.stock}</span></p>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="xfer-store">Vers le magasin</Label>
            <Select value={toStoreId} onValueChange={setToStoreId}>
              <SelectTrigger id="xfer-store"><SelectValue /></SelectTrigger>
              <SelectContent>{others.map((s) => <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="xfer-qty">Quantité</Label>
            <Input id="xfer-qty" type="number" min={1} max={product.stock} value={qty} onChange={(e) => setQty(parseInt(e.target.value) || 0)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Annuler</Button>
          <Button variant="gradient" disabled={qty <= 0 || qty > product.stock || !toStoreId} onClick={() => onApply(toStoreId, qty)}>Transférer</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProductDialog({ editing, onSave, stores, categories }: {
  editing: Product | null;
  onSave: (p: Omit<Product, "id" | "createdAt">) => void;
  stores: { id: string; name: string }[];
  categories: string[];
}) {
  const [form, setForm] = useState<Omit<Product, "id" | "createdAt">>(
    editing
      ? { name: editing.name, price: editing.price, stock: editing.stock, storeId: editing.storeId, category: editing.category, sku: editing.sku }
      : { name: "", price: 0, stock: 0, storeId: stores[0]?.id ?? "s1", category: undefined, sku: "" }
  );

  const submit = () => {
    if (!form.name.trim() || form.price <= 0) { toast.error("Nom et prix requis"); return; }
    const sku = form.sku.trim() || `KZ-${form.storeId.toUpperCase()}-${Date.now().toString().slice(-5)}`;
    onSave({ ...form, name: form.name.trim(), sku });
  };

  return (
    <DialogContent>
      <DialogHeader><DialogTitle>{editing ? "Modifier" : "Nouveau"} produit</DialogTitle></DialogHeader>
      <div className="grid grid-cols-2 gap-4">
        <div className="col-span-2 space-y-2">
          <Label htmlFor="prod-name">Nom *</Label>
          <Input id="prod-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
        </div>
        <div className="space-y-2">
          <Label htmlFor="prod-sku">Référence (SKU)</Label>
          <Input id="prod-sku" value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} placeholder="auto si vide" />
        </div>
        <div className="space-y-2">
          <Label htmlFor="prod-category">Catégorie (facultatif)</Label>
          <CategoryCombobox id="prod-category" value={form.category} onChange={(v) => setForm({ ...form, category: v })} options={categories} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="prod-price">Prix (Ar) *</Label>
          <Input id="prod-price" type="number" step="1" min={0} value={form.price || ""} onChange={(e) => setForm({ ...form, price: +e.target.value || 0 })} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="prod-stock">Stock</Label>
          <Input id="prod-stock" type="number" value={form.stock} onChange={(e) => setForm({ ...form, stock: +e.target.value || 0 })} />
        </div>
        <div className="col-span-2 space-y-2">
          <Label htmlFor="prod-store">Magasin</Label>
          <Select value={form.storeId} onValueChange={(v) => setForm({ ...form, storeId: v })}>
            <SelectTrigger id="prod-store"><SelectValue /></SelectTrigger>
            <SelectContent>{stores.map((s) => <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>)}</SelectContent>
          </Select>
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        Format CSV attendu : <code className="font-mono">name,sku,price,stock,category,storeId</code>
      </p>
      <DialogFooter>
        <Button variant="gradient" onClick={submit}>Enregistrer</Button>
      </DialogFooter>
    </DialogContent>
  );
}
