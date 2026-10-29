import * as React from "react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterSelectProps {
  value: string;
  onValueChange: (value: string) => void;
  options: FilterOption[];
  /** Texte de l'option « tout » (par défaut "Tous"). `null` pour la cacher. */
  allLabel?: string | null;
  /** Valeur utilisée pour l'option « tout » (par défaut "all"). */
  allValue?: string;
  placeholder?: string;
  className?: string;
  triggerClassName?: string;
  /** id pour aria-label / label externe. */
  id?: string;
  "aria-label"?: string;
}

/**
 * Select de filtre standard. Préfixe automatiquement une option « tout »
 * sauf si `allLabel={null}`. Évite de répéter le pattern
 * `<SelectItem value="all">Tous...</SelectItem>` dans chaque page.
 */
export function FilterSelect({
  value,
  onValueChange,
  options,
  allLabel = "Tous",
  allValue = "all",
  placeholder,
  className,
  triggerClassName,
  id,
  "aria-label": ariaLabel,
}: FilterSelectProps) {
  return (
    <Select value={value} onValueChange={onValueChange}>
      <SelectTrigger id={id} aria-label={ariaLabel} className={cn(triggerClassName, className)}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        {allLabel !== null && <SelectItem value={allValue}>{allLabel}</SelectItem>}
        {options.map((opt) => (
          <SelectItem key={opt.value} value={opt.value}>
            {opt.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
