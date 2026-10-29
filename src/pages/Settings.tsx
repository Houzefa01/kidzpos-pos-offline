import { useEffect, useState } from "react";
import { useAuth } from "@/store/auth";
import { useSettings } from "@/store/settings";
import { useData } from "@/store/data";
import { useExchange } from "@/store/exchange";
import { useBackend } from "@/store/backend";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Download, Upload, RefreshCw, Save, Server, RotateCw, Wifi, Plus, Trash2, Lock } from "lucide-react";
import { toast } from "sonner";
import { getApiUrl, setApiUrl } from "@/lib/apiConfig";
import { Button, IconButton, PageHeader, Section, EmptyState, StatusDot } from "@/components/ds";

export default function Settings() {
  const { user } = useAuth();
  const { settings, update, reset } = useSettings();
  const { stores, addStore, updateStore, deleteStore, exportAll, importAll, resetAll } = useData();
  const { rate, fetchedAt, source, refresh, setManual } = useExchange();
  const { lanReachable, pendingCount } = useBackend();
  const [form, setForm] = useState(settings);
  const [apiUrl, setApiUrlLocal] = useState(getApiUrl());
  const [manualRate, setManualRate] = useState<string>(String(Math.round(rate)));
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [newStoreName, setNewStoreName] = useState("");
  const [newStoreLocation, setNewStoreLocation] = useState("");

  useEffect(() => setForm(settings), [settings]);

  if (user?.role !== "ADMIN") {
    return (
      <EmptyState
        icon={Lock}
        title="Accès réservé"
        description="Les paramètres sont limités aux administrateurs."
      />
    );
  }

  const save = () => {
    if (form.maxDiscountPercent < 0 || form.maxDiscountPercent > 100) { toast.error("Plafond remise invalide"); return; }
    update(form);
    toast.success("Paramètres enregistrés");
  };

  const doExport = () => {
    const blob = new Blob([exportAll()], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = `kidzpos-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click(); URL.revokeObjectURL(url);
    toast.success("Sauvegarde téléchargée");
  };

  const doImport = (file: File) => {
    if (file.size > 5_000_000) { toast.error("Fichier trop volumineux (max 5 Mo)"); return; }
    const reader = new FileReader();
    reader.onload = () => {
      const res = importAll(String(reader.result));
      if (res.ok) toast.success("Import réussi"); else toast.error(`Fichier invalide : ${res.error ?? ""}`);
    };
    reader.readAsText(file);
  };

  const doReset = () => {
    resetAll(); reset(); toast.success("Données réinitialisées");
  };

  const saveApiUrl = () => {
    const trimmed = apiUrl.trim();
    if (!trimmed) { toast.error("Adresse vide"); return; }
    try { new URL(trimmed); } catch { toast.error("URL invalide (ex: http://192.168.1.20:8080)"); return; }
    if (!/^https?:\/\//i.test(trimmed)) { toast.error("Doit commencer par http:// ou https://"); return; }
    setApiUrl(trimmed);
    toast.success("Adresse serveur enregistrée");
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Configuration"
        title="Paramètres"
        subtitle="Configuration globale — admin uniquement"
      />

      <Section title="Serveur magasin" icon={Server} padding="lg">
        <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
          <div className="space-y-1.5">
            <Label htmlFor="api-url">Adresse du serveur LAN/Internet</Label>
            <Input
              id="api-url"
              value={apiUrl}
              onChange={(e) => setApiUrlLocal(e.target.value)}
              placeholder="http://192.168.1.20:8080"
            />
            <p className="text-xs text-muted-foreground">
              Ex : <code>http://192.168.1.20:8080</code> en LAN, ou <code>https://kidzpos.monshop.com</code> sur Internet.
            </p>
          </div>
          <div className="flex items-end">
            <Button variant="outline" onClick={saveApiUrl}><Save className="mr-2 h-4 w-4" /> Appliquer</Button>
          </div>
        </div>
        <div className="mt-3 flex items-center gap-2 text-xs">
          <StatusDot tone={lanReachable ? "success" : "destructive"} size="md" />
          {lanReachable ? "Connecté au serveur" : "Serveur injoignable — mode local"}
          {pendingCount > 0 && <span className="text-warning">· {pendingCount} action(s) en file</span>}
        </div>
      </Section>

      <Section title="Devise & Taux de change" padding="lg">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="currency-select">Devise d'affichage</Label>
            <Select value={form.currency} onValueChange={(v: "AR" | "EUR") => setForm({ ...form, currency: v })}>
              <SelectTrigger id="currency-select"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="AR">Ariary (Ar) — défaut</SelectItem>
                <SelectItem value="EUR">Euro (€)</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">Les prix sont stockés en Ariary. EUR n'est qu'une vue d'affichage convertie via le taux.</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="manual-rate" className="flex items-center gap-1"><Wifi className="h-3 w-3" aria-hidden="true" /> Taux 1 € = ? Ar</Label>
            <div className="flex gap-2">
              <Input
                id="manual-rate"
                type="number"
                value={manualRate}
                onChange={(e) => setManualRate(e.target.value)}
                className="font-mono"
              />
              <Button variant="outline" onClick={() => { const r = +manualRate; if (r > 0) { setManual(r); toast.success("Taux manuel appliqué"); } }}>OK</Button>
              <Button
                variant="outline"
                disabled={isRefreshing}
                onClick={async () => {
                  setIsRefreshing(true);
                  try { await refresh(); } finally { setIsRefreshing(false); }
                }}
                title="Récupérer le taux du jour (Internet requis)"
                aria-label="Récupérer le taux du jour depuis Internet"
              >
                <RotateCw className={`h-4 w-4 ${isRefreshing ? "animate-spin" : ""}`} />
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Source : <strong>{source}</strong>
              {fetchedAt && ` · maj ${new Date(fetchedAt).toLocaleString("fr-FR")}`}
            </p>
          </div>
        </div>
      </Section>

      <Section title="Magasin & Remise" padding="lg">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="shop-name">Nom de l'enseigne</Label>
            <Input id="shop-name" value={form.shopName} onChange={(e) => setForm({ ...form, shopName: e.target.value })} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="max-discount">Plafond remise employé (%)</Label>
            <Input
              id="max-discount"
              type="number"
              min={0}
              max={100}
              value={form.maxDiscountPercent}
              onChange={(e) => setForm({ ...form, maxDiscountPercent: +e.target.value || 0 })}
            />
            <p className="text-xs text-muted-foreground">Les admins peuvent toujours dépasser ce plafond.</p>
          </div>
        </div>
      </Section>

      <Section title="Programme de fidélité" padding="lg">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="points-per-ar">Points gagnés par Ariary dépensé</Label>
            <Input
              id="points-per-ar"
              type="number"
              step="0.0001"
              min={0}
              value={form.pointsPerAr}
              onChange={(e) => setForm({ ...form, pointsPerAr: +e.target.value || 0 })}
            />
            <p className="text-xs text-muted-foreground">Ex : 0.0002 = 1 point pour 5 000 Ar.</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="ar-per-point">Valeur d'un point (Ar)</Label>
            <Input
              id="ar-per-point"
              type="number"
              step="1"
              min={0}
              value={form.arPerPoint}
              onChange={(e) => setForm({ ...form, arPerPoint: +e.target.value || 0 })}
            />
            <p className="text-xs text-muted-foreground">Ex : 100 = 1 point vaut 100 Ar de réduction.</p>
          </div>
        </div>
      </Section>

      <Section title="Magasins" padding="lg">
        <div className="space-y-3">
          {stores.length === 0 && (
            <p className="text-sm text-muted-foreground">Aucun magasin. Ajoutez-en un ci-dessous.</p>
          )}
          {stores.map((s) => (
            <div key={s.id} className="grid items-center gap-3 sm:grid-cols-[1fr_1fr_auto]">
              <Input
                value={s.name}
                onChange={(e) => updateStore(s.id, { name: e.target.value })}
                placeholder="Nom"
                aria-label={`Nom du magasin ${s.name}`}
              />
              <Input
                value={s.location}
                onChange={(e) => updateStore(s.id, { location: e.target.value })}
                placeholder="Adresse"
                aria-label={`Adresse du magasin ${s.name}`}
              />
              <ConfirmDialog
                trigger={
                  <IconButton
                    icon={Trash2}
                    tone="destructive"
                    title="Supprimer"
                    aria-label={`Supprimer le magasin ${s.name}`}
                  />
                }
                title={`Supprimer le magasin "${s.name}" ?`}
                description="Cette action est définitive. Les ventes passées resteront mais ne pourront plus être rattachées."
                destructive
                onConfirm={() => {
                  const r = deleteStore(s.id);
                  if (r.ok) toast.success("Magasin supprimé");
                  else toast.error(r.error ?? "Suppression impossible");
                }}
              />
            </div>
          ))}
        </div>

        <div className="mt-5 border-t border-border pt-4">
          <Label htmlFor="new-store-name" className="mb-2 block">Ajouter un magasin</Label>
          <div className="grid items-center gap-3 sm:grid-cols-[1fr_1fr_auto]">
            <Input
              id="new-store-name"
              value={newStoreName}
              onChange={(e) => setNewStoreName(e.target.value)}
              placeholder="Nom (ex: Magasin C)"
            />
            <Input
              id="new-store-location"
              value={newStoreLocation}
              onChange={(e) => setNewStoreLocation(e.target.value)}
              placeholder="Adresse / Ville"
              aria-label="Adresse du nouveau magasin"
            />
            <Button
              variant="outline"
              onClick={() => {
                const r = addStore({ name: newStoreName, location: newStoreLocation });
                if (r.ok) {
                  toast.success("Magasin ajouté");
                  setNewStoreName("");
                  setNewStoreLocation("");
                } else {
                  toast.error(r.error ?? "Ajout impossible");
                }
              }}
            >
              <Plus className="mr-2 h-4 w-4" /> Ajouter
            </Button>
          </div>
        </div>
      </Section>

      <div className="flex flex-wrap gap-3">
        <Button variant="gradient" onClick={save}>
          <Save className="mr-2 h-4 w-4" /> Enregistrer les paramètres
        </Button>
        <Button variant="outline" onClick={doExport}>
          <Download className="mr-2 h-4 w-4" /> Exporter JSON
        </Button>
        <label>
          <input
            type="file"
            accept="application/json"
            className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) doImport(f); e.target.value = ""; }}
          />
          <Button variant="outline" asChild>
            <span><Upload className="mr-2 h-4 w-4" /> Importer JSON</span>
          </Button>
        </label>
        <ConfirmDialog
          trigger={
            <Button variant="destructive">
              <RefreshCw className="mr-2 h-4 w-4" /> Réinitialiser
            </Button>
          }
          title="Réinitialiser TOUTES les données locales ?"
          description="Toutes les ventes, produits et clients enregistrés localement seront définitivement perdus. Cette action est irréversible."
          confirmText="Tout réinitialiser"
          destructive
          onConfirm={doReset}
        />
      </div>
    </div>
  );
}
