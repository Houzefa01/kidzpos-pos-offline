# `features/` — Modules métier (couche 3)

Composants qui assemblent du DS avec de la logique métier (stores Zustand, hooks domaine, calls API).

## Règles strictes

1. Un module = un dossier (`features/pos/`, `features/sales/`, `features/stock/`...).
2. Chaque dossier contient un `index.ts` qui définit la surface publique du module.
3. **Une feature n'importe pas une autre feature**. Si du code doit être partagé, il monte dans `ds/` (visuel) ou `lib/` / `store/` (métier).
4. **Imports autorisés** : `ds/*`, `ui/*` (en dernier recours), `store/*`, `lib/*`, `hooks/*`. **Interdits** : `pages/*` (sauf via typage).
5. Les pages (`src/pages/`) deviennent de fines compositions de features. Si une page contient > 50 lignes de JSX, c'est qu'elle devrait extraire des features.

## Statut

Vide pour l'instant. Phase 4 (post-migration des pages) : extraction des features lourdes :

```
features/
  pos/            ← Cart, ProductGrid, PaymentPanel, QuickProductDialog (depuis POS.tsx)
  sales/          ← SalesTable, SalesFilters (depuis Sales.tsx)
  stock/          ← StockTable, StockFilters, AdjustDialog, TransferDialog
  customers/      ← CustomerTable, CustomerDetail
  dashboard/      ← Charts, RecentActivity, TopProducts
  users/          ← UserTable, UserFormDialog, PasswordDialog
```
