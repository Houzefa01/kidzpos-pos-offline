-- ─────────────────────────────────────────────────────────────────────────────
-- init-test-databases.sql — Crée les 2 bases de test sur le Postgres local.
--
-- À exécuter UNE FOIS, en superuser :
--   psql -U postgres -h localhost -f init-test-databases.sql
--
-- Suppose qu'un rôle "kidzpos" / mot de passe "kidzpos" existe déjà :
--   CREATE USER kidzpos WITH PASSWORD 'kidzpos';
-- (sinon, ajouter cette ligne avant les CREATE DATABASE)
--
-- Pour repartir de zéro :
--   DROP DATABASE IF EXISTS kidzpos_central;
--   DROP DATABASE IF EXISTS kidzpos_store_s1;
-- ─────────────────────────────────────────────────────────────────────────────

-- Crée le rôle s'il n'existe pas (idempotent via DO block)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'kidzpos') THEN
        CREATE ROLE kidzpos WITH LOGIN PASSWORD 'kidzpos';
    END IF;
END$$;

-- Base CENTRAL (agrège les events de tous les magasins)
SELECT 'CREATE DATABASE kidzpos_central OWNER kidzpos'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kidzpos_central')\gexec

-- Base STORE s1 (un magasin local — répliquer pour s2, s3… si besoin)
SELECT 'CREATE DATABASE kidzpos_store_s1 OWNER kidzpos'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kidzpos_store_s1')\gexec

GRANT ALL PRIVILEGES ON DATABASE kidzpos_central  TO kidzpos;
GRANT ALL PRIVILEGES ON DATABASE kidzpos_store_s1 TO kidzpos;

\echo ''
\echo '✓ Bases créées :'
\echo '    kidzpos_central   (port 8080)'
\echo '    kidzpos_store_s1  (port 8081)'
\echo ''
\echo 'Flyway créera automatiquement le schéma au premier démarrage de chaque serveur.'
