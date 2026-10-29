import { useMemo, useState } from "react";
import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { useSales } from "@/store/sales";
import { useSettings } from "@/store/settings";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Download, FileText, Undo2, Eye } from "lucide-react";
import { toast } from "sonner";
import { downloadReceiptPdf, downloadSalesReportPdf } from "@/lib/pdf";
import { useFormatMoney } from "@/lib/money";
import { Button, IconButton, PageHeader, Stat, SearchInput, FilterSelect, ReceiptOverlay } from "@/components/ds";
import { cn } from "@/lib/utils";

// Classe partagée pour les <TableHead> — évite la duplication de la chaîne
// `text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground` sur
// chaque cellule. Local à Sales : si Stock/Customers/Users finissent par
// l'avoir aussi, on remontera en DS (`<DataTableHead>`).
const colHeadCls = "text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground";

export default function Sales() {
  const { user } = useAuth();
  const fmt = useFormatMoney();
  const { stores } = useData();
  const { sales, refundSale } = useSales();
  const { settings } = useSettings();
  const isAdmin = user?.role === "ADMIN";

  const [search, setSearch] = useState("");
  const [storeFilter, setStoreFilter] = useState<string>(isAdmin ? "all" : (user?.storeId ?? "all"));
  const [from, setFrom] = useState<string>("");
  const [to, setTo] = useState<string>("");
  const [viewSale, setViewSale] = useState<typeof sales[number] | null>(null);

  const filtered = useMemo(() => {
    const fromTs = from ? new Date(from + "T00:00:00").getTime() : null;
    const toTs = to ? new Date(to + "T23:59:59.999").getTime() : null;
    return sales.filter((s) => {
      if (!isAdmin && s.storeId !== user?.storeId) return false;
      if (storeFilter !== "all" && s.storeId !== storeFilter) return false;
      if (fromTs !== null) {
        const saleTs = new Date(s.date).getTime();
        if (saleTs < fromTs) return false;
      }
      if (toTs !== null) {
        const saleTs = new Date(s.date).getTime();
        if (saleTs > toTs) return false;
      }
      if (search && !`${s.id} ${s.seq} ${s.userName} ${s.customerName ?? ""} ${s.items.map((i) => i.name).join(" ")}`.toLowerCase().includes(search.toLowerCase())) return false;
      return true;
    });
  }, [sales, search, storeFilter, isAdmin, user, from, to]);

  const total = filtered.reduce((a, s) => a + s.total, 0);
  const itemCount = filtered.reduce((a, s) => a + s.items.reduce((b, i) => b + i.quantity, 0), 0);
  const avgTicket = filtered.length > 0 ? total / filtered.length : 0;

  const exportCSV = () => {
    const rows = [
      ["Ticket", "Date", "Magasin", "Caissier", "Client", "Articles", "Sous-total", "Remise", "Total", "Type"],
      ...filtered.map((s) => [
        String(s.seq).padStart(6, "0"),
        new Date(s.date).toLocaleString("fr-FR"),
        stores.find((x) => x.id === s.storeId)?.name ?? "",
        s.userName,
        s.customerName ?? "",
        String(s.items.reduce((a, i) => a + i.quantity, 0)),
        s.subtotal.toFixed(2), s.discount.toFixed(2), s.total.toFixed(2),
        s.refundedFrom ? "Remboursement" : "Vente",
      ]),
    ];
    const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = `ventes-${new Date().toISOString().slice(0, 10)}.csv`; a.click();
    URL.revokeObjectURL(url);
    toast.success("Export CSV téléchargé");
  };

  const exportPDF = () => {
    const label = `Filtre : ${storeFilter === "all" ? "tous magasins" : stores.find((s) => s.id === storeFilter)?.name ?? ""}${from ? ` · du ${from}` : ""}${to ? ` au ${to}` : ""}`;
    downloadSalesReportPdf(filtered, stores, settings.shopName, label);
    toast.success("PDF téléchargé");
  };

  const onRefund = (id: string) => {
    if (!user) return;
    const res = refundSale(id, { userId: user.id, userName: user.name });
    if (!res.ok) toast.error(res.error ?? "Erreur");
    else toast.success("Vente remboursée");
  };

  const viewSaleStore = viewSale ? stores.find((s) => s.id === viewSale.storeId) : undefined;

  return (
    <div className="space-y-7">
      <PageHeader
        eyebrow="Journal"
        title="Historique des ventes"
        subtitle="Toutes les transactions, en temps réel. Exportez en CSV ou PDF."
        actions={
          <>
            <Button variant="outline" onClick={exportCSV} className="rounded-lg border-border bg-card hover:bg-secondary">
              <Download className="mr-2 h-4 w-4" /> CSV
            </Button>
            <Button variant="outline" onClick={exportPDF} className="rounded-lg border-border bg-card hover:bg-secondary">
              <FileText className="mr-2 h-4 w-4" /> PDF
            </Button>
          </>
        }
      />

      {/* Stats row — tuiles compactes séparées par des hairlines (gap-px sur bg-border) */}
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-border bg-border sm:grid-cols-4">
        <Stat density="compact" label="Ventes" value={filtered.length.toString()} />
        <Stat density="compact" label="Articles" value={itemCount.toString()} />
        <Stat density="compact" label="Panier moyen" value={fmt(avgTicket)} />
        <Stat density="compact" label="Total cumulé" value={fmt(total)} highlight />
      </div>

      {/* Filters */}
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <SearchInput
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Rechercher ticket, client, produit, caissier…"
          aria-label="Rechercher dans les ventes"
          className="rounded-lg border-border bg-card"
          wrapperClassName="lg:col-span-2"
        />
        {isAdmin && (
          <FilterSelect
            value={storeFilter}
            onValueChange={setStoreFilter}
            options={stores.map((s) => ({ value: s.id, label: s.name }))}
            allLabel="Tous les magasins"
            aria-label="Filtre magasin"
            triggerClassName="rounded-lg border-border bg-card"
          />
        )}
        <div className="flex gap-1.5">
          <Input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            aria-label="Date de début"
            className="rounded-lg border-border bg-card"
          />
          <Input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            aria-label="Date de fin"
            className="rounded-lg border-border bg-card"
          />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="border-b border-border bg-secondary/60">
                <TableHead className={colHeadCls}>Ticket</TableHead>
                <TableHead className={cn(colHeadCls, "hidden sm:table-cell")}>Date</TableHead>
                <TableHead className={cn(colHeadCls, "hidden lg:table-cell")}>Magasin</TableHead>
                <TableHead className={cn(colHeadCls, "hidden md:table-cell")}>Caissier</TableHead>
                <TableHead className={cn(colHeadCls, "hidden lg:table-cell")}>Client</TableHead>
                <TableHead className={cn(colHeadCls, "hidden sm:table-cell")}>Art.</TableHead>
                <TableHead className={cn(colHeadCls, "text-right")}>Total</TableHead>
                <TableHead className={cn(colHeadCls, "text-right")}>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((s) => {
                const isRefund = !!s.refundedFrom;
                const alreadyRefunded = sales.some((x) => x.refundedFrom === s.id);
                return (
                  <TableRow
                    key={s.id}
                    className={cn(
                      "border-b border-border/60 transition-colors hover:bg-secondary/40",
                      isRefund && "bg-destructive/[0.04]",
                    )}
                  >
                    <TableCell className="font-mono text-xs">
                      <span className="text-foreground">#{String(s.seq).padStart(6, "0")}</span>
                      {isRefund && (
                        <Badge variant="destructive" className="ml-2 rounded px-1.5 py-0 text-2xs">RBT</Badge>
                      )}
                      {/* Sub-meta visible only on mobile (where Date/Caissier cols are hidden) */}
                      <div className="mt-0.5 font-mono text-2xs text-muted-foreground sm:hidden">
                        {new Date(s.date).toLocaleDateString("fr-FR")} · {s.userName}
                      </div>
                    </TableCell>
                    <TableCell className="hidden font-mono text-label text-muted-foreground sm:table-cell">
                      {new Date(s.date).toLocaleString("fr-FR")}
                    </TableCell>
                    <TableCell className="hidden text-xs lg:table-cell">
                      {stores.find((x) => x.id === s.storeId)?.name.split("—")[0]}
                    </TableCell>
                    <TableCell className="hidden text-xs md:table-cell">{s.userName}</TableCell>
                    <TableCell className="hidden text-xs lg:table-cell">
                      {s.customerName || <span className="text-muted-foreground">—</span>}
                    </TableCell>
                    <TableCell className="hidden sm:table-cell">
                      <span className="inline-flex h-5 min-w-[1.25rem] items-center justify-center rounded bg-secondary px-1.5 font-mono text-label font-medium">
                        {s.items.reduce((a, i) => a + i.quantity, 0)}
                      </span>
                    </TableCell>
                    <TableCell className={cn(
                      "text-right font-mono text-sm font-semibold tracking-display",
                      s.total < 0 ? "text-destructive" : "text-foreground",
                    )}>
                      {fmt(s.total)}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-0.5">
                        <IconButton
                          variant="compact"
                          icon={Eye}
                          onClick={() => setViewSale(s)}
                          title="Voir"
                          aria-label={`Voir la vente #${String(s.seq).padStart(6, "0")}`}
                        />
                        <IconButton
                          variant="compact"
                          icon={FileText}
                          onClick={() => downloadReceiptPdf(s, stores.find((x) => x.id === s.storeId), settings)}
                          title="PDF"
                          aria-label="Télécharger le reçu PDF"
                        />
                        {!isRefund && !alreadyRefunded && (
                          <ConfirmDialog
                            trigger={
                              <IconButton
                                variant="compact"
                                icon={Undo2}
                                tone="warning"
                                className="hover:bg-warning/10"
                                title="Rembourser"
                                aria-label="Rembourser la vente"
                              />
                            }
                            title={`Rembourser la vente #${String(s.seq).padStart(6, "0")} ?`}
                            description="Le stock sera réintégré et un ticket d'avoir sera émis."
                            destructive
                            onConfirm={() => onRefund(s.id)}
                          />
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
              {filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} className="py-16 text-center">
                    <p className="text-sm text-muted-foreground">
                      Aucune vente sur la période sélectionnée.
                    </p>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </div>

      <ReceiptOverlay
        sale={viewSale}
        store={viewSaleStore}
        settings={settings}
        onClose={() => setViewSale(null)}
      />
    </div>
  );
}
