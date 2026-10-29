import type { LucideIcon } from "lucide-react";
import { Backpack, Blocks, Gift, Puzzle, Rabbit, Shirt } from "lucide-react";
import { cn } from "@/lib/utils";

type CategoryKey = "Jouets" | "Vêtements" | "Accessoires" | "Peluches" | "Jeux éducatifs";

const ICON_MAP: Record<CategoryKey, LucideIcon> = {
  "Jouets": Blocks,
  "Vêtements": Shirt,
  "Accessoires": Backpack,
  "Peluches": Rabbit,
  "Jeux éducatifs": Puzzle,
};

const TINT_MAP: Record<CategoryKey, string> = {
  "Jouets": "tint-mangue",
  "Vêtements": "tint-hibiscus",
  "Accessoires": "tint-vetiver",
  "Peluches": "tint-baobab",
  "Jeux éducatifs": "tint-lagon",
};

const DEFAULT_ICON: LucideIcon = Gift;
const DEFAULT_TINT = "tint-vanille";

export interface CategoryIconProps {
  category?: string | null;
  /** Container className. Default `h-9 w-9 rounded-md`. */
  className?: string;
  /** SVG className. Default `h-5 w-5`. */
  iconClassName?: string;
}

/**
 * Vignette d'icône de catégorie produit. Carré arrondi tinted + icône lucide
 * monochrome. Remplace les emojis (offline garanti + cohérence DS).
 * Fallback : catégorie inconnue → Gift sur tint-vanille.
 */
export function CategoryIcon({ category, className, iconClassName }: CategoryIconProps) {
  const key = category as CategoryKey;
  const Icon = ICON_MAP[key] ?? DEFAULT_ICON;
  const tint = TINT_MAP[key] ?? DEFAULT_TINT;
  return (
    <span
      aria-hidden="true"
      className={cn("grid h-9 w-9 shrink-0 place-items-center rounded-md tint-dot", tint, className)}
    >
      <Icon className={cn("h-5 w-5", iconClassName)} />
    </span>
  );
}
