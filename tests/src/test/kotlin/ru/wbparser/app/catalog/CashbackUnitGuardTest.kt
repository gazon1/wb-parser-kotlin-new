package ru.wbparser.app.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.testing.PostgresFixture
import java.sql.SQLException
import java.util.UUID

/**
 * The database refuses a write that does not declare the unit it writes in.
 *
 * ## Why this suite exists
 *
 * `V17` changed what the cashback columns mean: `cashback_percent` holds the percentage
 * WB sends, and `cashback` holds the money that follows it. A writer that does not know
 * that writes the old unit into the new columns, and nothing about the failure is loud:
 * the rows are written, the crawl succeeds, and the catalog answers with cashbacks that
 * are wrong by a factor of a hundred.
 *
 * The runbook says the old crawler must not run after `V17` — but a rule that lives in a
 * document is a rule an operator has to remember. `V19` makes the database refuse the
 * write instead, and this suite is what proves it does.
 *
 * ## What each test asserts
 *
 * The first test is the guard: a session that says nothing is refused, and the error
 * names the declaration. The second is the writer: the production write path declares
 * the unit by itself, so it writes on a session that says nothing — the session of the
 * release that writes the old unit, which has no such line and therefore cannot write.
 *
 * Both run on a fixture whose connections declare nothing
 * (`PostgresFixture.start(container, declaresTheUnit = false)`), because a suite that
 * proved the guard on a session that already declares would prove nothing.
 */
class CashbackUnitGuardTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        // The fixture, on connections that declare nothing.
        val postgres = PostgresFixture()

        beforeSpec {
            container.start()
            postgres.start(container, declaresTheUnit = false)
        }
        afterSpec { container.stop() }

        /**
         * A crawl target to write into.
         */
        fun seedTarget(): UUID = postgres.seedTarget("guard")

        /**
         * The rows in the table.
         */
        fun rowCount(): Int = (postgres.rows("SELECT COUNT(*) AS count FROM scraped_items").first()["count"] as Number).toInt()

        test("a write that does not declare the unit is refused") {
            val targetId = seedTarget()

            val failure =
                shouldThrow<SQLException> {
                    postgres.ds.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(
                                "INSERT INTO scraped_items (id, target_id, product_url, content_hash, title) " +
                                    "VALUES ('00000000-0000-0000-0000-000000000001', '$targetId', " +
                                    "'https://example.invalid/product/1', 'hash-1', 'Товар')",
                            )
                        }
                    }
                }

            failure.message shouldContain "crawler.cashback_unit"
            rowCount() shouldBe 0
        }

        test("the writer declares the unit it writes") {
            val targetId = seedTarget()

            // The production write path, on a session that declares nothing itself.
            postgres.ds.upsertSavedItems(listOf(postgres.item(targetId, productId = 1L)), targetId)

            rowCount() shouldBe 1
        }
    })
