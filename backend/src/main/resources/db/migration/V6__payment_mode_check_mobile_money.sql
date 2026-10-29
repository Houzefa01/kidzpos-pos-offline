-- V6 : la contrainte CHECK sales_payment_mode_check date d'un ancien
-- ddl-auto:create (Hibernate génère un CHECK depuis @Enumerated(STRING))
-- et n'inclut pas MOBILE_MONEY. Avec ddl-auto:validate désormais, elle
-- persiste sans être tenue à jour.
--
-- On recrée la contrainte avec les 4 valeurs courantes. Source de vérité
-- côté Java = enum PaymentMode ; cette CHECK est une garde en profondeur.
ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_payment_mode_check;
ALTER TABLE sales ADD CONSTRAINT sales_payment_mode_check
    CHECK (payment_mode IN ('CASH', 'CARD', 'MIXED', 'MOBILE_MONEY'));
