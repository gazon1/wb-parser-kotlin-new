package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.testing.Migrations
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * The migration that replaces the read model's index.
 *
 * `V18` builds the index again — the included columns of an index cannot be changed in
 * place — under a second name, and renames it. On a live database the build happens
 * before the migration, concurrently, by the runbook; a build that fails leaves an
 * index that exists and is **not valid**, and the planner ignores such an index.
 *
 * The migration therefore has to refuse to rename it: `CREATE INDEX IF NOT EXISTS` sees
 * only the name, so a migration that looked at the name would drop the index that works
 * and rename the one that does not — and the read model would visit the heap for every
 * observation, with nothing to show for the minutes the build took.
 *
 * The invalid index here is made the way production makes it: a concurrent build that
 * cannot finish leaves one behind. It is not written into `pg_index`, which PostgreSQL
 * refuses to let anyone modify.
 */
class ReadModelIndexMigrationTest :
    FunSpec({
        test("the migration refuses to rename an index that is not valid") {
            withDatabase { dataSource ->
                applyMigrations(dataSource, until = "V17__scraped_items_cashback_is_a_percentage.sql")

                // Two observations of the same price: a unique index over them cannot
                // be built, and a concurrent build that fails leaves the index behind.
                val targetId = seedTarget(dataSource)
                seedItem(dataSource, targetId, priceKopecks = 259000L)
                seedItem(dataSource, targetId, priceKopecks = 259000L)

                val build =
                    runCatching {
                        execute(
                            dataSource,
                            "CREATE UNIQUE INDEX CONCURRENTLY ix_scraped_items_product_latest_covering_v2 " +
                                "ON scraped_items (price_kopecks);",
                        )
                    }.exceptionOrNull()
                build.shouldBeInstanceOf<SQLException>()
                isValid(dataSource, "ix_scraped_items_product_latest_covering_v2") shouldBe false

                val refusal =
                    runCatching {
                        applyMigration(dataSource, "V18__catalog_read_model_index_carries_the_review_points.sql")
                    }.exceptionOrNull()
                val error = refusal.shouldBeInstanceOf<SQLException>()
                error.message.orEmpty().shouldContain("is not valid")

                // The index that works is still there: the refusal rolled the whole
                // migration back, the old index included.
                exists(dataSource, "ix_scraped_items_product_latest_covering") shouldBe true
                exists(dataSource, "ix_scraped_items_product_latest_covering_v2") shouldBe true
            }
        }

        test("the migration renames the index it finds and drops the old one") {
            withDatabase { dataSource ->
                applyMigrations(dataSource, until = "V17__scraped_items_cashback_is_a_percentage.sql")
                applyMigration(dataSource, "V17__scraped_items_cashback_is_a_percentage.sql")

                applyMigration(dataSource, "V18__catalog_read_model_index_carries_the_review_points.sql")

                exists(dataSource, "ix_scraped_items_product_latest_covering") shouldBe true
                exists(dataSource, "ix_scraped_items_product_latest_covering_v2") shouldBe false
                // The index is covering only when it carries the column the model
                // reads — that is the whole reason this migration exists.
                indexColumns(dataSource, "ix_scraped_items_product_latest_covering").contains("feedback_points") shouldBe true
            }
        }
    })

/** A database with the crawler's own migrations, applied up to the one named. */
private fun withDatabase(block: (DataSource) -> Unit) {
    val container =
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("wbparser")
            .withUsername("postgres")
            .withPassword("postgres")
    container.start()
    try {
        block(
            PGSimpleDataSource().apply {
                setUrl(container.jdbcUrl)
                user = container.username
                password = container.password
            },
        )
    } finally {
        container.stop()
    }
}

/** The schema as it was before the migration that changed the index. */
private fun applyMigrations(
    dataSource: DataSource,
    until: String,
) {
    val migrations = Migrations.discover()
    val last = migrations.indexOf(until)
    check(last >= 0) { "migration '$until' is not on the classpath" }
    migrations.take(last).forEach { applyMigration(dataSource, it) }
}

private fun applyMigration(
    dataSource: DataSource,
    name: String,
) {
    execute(dataSource, Migrations.read(name))
}

private fun execute(
    dataSource: DataSource,
    sql: String,
) {
    dataSource.connection.use { conn ->
        conn.createStatement().use { it.execute(sql) }
    }
}

private fun seedTarget(dataSource: DataSource): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                           root_category_name, created_at, updated_at)
                VALUES (?, 'Категория', 'https://example.invalid/category', 1, true, 'Категория', now(), now())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
    }
    return id
}

private fun seedItem(
    dataSource: DataSource,
    targetId: UUID,
    priceKopecks: Long,
) {
    dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO scraped_items (target_id, product_url, price_kopecks, content_hash)
                VALUES (?, 'https://example.invalid/product/1', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, targetId)
                ps.setLong(2, priceKopecks)
                ps.setString(3, "hash-$priceKopecks-${UUID.randomUUID()}")
                ps.executeUpdate()
            }
    }
}

/** Whether an index of that name exists in the schema. */
private fun exists(
    dataSource: DataSource,
    indexName: String,
): Boolean =
    dataSource.connection.use { conn ->
        conn
            .prepareStatement("SELECT to_regclass('public.' || ?) IS NOT NULL")
            .use { ps ->
                ps.setString(1, indexName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
    }

/** Whether the planner may use the index of that name. */
private fun isValid(
    dataSource: DataSource,
    indexName: String,
): Boolean =
    dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                SELECT i.indisvalid
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relname = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, indexName)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no index named '$indexName'" }
                    rs.getBoolean(1)
                }
            }
    }

/** The columns the index of that name carries — the key ones and the included ones. */
private fun indexColumns(
    dataSource: DataSource,
    indexName: String,
): List<String> =
    dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                SELECT a.attname
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = ?
                   AND a.attnum > 0
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, indexName)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("attname"))
                        }
                    }
                }
            }
    }
