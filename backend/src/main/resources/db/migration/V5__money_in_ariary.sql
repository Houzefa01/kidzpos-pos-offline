-- V5 : passage de la devise canonique de EUR à Ariary.
--
-- Décision : pas de conversion in-place (choix utilisateur). On purge les tables
-- monétaires et DataInitializer les repeuple en AR au prochain boot.
-- Les colonnes monétaires (price, subtotal, tax, total, amount_paid, change,
-- total_spent, ar_per_point, points_per_ar…) gardent leur type DOUBLE PRECISION ;
-- seule leur sémantique change — leur valeur désigne désormais des Ariary.
--
-- À partir de V5 : toute valeur stockée = Ariary. La devise globale par défaut
-- reste "AR" (déjà la valeur V1) ; le frontend convertit en EUR uniquement à
-- l'affichage si l'utilisateur le demande.

ALTER TABLE settings RENAME COLUMN points_per_euro TO points_per_ar;
ALTER TABLE settings RENAME COLUMN euro_per_point  TO ar_per_point;

-- Reset des données. sale_items cascade via FK ON DELETE CASCADE de sales.
-- Settings inclus : DataInitializer recrée la ligne id=1 avec les nouveaux defaults.
TRUNCATE TABLE sale_items, sales, stock_movements, products, customers, settings
    RESTART IDENTITY CASCADE;
