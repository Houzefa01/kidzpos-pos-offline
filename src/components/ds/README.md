# `ds/` — Design System KidzPOS (couche 2)

Composants opinionés du design system. Seule source de vérité pour les patterns visuels du produit.

## Règles strictes

1. **Tout pattern visuel utilisé ≥ 2 fois doit vivre ici.** Si une page le duplique inline, c'est un bug.
2. **Zéro valeur arbitraire Tailwind.** Pas de `text-[11px]`, `tracking-[0.14em]`, `gap-2.5`. Tout passe par les tokens de `tailwind.config.ts`.
3. **Tokens HSL uniquement** pour les couleurs : `hsl(var(--primary))`, jamais `#cc3373` ou `hsl(232 72% 54%)` en dur.
4. Un composant DS **n'embarque pas** de logique métier (pas de Zustand, pas de `useAuth`, pas d'API). Il reçoit ses données par props.
5. **Pas de re-export massif depuis `ui/`** — on ne wrappe que ce qu'on veut contrôler (Button avec variant gradient, Card via Section). Les autres primitives shadcn restent importées depuis `ui/`.
6. **Imports autorisés** : `ui/*`, `lib/utils`, `lib/money` (formatters purs), Radix UI, lucide-react. **Interdits** : `store/*`, `pages/*`, `features/*`.

## Composants

| Fichier | Rôle | Remplace |
|---|---|---|
| `Button.tsx` | Button avec variant `gradient` | `<Button className="gradient-primary..."/>`, `<Button className="btn-gradient"/>` |
| `BrandMark.tsx` | Logo SVG unique | `HibiscusMark` (sidebar) + `BrandMark` (login) |
| `PageHeader.tsx` | Eyebrow + titre + sous-titre + actions | 8× headers de page inline |
| `Section.tsx` | Card avec padding/title/icon | 14× `Card.gradient-card.border-border.p-X` |
| `Stat.tsx` | Tuile de stat (compact/comfortable) | `StatCard` + `StatPill` + customer detail tiles |
| `SearchInput.tsx` | Input + icône Search + kbd hint | 5× `<div relative><Search abs/><Input pl-9/></div>` |
| `FilterSelect.tsx` | Select avec option « all » | Patterns Stock/Sales filters |
| `EmptyState.tsx` | Icon + titre + description + action | 8× empty states inline |
| `KbdHint.tsx` | `<kbd>` stylisé | Indicateurs F2/F9 dispersés |
| `ReceiptOverlay.tsx` | Dialog + Receipt + Imprimer/PDF | 2× duplication POS/Sales |
| `Stack.tsx` `Cluster.tsx` `Grid.tsx` | Layout primitives | Chaînes flex/grid répétées |

## Import depuis le reste de l'app

```ts
import { PageHeader, Section, Stat, Button } from "@/components/ds";
```
