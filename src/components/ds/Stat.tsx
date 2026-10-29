import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export type StatTone = "neutral" | "primary" | "accent" | "success" | "warning" | "destructive";

const toneRingMap: Record<StatTone, string> = {
  neutral: "from-muted/30 to-muted/5 text-muted-foreground",
  primary: "from-primary/20 to-primary/5 text-primary",
  accent: "from-accent/20 to-accent/5 text-accent",
  success: "from-success/20 to-success/5 text-success",
  warning: "from-warning/20 to-warning/5 text-warning",
  destructive: "from-destructive/20 to-destructive/5 text-destructive",
};

const toneValueMap: Record<StatTone, string> = {
  neutral: "text-foreground",
  primary: "text-primary",
  accent: "text-accent",
  success: "text-success",
  warning: "text-warning",
  destructive: "text-destructive",
};

export interface StatProps {
  label: string;
  value: string;
  hint?: string;
  icon?: LucideIcon;
  tone?: StatTone;
  /**
   * - `comfortable` : tuile autoportante avec icône, hint et halo (ex Dashboard).
   * - `compact`     : ligne dans une grille de stats (ex Sales).
   */
  density?: "compact" | "comfortable";
  /** Met la valeur en gradient (text-gradient). Pour mettre en valeur un total. */
  highlight?: boolean;
  className?: string;
}

/**
 * Stat — unifie les anciennes implémentations StatCard, StatPill et les
 * tuiles de Customers/Settings. Une seule API.
 */
export function Stat({
  label,
  value,
  hint,
  icon: Icon,
  tone = "neutral",
  density = "comfortable",
  highlight,
  className,
}: StatProps) {
  if (density === "compact") {
    return (
      <div className={cn("relative flex min-w-0 flex-col gap-1 bg-card px-3 py-3 sm:px-5 sm:py-4", className)}>
        {highlight && (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute inset-0"
            style={{
              background:
                "radial-gradient(120% 100% at 100% 0%, hsl(var(--primary) / 0.12), transparent 60%), radial-gradient(120% 100% at 0% 100%, hsl(var(--accent) / 0.10), transparent 60%)",
            }}
          />
        )}
        <span className="relative truncate text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">
          {label}
        </span>
        <span
          className={cn(
            "relative truncate font-mono text-base font-semibold tracking-display sm:text-xl",
            highlight ? "text-gradient" : toneValueMap[tone],
          )}
        >
          {value}
        </span>
        {hint && <span className="relative truncate text-2xs text-muted-foreground">{hint}</span>}
      </div>
    );
  }

  // comfortable
  return (
    <div
      className={cn(
        "gradient-card relative overflow-hidden rounded-lg border border-border p-5 shadow-card transition-shadow hover:shadow-elevated",
        className,
      )}
    >
      <div
        aria-hidden="true"
        className={cn("absolute -right-6 -top-6 h-24 w-24 rounded-full bg-gradient-to-br blur-2xl", toneRingMap[tone])}
      />
      <div className="relative flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-2xs font-medium uppercase tracking-eyebrow text-muted-foreground">
            {label}
          </p>
          <p className="mt-2 font-display text-3xl font-bold">{value}</p>
          {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
        </div>
        {Icon && (
          <div className={cn("rounded-lg bg-gradient-to-br p-2", toneRingMap[tone])}>
            <Icon className="h-5 w-5" aria-hidden="true" />
          </div>
        )}
      </div>
    </div>
  );
}
