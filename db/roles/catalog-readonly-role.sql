-- ==============================================================================
-- catalog-readonly-role.sql — the rights of the catalog role, as pure SQL.
--
-- This file is the single source of truth for what `wb_catalog_ro` may do. It is
-- executed by two callers:
--
--   * `db/roles/provision-catalog-readonly-role.sql`, the psql entry point an
--     operator runs;
--   * the crawler's demo bootstrap, which needs the role to exist in a scratch
--     database before the catalog can read anything.
--
-- Two callers, one file. The rights are the part that matters, and a second copy
-- of them is a second thing to forget to change.
--
-- ## It is pure SQL on purpose
--
-- There are no psql meta-commands here, so the file can be executed by any
-- PostgreSQL client — `psql`, `pg_dump`, JDBC. The psql-specific parts (the
-- password variable, the messages) live in the entry point next to it.
--
-- ## The password comes from the caller
--
-- `ALTER ROLE … PASSWORD` takes a literal, not an expression, so the value is
-- read from a session setting and put into the statement by `format`. The caller
-- sets it:
--
--     SELECT set_config('wbparser.role_password', '<the password>', false);
--
-- The setting is session-local and disappears with the session. It is not
-- written into this file, and it is not echoed by anything here.
-- ==============================================================================

-- ── The tables the catalog reads, and the tables it must not ────────────────
-- The read set is written out rather than derived from a pattern. A pattern
-- would grant SELECT on whatever table happens to appear next, which is the
-- opposite of what a read-only role is for.
--
-- The known-other list is what makes a schema change visible: a table that is in
-- neither list is a table nobody has decided about, and the script stops instead
-- of ignoring it.

DO $$
DECLARE
    catalog_tables text[] := ARRAY['scraped_items', 'crawl_targets'];
    known_other text[] := ARRAY[
        'crawl_jobs', 'crawl_errors', 'target_api_contexts',
        'category_mappings', 'flyway_schema_history',
        -- The crawler's dictionary of WB's product subjects: id → name. The catalog
        -- does not read it — the product rows already carry the names the crawler
        -- resolved — but it is a table this script has decided about, so it belongs
        -- here rather than in the "nobody has decided" bucket that stops the script.
        'wb_subjects',
        -- One row per product: the most recent observation of it. The catalog reads
        -- the observations themselves and decides what "latest" means for its own
        -- questions; this view is the crawler's answer to the storefront's
        -- "what does this cost right now". It is not catalog data, so it is not read
        -- by the catalog role — but it is a table this script has decided about.
        'latest_product_observations',
        -- The demo seed's own marker: the table that says which environment this
        -- database is. The catalog has no business reading it — it is not catalog
        -- data — but it is a table this script has decided about, so it belongs here
        -- rather than in the "nobody has decided" bucket that stops the script.
        'catalog_demo_marker',
        -- The crawler's egress groups: which AdsPower profiles are expected to share
        -- one egress IP, and which IP the probe actually saw. The catalog has no
        -- business reading it — it says nothing about products — but it is a table this
        -- script has decided about, so it belongs here rather than in the "nobody has
        -- decided" bucket that stops the script.
        'crawl_egress_groups'
    ];
    missing text[];
    unknown text[];
BEGIN
    SELECT array_agg(t)
      INTO missing
      FROM unnest(catalog_tables) AS t
     WHERE to_regclass('public.' || t) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'the catalog reads tables that do not exist: % — the schema and the catalog are out of step',
            missing;
    END IF;

    SELECT array_agg(c.relname)
      INTO unknown
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p', 'v', 'm')
       AND c.relname <> ALL (catalog_tables)
       AND c.relname <> ALL (known_other);

    IF unknown IS NOT NULL THEN
        RAISE EXCEPTION
            'the public schema holds tables that are neither in the catalog read set (%) nor in the known list (%): % — decide whether the catalog may read them, and add them to one of the lists',
            catalog_tables, known_other, unknown;
    END IF;
END $$;

-- ── The role itself ─────────────────────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wb_catalog_ro') THEN
        CREATE ROLE wb_catalog_ro;
    END IF;
END $$;

-- The attributes are set explicitly rather than left to whatever the role was
-- created with. SUPERUSER, CREATEDB, CREATEROLE and BYPASSRLS would each of them
-- hand out exactly the powers this role is supposed not to have.
ALTER ROLE wb_catalog_ro WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

-- ── Role membership ─────────────────────────────────────────────────────────
-- A member of another role inherits that role's rights, and can `SET ROLE` to
-- it. Both are ways to be more than the role claims to be, so every membership
-- is dropped.
DO $$
DECLARE
    member record;
BEGIN
    FOR member IN
        SELECT r.rolname AS granted_role
          FROM pg_auth_members m
          JOIN pg_roles r ON r.oid = m.roleid
          JOIN pg_roles member_role ON member_role.oid = m.member
         WHERE member_role.rolname = 'wb_catalog_ro'
    LOOP
        EXECUTE format('REVOKE %I FROM wb_catalog_ro', member.granted_role);
    END LOOP;
END $$;

-- ── Ownership ───────────────────────────────────────────────────────────────
-- The owner of a table may alter it, drop it and grant anything on it. A
-- read-only role that owns something is not read-only, and the grant list below
-- would hide that.
DO $$
DECLARE
    owned text;
BEGIN
    SELECT string_agg(format('%s.%s', n.nspname, c.relname), ', ')
      INTO owned
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = 'wb_catalog_ro')
       AND n.nspname NOT IN ('pg_catalog', 'information_schema');

    IF owned IS NOT NULL THEN
        RAISE EXCEPTION
            'wb_catalog_ro owns % — an owner can do more than SELECT. Reassign the objects to their real owner and run this script again',
            owned;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = 'wb_catalog_ro')) THEN
        RAISE EXCEPTION 'wb_catalog_ro owns a schema — an owner can do more than SELECT';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba WHERE r.rolname = 'wb_catalog_ro') THEN
        RAISE EXCEPTION 'wb_catalog_ro owns a database — an owner can do more than SELECT';
    END IF;
END $$;

-- ── Everything that was granted before is taken back ────────────────────────
-- A single GRANT somewhere in the past would otherwise survive forever, and the
-- role would quietly hold more than this script grants.
REVOKE ALL PRIVILEGES ON SCHEMA public FROM wb_catalog_ro;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM wb_catalog_ro;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM wb_catalog_ro;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM wb_catalog_ro;
REVOKE ALL PRIVILEGES ON ALL ROUTINES IN SCHEMA public FROM wb_catalog_ro;

DO $$
BEGIN
    EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM wb_catalog_ro', current_database());
END $$;

-- ── The rights PUBLIC holds ─────────────────────────────────────────────────
-- PUBLIC's rights are inherited by every role, the catalog's included.
-- PostgreSQL grants TEMPORARY on a database to PUBLIC by default and grants
-- nothing on tables — so the table grants below are usually a no-op, and the
-- TEMPORARY one is not.
--
-- Revoking TEMPORARY from PUBLIC affects every role in this database. That is
-- the price of the catalog role not being able to create temporary tables: the
-- verification script requires
-- `has_database_privilege('wb_catalog_ro', db, 'TEMP')` to be false, and PUBLIC
-- is the only reason it would be true.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON public.scraped_items FROM PUBLIC;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON public.crawl_targets FROM PUBLIC;

DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END $$;

-- ── What the role may do ────────────────────────────────────────────────────

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO wb_catalog_ro', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO wb_catalog_ro;
GRANT SELECT ON public.scraped_items TO wb_catalog_ro;
GRANT SELECT ON public.crawl_targets TO wb_catalog_ro;

-- ── The password ────────────────────────────────────────────────────────────
-- The value comes from the caller's session setting. It is put into the
-- statement by `format`, which quotes it as a literal — so it is neither written
-- into this file nor able to break the statement.
DO $$
DECLARE
    password text := current_setting('wbparser.role_password', true);
BEGIN
    IF password IS NULL OR password = '' THEN
        RAISE EXCEPTION
            'the session setting wbparser.role_password is not set — the caller must set the catalog role''s password before running this script';
    END IF;

    EXECUTE format(
        'ALTER ROLE wb_catalog_ro WITH LOGIN PASSWORD %L',
        password);
END $$;
