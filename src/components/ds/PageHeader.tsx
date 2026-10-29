import * as React from "react";
import { cn } from "@/lib/utils";

export interface PageHeaderProps {
  /** Libellé en majuscules au-dessus du titre. Optionnel. */
  eyebrow?: string;
  /** Strict superset de string : permet des icônes lucide inline (cf Dashboard). */
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  /** Boutons / actions alignées à droite (Cluster naturel). */
  actions?: React.ReactNode;
  className?: string;
}

/**
 * Header de page standard. Remplace les 8 variantes inline (POS, Sales,
 * Dashboard, Stock, Settings, Users, Customers, Login). Une seule source
 * de vérité pour la hiérarchie typographique du H1.
 */
export function PageHeader({ eyebrow, title, subtitle, actions, className }: PageHeaderProps) {
  return (
    <header className={cn("flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between", className)}>
      <div className="min-w-0">
        {eyebrow && (
          <p className="text-label font-medium uppercase tracking-eyebrow text-muted-foreground">
            {eyebrow}
          </p>
        )}
        <h1 className="mt-0.5 text-2xl font-semibold leading-tight tracking-display sm:text-3xl">
          {title}
        </h1>
        {subtitle && (
          <p className="mt-1.5 max-w-md text-sm text-muted-foreground">{subtitle}</p>
        )}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}
