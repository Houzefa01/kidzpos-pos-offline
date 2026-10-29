import * as React from "react";
import { Search, type LucideIcon } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { KbdHint } from "./KbdHint";

export interface SearchInputProps extends React.ComponentProps<"input"> {
  /** Icône leading (défaut : Search). Passer `null` pour la retirer. */
  icon?: LucideIcon | null;
  /** Hint clavier affiché en absolute à droite (ex: "F2"). */
  kbdHint?: string;
  /** Wrapper className (le container relatif). */
  wrapperClassName?: string;
}

/**
 * Input de recherche standardisé. Remplace les 5+ implémentations
 * inline de `<div relative><Icon abs/><Input pl-9/></div>`.
 */
export const SearchInput = React.forwardRef<HTMLInputElement, SearchInputProps>(
  ({ icon = Search, kbdHint, wrapperClassName, className, ...inputProps }, ref) => {
    const Icon = icon;
    return (
      <div className={cn("relative min-w-0", wrapperClassName)}>
        {Icon && (
          <Icon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        )}
        <Input
          ref={ref}
          // Pas de `type="search"` par défaut : la croix native (-webkit-search-cancel-button)
          // changerait l'apparence à droite. L'attribut role/sémantique passe par l'icône
          // visible et un aria-label que l'appelant peut fournir.
          className={cn(Icon && "pl-9", kbdHint && "pr-12", className)}
          {...inputProps}
        />
        {kbdHint && (
          <KbdHint className="pointer-events-none absolute right-2.5 top-1/2 hidden -translate-y-1/2 sm:inline-flex">
            {kbdHint}
          </KbdHint>
        )}
      </div>
    );
  },
);
SearchInput.displayName = "SearchInput";
