import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { Button } from "./Button";
import { cn } from "@/lib/utils";

export interface EmptyStateAction {
  label: string;
  onClick: () => void;
  icon?: LucideIcon;
}

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: string;
  description?: string;
  action?: EmptyStateAction;
  /**
   * - `comfortable` (défaut) : padding généreux, centré dans un container.
   * - `compact` : pour une cellule de tableau ou un slot étroit.
   * - `inline` : juste un texte muted, sans icône ni centrage — pour les
   *   placeholders fins ("Aucune donnée.") à l'intérieur d'une Section.
   */
  density?: "compact" | "comfortable" | "inline";
  className?: string;
}

/**
 * État vide standard. Remplace les ≥ 8 implémentations inline
 * ("Aucun produit", "Aucune vente", "Sélectionnez des produits…", etc.).
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  density = "comfortable",
  className,
}: EmptyStateProps) {
  if (density === "inline") {
    return (
      <p role="status" className={cn("text-sm text-muted-foreground", className)}>
        {title}
      </p>
    );
  }

  return (
    <div
      role="status"
      className={cn(
        "flex flex-col items-center justify-center text-center",
        density === "comfortable" ? "py-16" : "py-8",
        className,
      )}
    >
      {Icon && (
        <div
          aria-hidden="true"
          className={cn(
            "mb-3 flex items-center justify-center rounded-full bg-secondary text-muted-foreground",
            density === "comfortable" ? "h-12 w-12" : "h-10 w-10",
          )}
        >
          <Icon className={cn(density === "comfortable" ? "h-5 w-5" : "h-4 w-4")} />
        </div>
      )}
      <p
        className={cn(
          "font-semibold tracking-display text-foreground",
          density === "comfortable" ? "text-base" : "text-sm",
        )}
      >
        {title}
      </p>
      {description && (
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
      )}
      {action && (
        <Button
          variant="outline"
          size="sm"
          onClick={action.onClick}
          className="mt-4"
        >
          {action.icon && <action.icon className="mr-2 h-4 w-4" />}
          {action.label}
        </Button>
      )}
    </div>
  );
}
