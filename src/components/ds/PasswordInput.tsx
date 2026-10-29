import * as React from "react";
import { Eye, EyeOff } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

export interface PasswordInputProps extends Omit<React.ComponentProps<"input">, "type"> {
  /** Wrapper className (le container relatif). */
  wrapperClassName?: string;
}

/**
 * Champ mot de passe avec bouton afficher/masquer (œil qui s'ouvre/se ferme).
 *
 * Wrappe l'Input shadcn — toutes les props natives sont forwardées (id, value,
 * onChange, placeholder, required, autoComplete, autoFocus, aria-label…). Le
 * `type` est piloté en interne par l'état `show` et ne peut donc pas être surchargé.
 *
 * Le bouton est `tabIndex={-1}` : il ne casse pas le flux de tabulation du
 * formulaire (Tab passe du champ au bouton de soumission, pas à l'œil).
 */
export const PasswordInput = React.forwardRef<HTMLInputElement, PasswordInputProps>(
  ({ className, wrapperClassName, ...inputProps }, ref) => {
    const [show, setShow] = React.useState(false);
    return (
      <div className={cn("relative", wrapperClassName)}>
        <Input
          ref={ref}
          type={show ? "text" : "password"}
          // pr-10 : réserve la place du bouton œil pour ne pas masquer le texte.
          className={cn("pr-10", className)}
          {...inputProps}
        />
        <button
          type="button"
          onClick={() => setShow((s) => !s)}
          tabIndex={-1}
          aria-label={show ? "Masquer le mot de passe" : "Afficher le mot de passe"}
          aria-pressed={show}
          title={show ? "Masquer" : "Afficher"}
          className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-md text-muted-foreground transition hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {show ? (
            <EyeOff className="h-4 w-4" aria-hidden="true" />
          ) : (
            <Eye className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
      </div>
    );
  },
);
PasswordInput.displayName = "PasswordInput";
