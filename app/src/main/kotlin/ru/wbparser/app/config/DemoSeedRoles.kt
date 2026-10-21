package ru.wbparser.app.config

import java.sql.Connection

/**
 * The rights the demo environment needs: the seed's own role, and the catalog's.
 *
 * ## Why this is one decision
 *
 * The seed writes into `scraped_items` and `crawl_targets`, and it does so
 * through a role of its own — not through the writer's, and not through the
 * catalog's read-only role. Creating that role, giving it the right password and
 * giving it the right privileges is one decision, because the three steps fail
 * together: a role without a password cannot connect, and a role without
 * privileges cannot write.
 *
 * ## Why the password goes through `set_config`
 *
 * PostgreSQL's `CREATE ROLE` and `ALTER ROLE` do not accept bind parameters. The
 * password is therefore passed as a session setting and read back by the
 * statement, instead of being written into the statement's text — where it would
 * end up in the server log.
 */
class DemoSeedRoles(
    /** The password the catalog role gets — it is the catalog's own. */
    private val catalogPassword: String,
) {
    /**
     * Create what the demo environment needs: the seed role and its rights.
     *
     * This runs on the writer's connection, which in the demo environment is a
     * superuser. It is the only part of the demo path that needs those rights, and
     * it runs only after the emptiness check has passed.
     */
    fun bootstrap(context: DemoSeedContext) {
        ensureRole(context.connection, context.role, context.settings.dbPassword)
        provisionCatalogRole(context.connection)
        grantSeedRole(context.connection, context.role)
    }

    /**
     * Give the catalog role its rights, by running the repository's own script.
     *
     * The rights are not written out here: `db/roles/catalog-readonly-role.sql` is
     * the single copy of them, and this is the same file an operator runs against a
     * working database. Two copies would be two things to forget to change — and
     * the copy that nobody remembers is the one that grants too much.
     */
    private fun provisionCatalogRole(connection: Connection) {
        connection.prepareStatement("SELECT set_config('wbparser.role_password', ?, false)").use { statement ->
            statement.setString(1, catalogPassword)
            statement.execute()
        }
        connection.createStatement().use { statement ->
            statement.execute(DemoSeedResources.read(DemoSeedResources.CATALOG_ROLE_SCRIPT))
        }
    }

    /**
     * Give the seeding role what it needs, and nothing more.
     *
     * It reads the tables it is about to check, and it writes the rows it is about to
     * seed. It gets no rights on anything else — in particular no DELETE, because a
     * seed that can delete would be a seed that can empty a database.
     */
    private fun grantSeedRole(
        connection: Connection,
        role: String,
    ) {
        require(SAFE_IDENTIFIER.matches(role)) {
            "'$role' is not a valid role name — the demo seed refuses to build a statement out of it"
        }
        connection.createStatement().use { statement ->
            statement.execute("GRANT USAGE ON SCHEMA public TO $role")
            statement.execute("GRANT SELECT, INSERT ON public.scraped_items TO $role")
            statement.execute("GRANT SELECT, INSERT ON public.crawl_targets TO $role")
        }
    }

    /**
     * Create a role if it does not exist, and give it the password it should have.
     *
     * The password is passed through `set_config` rather than written into the statement,
     * because PostgreSQL's `CREATE ROLE` and `ALTER ROLE` do not accept bind parameters.
     * The statement reads it back with `current_setting`.
     */
    private fun ensureRole(
        connection: Connection,
        role: String,
        password: String,
    ) {
        require(SAFE_IDENTIFIER.matches(role)) {
            "'$role' is not a valid role name — the demo seed refuses to build a statement out of it"
        }
        connection.prepareStatement("SELECT set_config('wbparser.role_password', ?, false)").use { statement ->
            statement.setString(1, password)
            statement.execute()
        }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') THEN
                        CREATE ROLE $role;
                    END IF;
                    EXECUTE format(
                        'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
                        '$role', current_setting('wbparser.role_password'));
                END $$
                """.trimIndent(),
            )
        }
    }

    companion object {
        /** The role the catalog reads through. */
        const val CATALOG_ROLE: String = "wb_catalog_ro"

        /** Role names are identifiers, and an identifier is a closed set of characters. */
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
    }
}
