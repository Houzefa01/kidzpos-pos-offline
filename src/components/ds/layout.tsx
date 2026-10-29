import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Primitives de mise en page minimales. Le but : empêcher la duplication
 * des chaînes `flex flex-col gap-N` / `grid grid-cols-X gap-Y` dispersées
 * dans le code. **Pas une lib de layout** — juste 3 helpers stables.
 *
 * Gap : on s'aligne sur l'échelle Tailwind par défaut (1=4px, 2=8px,
 * 3=12px, 4=16px, 5=20px, 6=24px, 8=32px). Pas de `.5`.
 */

type Gap = 1 | 2 | 3 | 4 | 5 | 6 | 8;
const gapMap: Record<Gap, string> = {
  1: "gap-1",
  2: "gap-2",
  3: "gap-3",
  4: "gap-4",
  5: "gap-5",
  6: "gap-6",
  8: "gap-8",
};

type Align = "start" | "center" | "end" | "baseline" | "stretch";
const alignItemsMap: Record<Align, string> = {
  start: "items-start",
  center: "items-center",
  end: "items-end",
  baseline: "items-baseline",
  stretch: "items-stretch",
};

type Justify = "start" | "center" | "end" | "between" | "around";
const justifyMap: Record<Justify, string> = {
  start: "justify-start",
  center: "justify-center",
  end: "justify-end",
  between: "justify-between",
  around: "justify-around",
};

// ─── Stack : flex vertical ─────────────────────────────────────────
export interface StackProps extends React.HTMLAttributes<HTMLDivElement> {
  gap?: Gap;
  align?: Align;
  as?: keyof JSX.IntrinsicElements;
}

export const Stack = React.forwardRef<HTMLDivElement, StackProps>(
  ({ gap = 4, align, as: Tag = "div", className, ...props }, ref) => {
    const Comp = Tag as React.ElementType;
    return (
      <Comp
        ref={ref}
        className={cn("flex flex-col", gapMap[gap], align && alignItemsMap[align], className)}
        {...props}
      />
    );
  },
);
Stack.displayName = "Stack";

// ─── Cluster : flex horizontal avec wrap ───────────────────────────
export interface ClusterProps extends React.HTMLAttributes<HTMLDivElement> {
  gap?: Gap;
  align?: Align;
  justify?: Justify;
  wrap?: boolean;
}

export const Cluster = React.forwardRef<HTMLDivElement, ClusterProps>(
  ({ gap = 2, align = "center", justify, wrap = true, className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn(
        "flex",
        wrap && "flex-wrap",
        gapMap[gap],
        alignItemsMap[align],
        justify && justifyMap[justify],
        className,
      )}
      {...props}
    />
  ),
);
Cluster.displayName = "Cluster";

// ─── Grid : grille responsive simple (1 → cols selon breakpoint) ───
type Cols = 1 | 2 | 3 | 4 | 5 | 6;
const colsBase: Record<Cols, string> = {
  1: "grid-cols-1",
  2: "grid-cols-2",
  3: "grid-cols-3",
  4: "grid-cols-4",
  5: "grid-cols-5",
  6: "grid-cols-6",
};
const colsSm: Record<Cols, string> = {
  1: "sm:grid-cols-1",
  2: "sm:grid-cols-2",
  3: "sm:grid-cols-3",
  4: "sm:grid-cols-4",
  5: "sm:grid-cols-5",
  6: "sm:grid-cols-6",
};
const colsLg: Record<Cols, string> = {
  1: "lg:grid-cols-1",
  2: "lg:grid-cols-2",
  3: "lg:grid-cols-3",
  4: "lg:grid-cols-4",
  5: "lg:grid-cols-5",
  6: "lg:grid-cols-6",
};

export interface GridProps extends React.HTMLAttributes<HTMLDivElement> {
  cols?: Cols;
  /** Colonnes à partir du breakpoint `sm` (640px+). */
  sm?: Cols;
  /** Colonnes à partir du breakpoint `lg` (1024px+). */
  lg?: Cols;
  gap?: Gap;
}

export const Grid = React.forwardRef<HTMLDivElement, GridProps>(
  ({ cols = 1, sm, lg, gap = 4, className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn(
        "grid",
        colsBase[cols],
        sm && colsSm[sm],
        lg && colsLg[lg],
        gapMap[gap],
        className,
      )}
      {...props}
    />
  ),
);
Grid.displayName = "Grid";
