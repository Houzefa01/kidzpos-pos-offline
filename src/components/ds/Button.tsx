import * as React from "react";
import { Button as UIButton, type ButtonProps as UIButtonProps } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type UIVariant = NonNullable<UIButtonProps["variant"]>;

export type ButtonVariant = UIVariant | "gradient";

export interface ButtonProps extends Omit<UIButtonProps, "variant"> {
  variant?: ButtonVariant;
}

/**
 * DS Button — wrappe le Button shadcn et expose une variante `gradient`
 * (la CTA primaire premium du produit) sans dupliquer la cva shadcn.
 *
 * Important : on ne modifie pas ui/button.tsx (généré par shadcn).
 * Le `.btn-gradient` utilitaire de index.css s'appuie sur `background`
 * (shorthand) et gagne sur `bg-primary` (background-color) grâce à l'ordre
 * des @layer utilities — donc pas besoin de `!important`.
 */
export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant, className, ...props }, ref) => {
    if (variant === "gradient") {
      return (
        <UIButton
          ref={ref}
          variant="default"
          className={cn("btn-gradient", className)}
          {...props}
        />
      );
    }
    return <UIButton ref={ref} variant={variant} className={className} {...props} />;
  },
);
Button.displayName = "Button";
