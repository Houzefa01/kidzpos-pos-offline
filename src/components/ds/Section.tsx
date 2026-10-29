import * as React from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { LucideIcon } from "lucide-react";

type Padding = "none" | "sm" | "md" | "lg";
const paddingMap: Record<Padding, string> = {
  none: "",
  sm: "p-4",
  md: "p-5",
  lg: "p-6",
};

export interface SectionProps extends React.HTMLAttributes<HTMLDivElement> {
  title?: string;
  description?: string;
  icon?: LucideIcon;
  /** Bouton / lien aligné à droite du titre. */
  action?: React.ReactNode;
  padding?: Padding;
  /** Ajoute un effet glow autour (à utiliser avec parcimonie). */
  elevated?: boolean;
}

/**
 * Section = Card shadcn + paddings standards + header optionnel.
 * Remplace les 14× `<Card className="gradient-card border-border p-X">…</Card>`.
 *
 * Utilisation :
 *   <Section title="Activité récente">…</Section>
 *   <Section padding="sm">…</Section>     ← cartes denses (barres de filtres)
 *   <Section padding="none">…</Section>   ← pour tables qui ont leur propre padding
 */
export const Section = React.forwardRef<HTMLDivElement, SectionProps>(
  ({ title, description, icon: Icon, action, padding = "md", elevated, className, children, ...props }, ref) => {
    return (
      <Card
        ref={ref}
        className={cn(
          "gradient-card border-border transition-shadow",
          elevated ? "shadow-elevated" : "shadow-card",
          paddingMap[padding],
          className,
        )}
        {...props}
      >
        {(title || action) && (
          <div className={cn("flex items-start justify-between gap-3", title && "mb-4")}>
            {title && (
              <div className="min-w-0">
                <h2 className="flex items-center gap-2 font-display text-lg font-semibold">
                  {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
                  {title}
                </h2>
                {description && (
                  <p className="mt-1 text-sm text-muted-foreground">{description}</p>
                )}
              </div>
            )}
            {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
          </div>
        )}
        {children}
      </Card>
    );
  },
);
Section.displayName = "Section";
