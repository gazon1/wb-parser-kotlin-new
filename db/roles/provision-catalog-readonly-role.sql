-- ==============================================================================
-- provision-catalog-readonly-role.sql — the psql entry point.
--
--   psql -v ON_ERROR_STOP=1 -v catalog_password="$CATALOG_DB_PASSWORD" \
--        -f db/roles/provision-catalog-readonly-role.sql "$DATABASE_URL"
--
-- The password is passed as a psql variable, never as an argument and never as a
-- literal in this file: psql does not echo the statements it runs, so the secret
-- does not reach the terminal, the CI log, or the shell history.
--
-- ## What it does
--
-- It puts the password into a session setting and then runs
-- `catalog-readonly-role.sql` from this directory, which holds the rights themselves. The
-- split is deliberate: the rights are pure SQL and are executed by the crawler's
-- demo bootstrap as well, so there is one copy of them; this file is only the
-- psql-specific shell around them.
--
-- ## Idempotent, and "idempotent" here means "safe"
--
-- Running it twice does not fail, and running it against a role that was handed
-- extra privileges by somebody else does not leave those privileges in place. It
-- brings the role to the state described below, or it fails with a clear message.
--
-- ## What the role may do
--
--   CONNECT on the database, USAGE on the schema `public`, SELECT on the tables
--   the catalog reads. Nothing else. In particular it does not own those tables,
--   it is not a member of any other role, and it holds no CREATE, INSERT,
--   UPDATE, DELETE, TRUNCATE, REFERENCES or TRIGGER anywhere.
--
-- ## Why ownership would matter
--
-- The owner of a table can do nearly everything to it, `ALTER` included, and that
-- is not visible in `information_schema.role_table_grants` — which shows explicit
-- grants and says nothing about ownership, about role membership, or about the
-- rights PUBLIC holds. So the script refuses to continue if the role owns
-- anything, instead of quietly granting SELECT on top of an owner.
--
-- The verification of the result lives in a separate script,
-- `verify-catalog-readonly-role.sql`, because a script that both applies and
-- checks its own work cannot tell "I did what I meant" from "I did what I always
-- do".
-- ==============================================================================

\set ON_ERROR_STOP on

\if :{?catalog_password}
\else
    \echo 'catalog_password is not set. Pass it with -v catalog_password=... — the role has no usable password otherwise.'
    \quit 1
\endif

-- The setting is session-local, and this file is one session.
SELECT set_config('wbparser.role_password', :'catalog_password', false);

\ir catalog-readonly-role.sql

\echo 'wb_catalog_ro is provisioned. Run db/roles/verify-catalog-readonly-role.sql to check the effective rights.'
