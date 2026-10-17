-- The market_data database's three roles (E1-T6 nod ruling 9, E1-T7). Run once, by the instance's superuser,
-- when the database is created (deploy/postgres/init-roles.sh, from the Postgres container's first start).
--   market_data_owner  owns the database and its schema: Flyway runs as it (TRADEBENCH_DB_MIGRATE_*).
--   market_data_app    reads and writes rows, no DDL: the collector's pool (TRADEBENCH_DB_USER).
--   market_data_reader reads only: people's SQL tools, and later control-service (the published data product, D17).
-- psql variables: db, owner_password, app_password, reader_password.
CREATE ROLE market_data_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE market_data_app LOGIN PASSWORD :'app_password';
CREATE ROLE market_data_reader LOGIN PASSWORD :'reader_password';
ALTER DATABASE :"db" OWNER TO market_data_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO market_data_app, market_data_reader;
-- Postgres 15+: the public schema is owned by the database's owner and grants CREATE to nobody, so neither
-- needs saying (this file pins Postgres 16, deploy/compose.prod.yaml); USAGE is the one grant the two roles need.
GRANT USAGE ON SCHEMA public TO market_data_app, market_data_reader;
-- Whatever the owner creates from here on — every Flyway migration's tables and sequences — is granted as it is made.
ALTER DEFAULT PRIVILEGES FOR ROLE market_data_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO market_data_app;
ALTER DEFAULT PRIVILEGES FOR ROLE market_data_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO market_data_app;
ALTER DEFAULT PRIVILEGES FOR ROLE market_data_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO market_data_reader;
