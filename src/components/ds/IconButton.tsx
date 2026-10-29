import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { Button, type ButtonProps } from "./Button";
import { cn } from "@/lib/utils";

type IconButtonVariant = "standard" | "compact";
type IconButtonTone = "default" | "destructive" | "warning";

/**
 * Surface héritée du Button DS, moins ce que IconButton contrôle lui-même :
 * - `size` et `variant` : imposés (`icon` + `ghost`).
 * - `children` : remplacé par le couple `icon` + spec interne.
 * - `aria-label` : ré-introduit en **requis** ci-dessous.
 * - `asChild` : pas de sens (l'IconButton n'a pas d'enfant DOM unique à
 *   slotter — il rend toujours un `<button><svg/></button>`).
 */
export interface IconButtonProps
  extends Omit<ButtonProps, "size" | "variant" | "children" | "aria-label" | "asChild"> {
  icon: LucideIcon;
  /** **Obligatoire**. Décrit l'action ("Supprimer le produit X"), pas l'icône. */
  "aria-label": string;
  /**
   * - `standard` (défaut) : h-10, icône 16px. Tables courantes (Customers, Stock, Users).
   * - `compact` : h-7, icône 14px. Tables denses (Sales).
   */
  variant?: IconButtonVariant;
  /** Teinte de l'icône uniquement. Le hover reste neutre — pour un hover
   *  spécifique (ex: `hover:bg-warning/10`), passer une `className` qui
   *  override via tailwind-merge. */
  tone?: IconButtonTone;
}

const containerCls: Record<IconButtonVariant, string> = {
  standard: "",
  compact: "h-7 w-7 rounded-md hover:bg-secondary",
};

const iconCls: Record<IconButtonVariant, string> = {
  standard: "h-4 w-4",
  compact: "h-3.5 w-3.5",
};

const toneCls: Record<IconButtonTone, string> = {
  default: "",
  destructive: "text-destructive",
  warning: "text-warning",
};

export const IconButton = React.forwardRef<HTMLButtonElement, IconButtonProps>(
  ({ icon: Icon, variant = "standard", tone = "default", className, ...props }, ref) => (
    <Button
      ref={ref}
      size="icon"
      variant="ghost"
      className={cn(containerCls[variant], className)}
      {...props}
    >
      <Icon className={cn(iconCls[variant], toneCls[tone])} aria-hidden="true" />
    </Button>
  ),
);
IconButton.displayName = "IconButton";
