-- V9 : suppression des contraintes CHECK rigides générées par ddl-auto:create historique.
--
-- Contexte (cf CLAUDE.md, V6) : Hibernate générait des CHECK figées listant les
-- valeurs des @Enumerated(EnumType.STRING). Désormais en validate, ces CHECK
-- fossilisent et bloquent toute extension d'enum (SQLState 23514).
--
-- V6 a corrigé sales_payment_mode_check ponctuellement. V9 termine le ménage
-- pour tous les enums encore actifs. Source de vérité = enum Java.
-- (Re-créer ces CHECK avec la liste exhaustive est possible mais redondant
-- avec la validation côté code et reproduit le même piège à la prochaine
-- extension. On les retire purement.)

ALTER TABLE users           DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_type_check;

-- sales_payment_mode_check est déjà géré par V6 (re-créée à jour) — on la laisse.
-- Si on souhaite à terme aligner les 3 enums, retirer aussi V6 et ce check ici.
