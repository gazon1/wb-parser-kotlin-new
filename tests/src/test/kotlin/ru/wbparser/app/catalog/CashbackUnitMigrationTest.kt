package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.testing.Migrations
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.sql.DataSource

/**
 * The migration that changed the meaning of the cashback columns.
 *
 * `sizes[0].price.cashback` is a percentage, not an amount of money. The old writer
 * took that percentage for kopecks and wrote it into `cashback` as rubles, so a
 * product with a 3 % cashback was stored as 0.03 rubles; it derived
 * `cashback_percent` from that amount and the price, so the stored percentage was a
 * number that depended on the price rather than on the cashback (0.00 % for a
 * 2590 ₽ product — the column holds two decimals).
 *
 * The percentage is a measured fact, not an assumption: the live catalog answered
 * `cashback` 1..3 for products priced 850..10000 ₽, and a kopeck amount on those
 * prices would be 0.01..0.03 ₽ — a number no storefront shows. The measurement is
 * committed as evidence in
 * `devtools/wb-research/investigations/2026-11-19-feedback-points-filter/evidence/feedback-points-filter.json`.
 *
 * The test applies the migrations by hand, so the row can be written in the *old*
 * meaning and read after the migration that changes it. A test that only seeded
 * rows under the new meaning would prove nothing: the rows would already be right.
 * The seeded values are computed with the old writer's own formulas, from the
 * percentage WB sent — so they are what that writer produced, not a guess.
 */
class CashbackUnitMigrationTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        afterSpec { container.stop() }

        test("a cashback stored in the old unit becomes a percentage and the money that follows it") {
            container.start()
            val dataSource = dataSource(container)
            applyMigrations(dataSource, until = "V17__scraped_items_cashback_is_a_percentage.sql")

            val targetId = seedTarget(dataSource)
            // The old row: WB sent the percentage "3", the old mapping read it as
            // kopecks and the old persistence wrote it as 0.03 rubles, and the
            // percentage was derived from that amount and the price — 3 % of
            // 2590 ₽, which the old column kept as 0.00.
            val plain = legacyRow(apiCashback = 3, priceKopecks = 259000L)
            seedItem(
                dataSource,
                targetId,
                "with-cashback",
                cashback = plain.rubles,
                cashbackPercent = plain.percent,
                price = 259000L,
                salePrice = 259000L,
            )
            // The same observation with a discounted price: the money follows the
            // price that was paid.
            seedItem(
                dataSource,
                targetId,
                "with-discount",
                cashback = plain.rubles,
                cashbackPercent = plain.percent,
                price = 259000L,
                salePrice = 90000L,
            )
            // The same percentage on a product of another price: the percentage is
            // what WB sends, and it does not depend on the price.
            val large = legacyRow(apiCashback = 3, priceKopecks = 1000000L)
            seedItem(
                dataSource,
                targetId,
                "with-large-price",
                cashback = large.rubles,
                cashbackPercent = large.percent,
                price = 1000000L,
                salePrice = 373500L,
            )
            // A price that gives a fraction of a kopeck: 1 % of 1.50 ₽ is
            // 0.015 ₽. The writer of the new rows truncates to whole kopecks
            // (0.01 ₽), so the migration has to truncate too — otherwise a
            // migrated row and a scraped row of the same product differ by a
            // cent.
            val fraction = legacyRow(apiCashback = 1, priceKopecks = 150L)
            seedItem(
                dataSource,
                targetId,
                "with-fraction-of-a-kopeck",
                cashback = fraction.rubles,
                cashbackPercent = fraction.percent,
                price = 150L,
                salePrice = 150L,
            )
            seedItem(
                dataSource,
                targetId,
                "without-cashback",
                cashback = null,
                cashbackPercent = null,
                price = 259000L,
                salePrice = 259000L,
            )
            // An observation the old writer stored without a price: the amount is
            // there, the price that would give the money amount is not — and the old
            // percentage was empty, because it was derived from the price.
            val withoutPrice = legacyRow(apiCashback = 3, priceKopecks = 0L)
            seedItem(
                dataSource,
                targetId,
                "without-price",
                cashback = withoutPrice.rubles,
                cashbackPercent = withoutPrice.percent,
                price = 0L,
                salePrice = null,
            )

            applyMigration(dataSource, "V17__scraped_items_cashback_is_a_percentage.sql")

            // 3 % of 2590 ₽ is 77.70 ₽.
            val converted = row(dataSource, "with-cashback")
            converted["cashback_percent"] shouldBe BigDecimal("3.00")
            converted["cashback"] shouldBe BigDecimal("77.70")

            // 3 % of 900 ₽ is 27 ₽.
            val discounted = row(dataSource, "with-discount")
            discounted["cashback_percent"] shouldBe BigDecimal("3.00")
            discounted["cashback"] shouldBe BigDecimal("27.00")

            // 3 % of 3735 ₽ is 112.05 ₽ — the percentage does not depend on the
            // price, the money does.
            val largePrice = row(dataSource, "with-large-price")
            largePrice["cashback_percent"] shouldBe BigDecimal("3.00")
            largePrice["cashback"] shouldBe BigDecimal("112.05")

            // The percentage is restored even where the price is missing; the
            // money amount is not there to restore.
            val noPrice = row(dataSource, "without-price")
            noPrice["cashback_percent"] shouldBe BigDecimal("3.00")
            noPrice["cashback"].shouldBeNull()

            // 1 % of 1.50 ₽ is 0.015 ₽ — truncated to 0.01 ₽, the way the writer
            // of the new rows truncates it.
            val fractional = row(dataSource, "with-fraction-of-a-kopeck")
            fractional["cashback_percent"] shouldBe BigDecimal("1.00")
            fractional["cashback"] shouldBe BigDecimal("0.01")

            val untouched = row(dataSource, "without-cashback")
            untouched["cashback_percent"].shouldBeNull()
            untouched["cashback"].shouldBeNull()
        }
    })

/**
 * The row the old writer left behind for a product WB answered with [apiCashback]
 * — the percentage it sent — at [priceKopecks].
 *
 * The old mapping read the percentage as kopecks, the old persistence wrote those
 * kopecks as rubles, and the old percentage was derived from the amount and the
 * price: it was empty when there was no price to divide by.
 */
private fun legacyRow(
    apiCashback: Int,
    priceKopecks: Long,
): LegacyRow {
    val kopecks = apiCashback.toLong()
    val rubles = BigDecimal.valueOf(kopecks).movePointLeft(2)
    val percent =
        if (kopecks > 0 && priceKopecks > 0) {
            BigDecimal
                .valueOf(kopecks)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(priceKopecks), 2, RoundingMode.HALF_UP)
        } else {
            null
        }
    return LegacyRow(rubles, percent)
}

private data class LegacyRow(
    val rubles: BigDecimal,
    val percent: BigDecimal?,
)

private fun dataSource(container: PostgreSQLContainer<*>): DataSource =
    PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        user = container.username
        password = container.password
    }

/** The schema as it was before the migration that changed the meaning of the columns. */
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
    dataSource.connection.use { conn ->
        conn.createStatement().use { it.execute(Migrations.read(name)) }
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
    hash: String,
    cashback: BigDecimal?,
    cashbackPercent: BigDecimal?,
    price: Long,
    salePrice: Long?,
) {
    dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO scraped_items (target_id, product_url, product_id, price_kopecks,
                                          sale_price_kopecks, cashback, cashback_percent, content_hash)
                VALUES (?, 'https://example.invalid/product/1', 1, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, targetId)
                ps.setLong(2, price)
                ps.setObject(3, salePrice)
                ps.setBigDecimal(4, cashback)
                ps.setBigDecimal(5, cashbackPercent)
                ps.setString(6, hash)
                ps.executeUpdate()
            }
    }
}

private fun row(
    dataSource: DataSource,
    hash: String,
): Map<String, Any?> =
    dataSource.connection.use { conn ->
        conn
            .prepareStatement("SELECT cashback, cashback_percent FROM scraped_items WHERE content_hash = ?")
            .use { ps ->
                ps.setString(1, hash)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no row with content_hash '$hash'" }
                    mapOf(
                        "cashback" to rs.getObject("cashback"),
                        "cashback_percent" to rs.getObject("cashback_percent"),
                    )
                }
            }
    }
