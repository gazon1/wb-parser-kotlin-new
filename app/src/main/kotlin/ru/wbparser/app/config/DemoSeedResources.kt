package ru.wbparser.app.config

/**
 * The classpath resources the demo seed needs.
 *
 * The seed's script and the catalog role's rights are files, not strings: the
 * rights live in `db/roles/catalog-readonly-role.sql`, which is the same file an
 * operator runs against a working database, and the seed data lives in
 * `db/seed/synthetic-items.sql`. Writing either of them into the code would
 * create a second copy of the same thing — and the copy that nobody remembers
 * is the one that grants too much.
 */
object DemoSeedResources {
    /** Where the seed script lives on the classpath. */
    const val SEED_RESOURCE: String = "/db/seed/synthetic-items.sql"

    /** Where the catalog role's rights live on the classpath. */
    const val CATALOG_ROLE_SCRIPT: String = "/db/roles/catalog-readonly-role.sql"

    /**
     * A resource from the classpath, as text.
     */
    fun read(path: String): String =
        checkNotNull(DemoSeedResources::class.java.getResourceAsStream(path)) {
            "the resource '$path' is not on the classpath"
        }.bufferedReader().use { it.readText() }
}
