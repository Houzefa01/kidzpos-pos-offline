/**
 * Design System KidzPOS — barrel d'export public.
 *
 * Usage côté pages et features :
 *   import { PageHeader, Section, Stat, Button, SearchInput } from "@/components/ds";
 *
 * Voir `./README.md` pour les règles d'usage et la cartographie des
 * remplacements (quel composant DS remplace quel pattern inline).
 */

export { Button } from "./Button";
export type { ButtonProps, ButtonVariant } from "./Button";

export { IconButton } from "./IconButton";
export type { IconButtonProps } from "./IconButton";

export { CategoryIcon } from "./CategoryIcon";
export type { CategoryIconProps } from "./CategoryIcon";

export { StatusDot } from "./StatusDot";
export type { StatusDotProps, StatusDotTone, StatusDotSize } from "./StatusDot";

export { BrandMark } from "./BrandMark";
export { PageHeader } from "./PageHeader";
export type { PageHeaderProps } from "./PageHeader";

export { Section } from "./Section";
export type { SectionProps } from "./Section";

export { Stat } from "./Stat";
export type { StatProps, StatTone } from "./Stat";

export { SearchInput } from "./SearchInput";
export type { SearchInputProps } from "./SearchInput";

export { PasswordInput } from "./PasswordInput";
export type { PasswordInputProps } from "./PasswordInput";

export { FilterSelect } from "./FilterSelect";
export type { FilterSelectProps, FilterOption } from "./FilterSelect";

export { EmptyState } from "./EmptyState";
export type { EmptyStateProps, EmptyStateAction } from "./EmptyState";

export { KbdHint } from "./KbdHint";
export type { KbdHintProps } from "./KbdHint";

export { ReceiptOverlay } from "./ReceiptOverlay";
export type { ReceiptOverlayProps } from "./ReceiptOverlay";

export { Stack, Cluster, Grid } from "./layout";
export type { StackProps, ClusterProps, GridProps } from "./layout";
