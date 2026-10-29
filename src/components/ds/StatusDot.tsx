import { cn } from "@/lib/utils";

export type StatusDotTone = "success" | "destructive" | "warning" | "primary";
export type StatusDotSize = "sm" | "md";

const toneCls: Record<StatusDotTone, string> = {
  success: "bg-success",
  destructive: "bg-destructive",
  warning: "bg-warning",
  primary: "bg-primary",
};

const sizeCls: Record<StatusDotSize, string> = {
  sm: "h-1.5 w-1.5",
  md: "h-2 w-2",
};

export interface StatusDotProps {
  tone: StatusDotTone;
  /** `sm` (h-1.5, défaut) accent inline ; `md` (h-2) indicateur dédié. */
  size?: StatusDotSize;
  className?: string;
}

/**
 * Pastille de statut HSL. Décorative par défaut (`aria-hidden`) — le sens
 * vient du texte adjacent (ex: « En ligne », « Serveur injoignable »).
 * 3 call-sites au moment de l'extraction : Settings (LAN), Login (en ligne),
 * POS (accent Panier).
 */
export function StatusDot({ tone, size = "sm", className }: StatusDotProps) {
  return (
    <span
      aria-hidden="true"
      className={cn("inline-block shrink-0 rounded-full", sizeCls[size], toneCls[tone], className)}
    />
  );
}
