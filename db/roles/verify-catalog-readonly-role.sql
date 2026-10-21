-- ==============================================================================
-- verify-catalog-readonly-role.sql
--
-- Checks the *effective* rights of the catalog role and fails when any of them is wrong.
--
--   psql -v ON_ERROR_STOP=1 -f db/roles/verify-catalog-readonly-role.sql "$DATABASE_URL"
--
-- ## Why this is a separate script
--
-- A script that both applies and checks its own work cannot tell "I did what I meant" from
-- "I did what I always do". This one only looks.
--
-- ## Why `information_schema.role_table_grants` is not enough
--
-- That view shows the grants that were made *to* the role, explicitly. It says nothing
-- about
--
--   - the rights the role inherits from PUBLIC,
--   - the rights the role inherits by being a member of another role,
--   - the rights the role has because it *owns* the table — an owner may ALTER and DROP
--     it, and no grant row says so,
--   - the rights the role has because it can `SET ROLE` to something else.
--
-- So the checks below ask PostgreSQL what the role can actually do, through
-- `has_table_privilege`, `has_schema_privilege`, `has_database_privilege` and the
-- catalogue tables — the same questions the server would answer when the role tried.
--
-- The runtime counterpart of this script is the `42501` test: the role's own credentials
-- are used to attempt an `INSERT`, an `UPDATE`, a `DELETE` and a `CREATE TABLE`, and each
-- has to be refused with `insufficient_privilege`. Between the two, both sides are
-- covered: "these operations are forbidden" and "there is no other way in".
-- ==============================================================================

\set ON_ERROR_STOP on

DO $$
DECLARE
    role_name text := 'wb_catalog_ro';
    catalog_tables text[] := ARRAY['scraped_items', 'crawl_targets'];
    write_privileges text[] := ARRAY[
        'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'
    ];
    problems text[] := '{}';
    member record;
    owned text;
    table_name text;
    privilege_name text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        RAISE EXCEPTION 'the role % does not exist — run db/roles/provision-catalog-readonly-role.sql first', role_name;
    END IF;

    -- ── Membership ───────────────────────────────────────────────────────────
    FOR member IN
        SELECT r.rolname AS granted_role
          FROM pg_auth_members m
          JOIN pg_roles r ON r.oid = m.roleid
          JOIN pg_roles member_role ON member_role.oid = m.member
         WHERE member_role.rolname = role_name
    LOOP
        problems := problems || format(
            '%s is a member of %s — the inherited rights of that role are a way around the read-only boundary',
            role_name, member.granted_role);
    END LOOP;

    -- ── Ownership ────────────────────────────────────────────────────────────
    SELECT string_agg(format('%s.%s', n.nspname, c.relname), ', ')
      INTO owned
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = role_name)
       AND n.nspname NOT IN ('pg_catalog', 'information_schema');

    IF owned IS NOT NULL THEN
        problems := problems || format('%s owns %s — an owner can do more than SELECT', role_name, owned);
    END IF;

    -- ── The role's own attributes ────────────────────────────────────────────
    IF EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = role_name
           AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        problems := problems || format('%s is SUPERUSER, CREATEDB, CREATEROLE, REPLICATION or BYPASSRLS', role_name);
    END IF;

    -- ── Database and schema ──────────────────────────────────────────────────
    IF NOT has_database_privilege(role_name, current_database(), 'CONNECT') THEN
        problems := problems || format('%s cannot CONNECT to the database', role_name);
    END IF;

    IF has_database_privilege(role_name, current_database(), 'CREATE') THEN
        problems := problems || format('%s may CREATE in the database', role_name);
    END IF;

    IF has_database_privilege(role_name, current_database(), 'TEMP') THEN
        problems := problems || format('%s may create TEMPORARY objects in the database', role_name);
    END IF;

    IF NOT has_schema_privilege(role_name, 'public', 'USAGE') THEN
        problems := problems || format('%s has no USAGE on the schema public', role_name);
    END IF;

    IF has_schema_privilege(role_name, 'public', 'CREATE') THEN
        problems := problems || format('%s may CREATE in the schema public', role_name);
    END IF;

    -- ── Tables ───────────────────────────────────────────────────────────────
    FOREACH table_name IN ARRAY catalog_tables
    LOOP
        IF to_regclass('public.' || table_name) IS NULL THEN
            problems := problems || format('the table public.%s does not exist', table_name);
            CONTINUE;
        END IF;

        IF NOT has_table_privilege(role_name, 'public.' || table_name, 'SELECT') THEN
            problems := problems || format('%s cannot SELECT from public.%s', role_name, table_name);
        END IF;

        FOREACH privilege_name IN ARRAY write_privileges
        LOOP
            IF has_table_privilege(role_name, 'public.' || table_name, privilege_name) THEN
                problems := problems || format(
                    '%s holds %s on public.%s — that is a write path',
                    role_name, privilege_name, table_name);
            END IF;

            -- The rights PUBLIC holds are inherited by the role, so a table PUBLIC may
            -- write to is a table the catalog role may write to as well.
            IF has_table_privilege('public', 'public.' || table_name, privilege_name) THEN
                problems := problems || format(
                    'PUBLIC holds %s on public.%s — the catalog role inherits it',
                    privilege_name, table_name);
            END IF;
        END LOOP;
    END LOOP;

    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION E'\nthe catalog role is not read-only:\n  - %', array_to_string(problems, E'\n  - ');
    END IF;
END $$;

\echo 'wb_catalog_ro holds CONNECT, USAGE and SELECT, and nothing else.'
