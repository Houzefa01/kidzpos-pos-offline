import { useEffect, useState } from "react";
import { useOnlineStatus } from "@/hooks/useOnlineStatus";
import { useBackend } from "@/store/backend";
import { useFailedReplays } from "@/store/failedReplays";
import { WifiOff, CloudOff, CloudUpload, AlertCircle, Globe } from "lucide-react";
import { FailedReplaysButton } from "@/components/FailedReplaysButton";

// T15 — Seuils d'affichage du badge "central sync". En deçà : silencieux
// (régime nominal). 10 ops OU 60s = signal faible. >300s = couleur d'alerte.
const CENTRAL_BACKLOG_THRESHOLD = 10;
const CENTRAL_LAG_THRESHOLD_S = 60;
const CENTRAL_LAG_CRITICAL_S = 300;

export function OfflineBanner() {
  const online = useOnlineStatus();
  const { lanReachable, pendingCount, centralSync } = useBackend();
  const failedCount = useFailedReplays((s) => s.failures.length);

  // T15 — Affiche le badge si le backend store accumule du retard vers le central.
  // nodeRole "central" lui-même n'a personne au-dessus → on ne décore pas.
  const showCentralBadge = centralSync !== null
    && centralSync.nodeRole !== "central"
    && (centralSync.pending >= CENTRAL_BACKLOG_THRESHOLD
        || centralSync.lagSeconds >= CENTRAL_LAG_THRESHOLD_S);
  const centralLagCritical = (centralSync?.lagSeconds ?? 0) >= CENTRAL_LAG_CRITICAL_S;

  // Ne pas afficher "injoignable" immédiatement — attendre 3s de non-connexion soutenue
  // pour éviter le flash pendant le chargement initial (le premier ping prend ~100ms).
  const [showInjoignable, setShowInjoignable] = useState(false);
  useEffect(() => {
    if (lanReachable) { setShowInjoignable(false); return; }
    const t = setTimeout(() => setShowInjoignable(true), 3000);
    return () => clearTimeout(t);
  }, [lanReachable]);

  // Tout est sain ET aucun échec → bannière masquée (comportement historique).
  // T15 — sauf si le backend store a un backlog significatif vers le central.
  if (online && lanReachable && pendingCount === 0 && failedCount === 0 && !showCentralBadge) return null;

  // Banner non-sticky en lui-même : AppLayout l'enveloppe avec le header dans
  // un parent sticky commun, sinon banner + header sticky top-0 se chevaucheraient.
  const baseCls = "no-print flex flex-wrap items-center justify-center gap-2 px-3 py-1.5 text-xs font-medium";

  if (!online) {
    return (
      <div className={`${baseCls} bg-warning/15 text-foreground`}>
        <WifiOff className="h-3.5 w-3.5" />
        <span>
          Mode hors-ligne — vos actions sont enregistrées localement et seront synchronisées
          {pendingCount > 0 && ` (${pendingCount} en attente)`}
        </span>
        <FailedReplaysButton count={failedCount} />
      </div>
    );
  }

  if (!lanReachable && showInjoignable) {
    return (
      <div className={`${baseCls} bg-warning/15 text-foreground`}>
        <CloudOff className="h-3.5 w-3.5" />
        <span>
          Serveur magasin injoignable — données sauvegardées localement
          {pendingCount > 0 && ` · ${pendingCount} action(s) en file d'attente`}
        </span>
        <FailedReplaysButton count={failedCount} />
      </div>
    );
  }

  if (lanReachable && pendingCount > 0) {
    return (
      <div className={`${baseCls} bg-primary/15 text-primary`}>
        <CloudUpload className="h-3.5 w-3.5 animate-pulse" />
        <span>Synchronisation en cours… {pendingCount} action(s) restantes</span>
        <FailedReplaysButton count={failedCount} />
      </div>
    );
  }

  // Tout sain MAIS des échecs persistants → bannière dédiée discrète.
  if (failedCount > 0) {
    return (
      <div className={`${baseCls} bg-destructive/10 text-foreground`}>
        <AlertCircle className="h-3.5 w-3.5 text-destructive" />
        <span>
          {failedCount} action{failedCount > 1 ? "s" : ""} non synchronisée
          {failedCount > 1 ? "s" : ""} — rejetée
          {failedCount > 1 ? "s" : ""} par le serveur
        </span>
        <FailedReplaysButton count={failedCount} />
      </div>
    );
  }

  // T15 — Backend store en retard de push vers le central. Pas un blocage UX
  // (la caisse continue normalement), mais l'opérateur doit savoir qu'éteindre
  // la machine maintenant = risque de perte si la sync ne reprend pas.
  if (showCentralBadge && centralSync) {
    const lagDisplay = centralSync.lagSeconds < 60
      ? `${centralSync.lagSeconds}s`
      : centralSync.lagSeconds < 3600
        ? `${Math.round(centralSync.lagSeconds / 60)} min`
        : `${Math.round(centralSync.lagSeconds / 3600)} h`;
    const cls = centralLagCritical ? "bg-destructive/10 text-foreground" : "bg-warning/15 text-foreground";
    return (
      <div className={`${baseCls} ${cls}`}>
        <Globe className={`h-3.5 w-3.5 ${centralLagCritical ? "text-destructive" : ""}`} />
        <span>
          {centralSync.pending} opération{centralSync.pending > 1 ? "s" : ""} en attente
          de réplication vers le central · retard {lagDisplay}
        </span>
        <FailedReplaysButton count={failedCount} />
      </div>
    );
  }

  return null;
}
