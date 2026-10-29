import * as React from "react";
import { cn } from "@/lib/utils";

export interface KbdHintProps extends React.HTMLAttributes<HTMLElement> {
  /** Variante visuelle. `inline` = dans une ligne de texte, `chip` = pastille discrète. */
  variant?: "inline" | "chip";
}

/**
 * `<kbd>` stylisé pour afficher un raccourci (F2, F9, Échap, ⌘K…).
 * Toujours `aria-hidden` — l'info est purement visuelle, les vrais
 * raccourcis sont gérés par `useHotkeys`.
 */
export const KbdHint = React.forwardRef<HTMLElement, KbdHintProps>(
  ({ variant = "chip", className, children, ...props }, ref) => (
    <kbd
      ref={ref}
      aria-hidden="true"
      className={cn(
        "items-center font-mono",
        variant === "chip"
          ? "inline-flex h-5 rounded border border-border bg-secondary px-1.5 text-2xs text-muted-foreground"
          : "rounded-md border border-border bg-card px-2 py-0.5 text-xs shadow-sm",
        className,
      )}
      {...props}
    >
      {children}
    </kbd>
  ),
);
KbdHint.displayName = "KbdHint";
