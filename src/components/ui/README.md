# `ui/` — Primitives shadcn (couche 1)

Composants bas niveau générés par shadcn (Radix UI + class-variance-authority).

## Règles strictes

- **Ne JAMAIS modifier ces fichiers à la main.** Régénérer via la CLI shadcn si besoin.
- **Ne JAMAIS importer `ui/*` directement depuis une page (`src/pages/`)** une fois la migration faite — passer par `ds/`.
- Exceptions tolérées : `Input`, `Label`, `Dialog*`, `Select*`, `Table*`, `Tabs*`, `Switch`, `Badge`, `Sidebar*` qui n'ont pas (encore) de wrapper DS. Mais imports depuis `ds/` à privilégier dès qu'un wrapper existe.

## Couches

```
ui/         ← shadcn brut (cette couche)
ds/         ← Design system : wrappe ui/ + tokens + composants composites
features/   ← Modules métier qui assemblent ds/
pages/      ← Routes : assemblent features/ + ds/
```

Plus on monte, plus c'est métier. Une couche n'importe **jamais** vers le bas (`ui/` ne peut pas importer `ds/` ou `features/`).
