import { useId } from "react";
import { cn } from "@/lib/utils";

interface BrandMarkProps {
  className?: string;
  /** Tailles standard. Pour les cas spéciaux passer `className` avec h-* w-*. */
  size?: "sm" | "md" | "lg";
}

const sizeMap = { sm: "h-7 w-7", md: "h-8 w-8", lg: "h-9 w-9" } as const;

/**
 * Logo KidzPOS — carré arrondi indigo→rose, "K" en négatif.
 * Couleurs via tokens HSL pour rester cohérent avec le thème (light/dark).
 */
export function BrandMark({ className, size = "md" }: BrandMarkProps) {
  // useId évite les collisions de gradient si plusieurs logos coexistent sur la page.
  const rawId = useId().replace(/[^\w]/g, "");
  const gradientId = `brand-mark-${rawId}`;

  return (
    <svg viewBox="0 0 32 32" className={cn(sizeMap[size], "shrink-0", className)} aria-hidden="true">
      <defs>
        <linearGradient id={gradientId} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="hsl(var(--primary))" />
          <stop offset="100%" stopColor="hsl(var(--accent))" />
        </linearGradient>
      </defs>
      <rect x="2" y="2" width="28" height="28" rx="7" fill={`url(#${gradientId})`} />
      <path
        d="M 11.5 9.5 L 11.5 22.5 M 11.5 16 L 19 9.5 M 13 16 L 21 22.5"
        stroke="hsl(var(--primary-foreground))"
        strokeWidth="2.4"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
