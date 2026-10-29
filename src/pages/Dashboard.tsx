import { useAuth } from "@/store/auth";
import { useData } from "@/store/data";
import { useSales } from "@/store/sales";
import { useSettings } from "@/store/settings";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertTriangle, Banknote, Hand, Package, Star, TrendingDown, TrendingUp, Undo2 } from "lucide-react";
import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useMemo } from "react";
import { useFormatMoney } from "@/lib/money";
import { PageHeader, Section, Stat, Grid, EmptyState } from "@/components/ds";

export default function Dashboard() {
  const { user } = useAuth();
  const { products, stores } = useData();
  const sales = useSales((s) => s.sales);
  const { settings } = useSettings();
  const fmt = useFormatMoney();

  const isAdmin = user?.role === "ADMIN";
  const scopedSales = useMemo(() => isAdmin ? sales : sales.filter((s) => s.storeId === user?.storeId), [sales, isAdmin, user]);
  const scopedProducts = useMemo(() => isAdmin ? products : products.filter((p) => p.storeId === user?.storeId), [products, isAdmin, user]);

  const revenue = useMemo(() => scopedSales.reduce((acc, s) => acc + s.total, 0), [scopedSales]);
  const lowStock = useMemo(() => scopedProducts.filter((p) => p.stock <= 3).length, [scopedProducts]);

  const byStore = useMemo(() => stores.map((st) => ({
    name: st.name.split("—")[0].trim(),
    Ventes: sales.filter((s) => s.storeId === st.id).reduce((a, s) => a + s.total, 0),
  })), [stores, sales]);

  const days = useMemo(() => {
    const result: { day: string; Total: number }[] = [];
    for (let i = 6; i >= 0; i--) {
      const d = new Date();
      d.setDate(d.getDate() - i);
      const key = d.toISOString().slice(0, 10);
      const total = scopedSales.filter((s) => s.date.slice(0, 10) === key).reduce((a, s) => a + s.total, 0);
      result.push({ day: d.toLocaleDateString("fr-FR", { weekday: "short" }), Total: +total.toFixed(2) });
    }
    return result;
  }, [scopedSales]);

  // Stats mensuelles : 12 derniers mois + comparaison mois en cours vs précédent
  const months = useMemo(() => {
    const out: { label: string; key: string; Total: number }[] = [];
    const now = new Date();
    for (let i = 11; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
      const total = scopedSales
        .filter((s) => s.date.slice(0, 7) === key)
        .reduce((a, s) => a + s.total, 0);
      out.push({ label: d.toLocaleDateString("fr-FR", { month: "short" }), key, Total: +total.toFixed(2) });
    }
    return out;
  }, [scopedSales]);

  const currentMonth = months[months.length - 1]?.Total ?? 0;
  const prevMonth = months[months.length - 2]?.Total ?? 0;
  const monthDelta = prevMonth > 0 ? ((currentMonth - prevMonth) / prevMonth) * 100 : currentMonth > 0 ? 100 : 0;

  // Top 5 produits (par CA)
  const topProducts = useMemo(() => {
    const map = new Map<string, { name: string; qty: number; revenue: number }>();
    for (const s of scopedSales) {
      if (s.refundedFrom) continue;
      for (const it of s.items) {
        const cur = map.get(it.productId) ?? { name: it.name, qty: 0, revenue: 0 };
        cur.qty += it.quantity;
        cur.revenue += it.price * it.quantity;
        map.set(it.productId, cur);
      }
    }
    return Array.from(map.values()).sort((a, b) => b.revenue - a.revenue).slice(0, 5);
  }, [scopedSales]);

  const recent = [...scopedSales].slice(0, 6);

  // Helper recharts Tooltip : signature alignée sur `Formatter` de recharts
  // (ValueType = number | string | ReadonlyArray<number|string>, peut être undefined).
  const tooltipFmt = (value: number | string | ReadonlyArray<number | string> | undefined): string => {
    if (value == null) return "";
    const v = Array.isArray(value) ? value[0] : (value as number | string);
    if (v == null) return "";
    return fmt(typeof v === "number" ? v : Number(v));
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Tableau de bord"
        title={
          <>
            Bonjour, {user?.name.split(" ")[0]}
            <Hand
              aria-hidden="true"
              className="ml-2 inline-block h-6 w-6 -translate-y-0.5 align-middle text-warning sm:h-7 sm:w-7"
            />
          </>
        }
        subtitle={isAdmin ? `Vue consolidée — ${settings.shopName}.` : "Aperçu de votre magasin."}
      />

      <Grid cols={1} sm={2} lg={4} gap={4}>
        <Stat className="animate-rise" label="Chiffre d'affaires" value={fmt(revenue)} hint={`${scopedSales.length} ventes`} icon={Banknote} tone="primary" />
        <Stat
          className="animate-rise-2"
          label="Mois en cours"
          value={fmt(currentMonth)}
          hint={`${monthDelta >= 0 ? "+" : ""}${monthDelta.toFixed(1)}% vs précédent`}
          icon={monthDelta >= 0 ? TrendingUp : TrendingDown}
          tone={monthDelta >= 0 ? "success" : "warning"}
        />
        <Stat className="animate-rise-3" label="Produits actifs" value={String(scopedProducts.length)} hint={isAdmin ? "tous magasins" : "votre stock"} icon={Package} tone="accent" />
        <Stat className="animate-rise-4" label="Stock faible" value={String(lowStock)} hint="≤ 3 unités" icon={AlertTriangle} tone="warning" />
      </Grid>

      <Tabs defaultValue="week" className="space-y-4">
        <TabsList>
          <TabsTrigger value="week">7 jours</TabsTrigger>
          <TabsTrigger value="month">12 mois</TabsTrigger>
          <TabsTrigger value="top">Top produits</TabsTrigger>
        </TabsList>

        <TabsContent value="week">
          <Grid cols={1} lg={3} gap={4}>
            <Section title="Ventes — 7 derniers jours" className="lg:col-span-2">
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={0}>
                  <LineChart data={days}>
                    <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                    <XAxis dataKey="day" stroke="hsl(var(--muted-foreground))" fontSize={12} />
                    <YAxis stroke="hsl(var(--muted-foreground))" fontSize={12} />
                    <Tooltip contentStyle={{ background: "hsl(var(--popover))", border: "1px solid hsl(var(--border))", borderRadius: 8 }} formatter={tooltipFmt} />
                    <Line type="monotone" dataKey="Total" stroke="hsl(var(--primary))" strokeWidth={3} dot={{ fill: "hsl(var(--primary))" }} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </Section>
            {isAdmin && (
              <Section title="Par magasin">
                <div className="h-64">
                  <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={0}>
                    <BarChart data={byStore}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                      <XAxis dataKey="name" stroke="hsl(var(--muted-foreground))" fontSize={11} />
                      <YAxis stroke="hsl(var(--muted-foreground))" fontSize={12} />
                      <Tooltip contentStyle={{ background: "hsl(var(--popover))", border: "1px solid hsl(var(--border))", borderRadius: 8 }} formatter={tooltipFmt} />
                      <Bar dataKey="Ventes" radius={[8, 8, 0, 0]}>
                        {byStore.map((_, i) => <Cell key={i} fill={i === 0 ? "hsl(var(--primary))" : "hsl(var(--accent))"} />)}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </Section>
            )}
          </Grid>
        </TabsContent>

        <TabsContent value="month">
          <Section title="Chiffre d'affaires — 12 derniers mois">
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={0}>
                <BarChart data={months}>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                  <XAxis dataKey="label" stroke="hsl(var(--muted-foreground))" fontSize={11} />
                  <YAxis stroke="hsl(var(--muted-foreground))" fontSize={12} />
                  <Tooltip contentStyle={{ background: "hsl(var(--popover))", border: "1px solid hsl(var(--border))", borderRadius: 8 }} formatter={tooltipFmt} />
                  <Bar dataKey="Total" radius={[6, 6, 0, 0]} fill="hsl(var(--primary))" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Section>
        </TabsContent>

        <TabsContent value="top">
          <Section title="Top 5 produits (CA)">
            {topProducts.length === 0 ? (
              <EmptyState density="inline" title="Aucune donnée." />
            ) : (
              <div className="space-y-3">
                {topProducts.map((p, i) => {
                  const max = topProducts[0].revenue;
                  const pct = (p.revenue / max) * 100;
                  return (
                    <div key={p.name} className="space-y-1">
                      <div className="flex items-center justify-between text-sm">
                        <span className="flex items-center gap-2">
                          <Badge variant="outline" className="font-mono">#{i + 1}</Badge>
                          {i === 0 && <Star className="h-3.5 w-3.5 text-warning" />}
                          {p.name}
                        </span>
                        <span className="font-mono font-semibold">{fmt(p.revenue)} · {p.qty}u</span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full bg-secondary">
                        <div className="h-full gradient-primary" style={{ width: `${pct}%` }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Section>
        </TabsContent>
      </Tabs>

      <Section title="Activité récente">
        {recent.length === 0 ? (
          <EmptyState density="inline" title="Aucune vente pour le moment." />
        ) : (
          <div className="divide-y divide-border">
            {recent.map((s) => {
              const store = stores.find((x) => x.id === s.storeId);
              return (
                <div key={s.id} className="flex items-center justify-between py-3">
                  <div>
                    <p className="text-sm font-medium">
                      {s.refundedFrom ? (
                        <>
                          <Undo2
                            aria-hidden="true"
                            className="mr-1 inline-block h-3.5 w-3.5 -translate-y-px align-middle text-destructive"
                          />
                          Remboursement{" "}
                        </>
                      ) : "Vente "}
                      #{String(s.seq).padStart(6, "0")} — {s.userName}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {store?.name} · {new Date(s.date).toLocaleString("fr-FR")}
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <Badge variant="secondary">{s.items.reduce((a, i) => a + i.quantity, 0)} art.</Badge>
                    <span className={`font-mono font-semibold ${s.total < 0 ? "text-destructive" : "text-primary"}`}>{fmt(s.total)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Section>
    </div>
  );
}
