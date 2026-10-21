package ru.wbparser.app.config

/**
 * The data the seed applies.
 *
 * The synthetic rows live in a file, not in the code: `db/seed/synthetic-items.sql`
 * is the single copy of them, and the tests read the same file — so what the seed
 * writes and what the tests count are the same rows.
 */
class DemoSeedData {
    /**
     * Apply the seed.
     *
     * The script is one file with many statements, applied through the simple query
     * protocol — the same way `psql` would apply it, and the same way Flyway applies a
     * migration.
     */
    fun apply(context: DemoSeedContext) {
        context.connection.createStatement().use { statement ->
            statement.execute(DemoSeedResources.read(DemoSeedResources.SEED_RESOURCE))
        }
    }
}
