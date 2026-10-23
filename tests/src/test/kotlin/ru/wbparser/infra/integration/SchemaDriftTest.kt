package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.CrawlEgressGroupsTable
import ru.wbparser.infra.db.CrawlTargetRunsTable
import ru.wbparser.infra.db.CrawlTargetsTable
import ru.wbparser.infra.db.ScrapedItemsTable
import ru.wbparser.testing.PostgresFixture

/**
 * The schema the migrations create and the schema the code believes in must be
 * the same schema.
 *
 * The table definitions in `ExposedTables.kt` are the only description of the
 * database the application has. The migrations are the database. Nothing
 * compared them: a column renamed in a migration, a type widened, a column made
 * nullable — the write path keeps compiling, because the model did not change,
 * and fails at runtime, on the column it names.
 *
 * ## How the comparison is made
 *
 * Not by writing the expected schema a second time by hand — that would be a
 * third description to keep in sync, and it would encode the same mistake. The
 * model itself creates the schema: Exposed is asked to create the tables in a
 * scratch schema, and the result is compared with the schema the migrations
 * produced. Both sides are then PostgreSQL's own types, so the comparison needs
 * no mapping table and cannot hide a difference behind one.
 *
 * The migrated schema is the contract: it is what production runs. When the two
 * disagree, the model is wrong and is corrected to match.
 */
class SchemaDriftTest :
    FunSpec({
        val container = PostgreSQLContainer("postgres:16-alpine")
        val db = PostgresFixture()

        /** The schema Exposed creates from the table definitions. */
        val modelSchema = "code_model"

        val modelled: List<Table> =
            listOf(ScrapedItemsTable, CrawlTargetsTable, CrawlEgressGroupsTable, CrawlTargetRunsTable)

        beforeSpec {
            container.start()
            db.start(container)
        }

        afterSpec {
            container.stop()
        }

        test("the migrated schema matches the Exposed table definitions") {
            // Let the model build its own schema, next to the migrated one.
            transaction(Database.connect(db.ds)) {
                exec("CREATE SCHEMA IF NOT EXISTS $modelSchema")
                exec("SET search_path TO $modelSchema")
                SchemaUtils.create(*modelled.toTypedArray())
            }

            val migrated = db.columnsOf("public")
            val model = db.columnsOf(modelSchema)

            val differences = mutableListOf<String>()
            for (table in modelled) {
                val name = table.tableName
                val fromMigrations = migrated[name]
                val fromModel = model[name]
                if (fromMigrations == null) {
                    differences += "$name is in the table definitions but not in the migrated schema"
                    continue
                }
                if (fromModel == null) {
                    differences += "$name is in the migrated schema but not in the table definitions"
                    continue
                }
                for ((column, definition) in fromMigrations) {
                    val declared = fromModel[column]
                    when {
                        declared == null ->
                            differences += "$name.$column is in the migrated schema but not in the table definitions"
                        declared != definition ->
                            differences +=
                                "$name.$column is $definition in the migrated schema and $declared in the table definitions"
                    }
                }
                for (column in fromModel.keys - fromMigrations.keys) {
                    differences += "$name.$column is in the table definitions but not in the migrated schema"
                }
            }

            differences shouldBe emptyList()
        }

        test("every column the model declares exists in the migrated schema") {
            // A narrower statement of the same contract, kept separate so that a
            // failure says which of the two it is: a column that does not exist at
            // all is a different kind of problem from a column of the wrong type.
            val migrated = db.columnsOf("public")
            for (table in modelled) {
                val columns = migrated[table.tableName].orEmpty().keys
                table.columns.map { it.name }.toSet() shouldContainExactly columns
            }
        }
    })

/**
 * The columns of every table in [schema]: name → (type, nullable).
 *
 * `format_type` is PostgreSQL's own rendering of a type, so both sides of the
 * comparison are the database's words — `character varying(255)` on one side and
 * `text` on the other are visibly different, with no mapping table in between.
 */
private fun PostgresFixture.columnsOf(schema: String): Map<String, Map<String, ColumnDefinition>> {
    val sql =
        """
        SELECT c.relname AS table_name,
               a.attname AS column_name,
               pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
               NOT a.attnotnull AS nullable
        FROM pg_catalog.pg_attribute a
        JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ?
          AND a.attnum > 0
          AND NOT a.attisdropped
          AND c.relkind = 'r'
        ORDER BY c.relname, a.attname
        """.trimIndent()

    val out = mutableMapOf<String, MutableMap<String, ColumnDefinition>>()
    ds.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, schema)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val table = rows.getString("table_name")
                    val column = rows.getString("column_name")
                    val type = rows.getString("data_type")
                    val nullable = rows.getBoolean("nullable")
                    out.getOrPut(table) { mutableMapOf() }[column] = ColumnDefinition(type, nullable)
                }
            }
        }
    }
    return out
}

/** A column as PostgreSQL describes it: its type, and whether it accepts null. */
private data class ColumnDefinition(
    val type: String,
    val nullable: Boolean,
) {
    override fun toString(): String = if (nullable) "$type NULL" else "$type NOT NULL"
}
