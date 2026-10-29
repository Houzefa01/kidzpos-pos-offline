import { useMemo, useState } from "react";
import { useCustomers, Customer } from "@/store/customers";
import { useSales } from "@/store/sales";
import { useAuth } from "@/store/auth";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Plus, Star, Trash2, Pencil, Gift } from "lucide-react";
import { toast } from "sonner";
import { useFormatMoney } from "@/lib/money";
import { Button, IconButton, PageHeader, Section, SearchInput } from "@/components/ds";

export default function Customers() {
  const { user } = useAuth();
  const fmt = useFormatMoney();
  const { customers, addCustomer, updateCustomer, deleteCustomer } = useCustomers();
  const sales = useSales((s) => s.sales);
  const isAdmin = user?.role === "ADMIN";

  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Customer | null>(null);
  const [form, setForm] = useState({ name: "", phone: "", email: "" });
  const [detail, setDetail] = useState<Customer | null>(null);
  const [pointsDelta, setPointsDelta] = useState(0);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return customers;
    return customers.filter((c) => `${c.name ?? ""} ${c.phone ?? ""} ${c.email ?? ""}`.toLowerCase().includes(q));
  }, [customers, search]);

  const openCreate = () => { setEditing(null); setForm({ name: "", phone: "", email: "" }); setOpen(true); };
  const openEdit = (c: Customer) => {
    setEditing(c);
    setForm({ name: c.name ?? "", phone: c.phone ?? "", email: c.email ?? "" });
    setOpen(true);
  };

  const submit = () => {
    if (!form.name.trim() && !form.phone.trim()) { toast.error("Nom OU téléphone requis"); return; }
    if (editing) {
      updateCustomer(editing.id, {
        name: form.name.trim() || undefined,
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
      });
      toast.success("Client mis à jour");
    } else {
      addCustomer(form);
      toast.success("Client ajouté");
    }
    setOpen(false);
  };

  const detailSales = useMemo(
    () => detail ? sales.filter((s) => s.customerId === detail.id) : [],
    [sales, detail]
  );

  const applyPointsDelta = () => {
    if (!detail || pointsDelta === 0) return;
    const newPoints = Math.max(0, detail.points + pointsDelta);
    updateCustomer(detail.id, { points: newPoints });
    setDetail({ ...detail, points: newPoints });
    setPointsDelta(0);
    toast.success(`Points ajustés : ${newPoints}`);
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Fidélité"
        title="Clients & Fidélité"
        subtitle={`${filtered.length} client(s) · gérez la fidélisation`}
        actions={
          <Dialog open={open} onOpenChange={setOpen}>
            <DialogTrigger asChild>
              <Button variant="gradient" onClick={openCreate}>
                <Plus className="mr-2 h-4 w-4" /> Nouveau client
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader><DialogTitle>{editing ? "Modifier" : "Nouveau"} client</DialogTitle></DialogHeader>
              <div className="space-y-3">
                <div className="space-y-1.5"><Label htmlFor="cust-name">Nom</Label><Input id="cust-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus /></div>
                <div className="space-y-1.5"><Label htmlFor="cust-phone">Téléphone</Label><Input id="cust-phone" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></div>
                <div className="space-y-1.5"><Label htmlFor="cust-email">Email</Label><Input id="cust-email" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></div>
              </div>
              <DialogFooter><Button variant="gradient" onClick={submit}>Enregistrer</Button></DialogFooter>
            </DialogContent>
          </Dialog>
        }
      />

      <Section padding="sm">
        <SearchInput
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Rechercher nom, téléphone..."
          aria-label="Rechercher un client"
        />
      </Section>

      <Section padding="none">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Client</TableHead>
              <TableHead>Téléphone</TableHead>
              <TableHead>Email</TableHead>
              <TableHead className="text-right">Visites</TableHead>
              <TableHead className="text-right">Total dépensé</TableHead>
              <TableHead className="text-right">Points</TableHead>
              <TableHead className="w-24" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {filtered.map((c) => (
              <TableRow key={c.id} className="cursor-pointer" onClick={() => setDetail(c)}>
                <TableCell className="font-medium">{c.name || <span className="text-muted-foreground">Anonyme</span>}</TableCell>
                <TableCell className="text-xs">{c.phone || "—"}</TableCell>
                <TableCell className="text-xs">{c.email || "—"}</TableCell>
                <TableCell className="text-right">{c.visits}</TableCell>
                <TableCell className="text-right font-mono">{fmt(c.totalSpent)}</TableCell>
                <TableCell className="text-right">
                  <Badge variant="outline" className="border-warning/50 text-warning"><Star className="mr-1 h-3 w-3" />{c.points}</Badge>
                </TableCell>
                <TableCell>
                  <div className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                    <IconButton icon={Pencil} onClick={() => openEdit(c)} aria-label={`Modifier ${c.name || c.phone || "le client"}`} />
                    {isAdmin && (
                      <ConfirmDialog
                        trigger={<IconButton icon={Trash2} tone="destructive" aria-label="Supprimer le client" />}
                        title={`Supprimer "${c.name || c.phone || 'ce client'}" ?`}
                        description="Les ventes passées resteront mais ne seront plus rattachées."
                        destructive
                        onConfirm={() => { deleteCustomer(c.id); toast.success("Supprimé"); }}
                      />
                    )}
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {filtered.length === 0 && (
              <TableRow><TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">Aucun client.</TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </Section>

      {/* Detail dialog — pattern local (3 mini-stats centrées + ajustement points + historique).
          Labels/values calqués sur les tokens Stat (eyebrow + tracking-display) pour
          rester cohérent visuellement sans extraire un composant pour 3 instances. */}
      <Dialog open={!!detail} onOpenChange={(v) => { if (!v) { setDetail(null); setPointsDelta(0); } }}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{detail?.name || detail?.phone || "Client"}</DialogTitle></DialogHeader>
          {detail && (
            <div className="space-y-3 text-sm">
              <div className="grid grid-cols-3 gap-2">
                <Card className="p-3 text-center">
                  <p className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">Visites</p>
                  <p className="mt-1 font-mono text-2xl font-semibold tracking-display">{detail.visits}</p>
                </Card>
                <Card className="p-3 text-center">
                  <p className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">Dépensé</p>
                  <p className="mt-1 font-mono text-2xl font-semibold tracking-display text-primary">{fmt(detail.totalSpent)}</p>
                </Card>
                <Card className="p-3 text-center">
                  <p className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">Points</p>
                  <p className="mt-1 font-mono text-2xl font-semibold tracking-display text-warning">{detail.points}</p>
                </Card>
              </div>
              {isAdmin && (
                <Card className="p-3">
                  <p className="mb-2 flex items-center gap-2 text-xs font-medium"><Gift className="h-3.5 w-3.5 text-warning" /> Ajustement de points (admin)</p>
                  <div className="flex gap-2">
                    <Input type="number" placeholder="ex: +50 ou -10" value={pointsDelta || ""} onChange={(e) => setPointsDelta(parseInt(e.target.value) || 0)} className="h-8" />
                    <Button size="sm" onClick={applyPointsDelta} disabled={pointsDelta === 0}>Appliquer</Button>
                  </div>
                </Card>
              )}
              <div>
                <p className="mb-2 font-medium">Historique ({detailSales.length})</p>
                <div className="max-h-60 space-y-1 overflow-y-auto">
                  {detailSales.map((s) => (
                    <div key={s.id} className="flex justify-between rounded border border-border bg-secondary/40 px-3 py-2 text-xs">
                      <span>#{String(s.seq).padStart(6, "0")} · {new Date(s.date).toLocaleDateString("fr-FR")}</span>
                      <span className={`font-mono ${s.total < 0 ? "text-destructive" : ""}`}>{fmt(s.total, s.currency)}</span>
                    </div>
                  ))}
                  {detailSales.length === 0 && <p className="text-center text-xs text-muted-foreground">Aucun achat.</p>}
                </div>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
