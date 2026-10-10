package ru.wbparser.infra.pipeline

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.InvalidUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

/**
 * The filter stage must actually filter.
 *
 * `PipelineFactory` wired `filter` as `{ item -> Step.Done(item) }`, so `BusinessRules` was
 * only ever exercised by `BusinessRulesTest` and never by a running crawl: every parsed item
 * was enriched and saved, and `Side.Drop` was unreachable in production.
 *
 * Wiring the rules is a deliberate behaviour change, so each rule is pinned separately —
 * including its boundary — rather than covered by one "filtering works" case.
 *
 * Note the default [BusinessRules] already rejects an item with no price at all
 * (`Dropped.EmptyPrice`). That part of the change applies even with no configuration.
 */
class BusinessRulesFilterTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)
        val targetId = UUID.randomUUID()

        fun url(raw: String): CrawlUrl =
            CrawlUrl
                .of(raw)
                .getOrElse { reason: InvalidUrl -> throw IllegalArgumentException(reason.message) }

        fun task() = Crawling("t1", url("http://example.com/catalog"), 0, targetId)

        fun fetched(t: Crawling) = Fetched(t, CrawlHttpStatusCode(200), "", emptyMap(), 1L)

        fun item(
            productId: Long = 1L,
            priceKopecks: Long = 100_000L,
            salePriceKopecks: Long? = null,
            inStock: Boolean = true,
            category: String? = "Обувь",
        ) = ParsedItem(
            productId = ProductId(productId),
            name = "Товар $productId",
            priceKopecks = priceKopecks,
            salePriceKopecks = salePriceKopecks,
            cashbackPercent = null,
            cashbackKopecks = null,
            brand = null,
            category = category,
            imageUrl = null,
            pageUrl = url("http://example.com/p/$productId"),
            brandId = null,
            subjectId = null,
            supplierId = null,
            inStock = inStock,
        )

        /** Runs the production factory over a single parsed item and returns what happened. */
        suspend fun run(
            parsed: ParsedItem,
            rules: BusinessRules,
        ): Pair<Int, List<Side>> {
            val saved = mutableListOf<SavedItem>()
            val pipeline =
                buildParserPipeline(
                    downloader = { t -> Either.Right(fetched(t)) },
                    parser = { f -> ParsedPage(f, listOf(parsed), null, false) },
                    save = { items ->
                        saved += items
                        Step.Done(Unit)
                    },
                    targetId = targetId,
                    rules = rules,
                    retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMs = 1, maxDelayMs = 2),
                    clock = clock,
                )
            val (_, sides) =
                pipeline.run(listOf(task())).getOrElse { throw AssertionError("Expected Right") }
            return saved.size to sides
        }

        test("an in-range item passes and is saved") {
            runTest {
                val (saved, sides) = run(item(), BusinessRules(minPriceKopecks = 50_000L))

                saved shouldBe 1
                sides.none { it is Side.Drop } shouldBe true
            }
        }

        test("a price below the minimum is dropped, not saved") {
            runTest {
                val (saved, sides) = run(item(priceKopecks = 10L), BusinessRules(minPriceKopecks = 50_000L))

                saved shouldBe 0
                sides.filterIsInstance<Side.Drop>().size shouldBe 1
            }
        }

        test("the drop reason names the rule that fired") {
            runTest {
                val (_, sides) = run(item(priceKopecks = 10L), BusinessRules(minPriceKopecks = 50_000L))

                val drop = sides.filterIsInstance<Side.Drop>().single()
                drop.reason.shouldBeInstanceOf<Dropped.PriceOutOfRange>()
            }
        }

        test("a price exactly at the minimum is kept") {
            runTest {
                // The rule is `priceKopecks < min`, so the boundary itself is in range.
                val (saved, _) = run(item(priceKopecks = 50_000L), BusinessRules(minPriceKopecks = 50_000L))

                saved shouldBe 1
            }
        }

        test("a price exactly at the maximum is kept") {
            runTest {
                // The rule is `priceKopecks > max`, so the boundary itself is in range.
                val (saved, _) = run(item(priceKopecks = 200_000L), BusinessRules(maxPriceKopecks = 200_000L))

                saved shouldBe 1
            }
        }

        test("a price above the maximum is dropped") {
            runTest {
                val (saved, _) = run(item(priceKopecks = 200_001L), BusinessRules(maxPriceKopecks = 200_000L))

                saved shouldBe 0
            }
        }

        test("an out-of-stock item is dropped only when requireInStock is set") {
            runTest {
                val permissive = run(item(inStock = false), BusinessRules(requireInStock = false))
                permissive.first shouldBe 1

                val strict = run(item(inStock = false), BusinessRules(requireInStock = true))
                strict.first shouldBe 0
                strict.second
                    .filterIsInstance<Side.Drop>()
                    .single()
                    .reason
                    .shouldBeInstanceOf<Dropped.OutOfStock>()
            }
        }

        test("a blacklisted category is dropped") {
            runTest {
                val (saved, sides) =
                    run(
                        item(category = "Обувь"),
                        BusinessRules(blacklistedCategories = setOf("Обувь")),
                    )

                saved shouldBe 0
                sides
                    .filterIsInstance<Side.Drop>()
                    .single()
                    .reason
                    .shouldBeInstanceOf<Dropped.CategoryBlacklisted>()
            }
        }

        test("a category outside the blacklist passes") {
            runTest {
                val (saved, _) =
                    run(item(category = "Книги"), BusinessRules(blacklistedCategories = setOf("Обувь")))

                saved shouldBe 1
            }
        }

        test("the default rules still reject an item with no price") {
            runTest {
                // This is the part of the behaviour change nobody can opt out of by accident,
                // so it is pinned explicitly.
                val (saved, sides) = run(item(priceKopecks = 0L, salePriceKopecks = null), BusinessRules())

                saved shouldBe 0
                sides
                    .filterIsInstance<Side.Drop>()
                    .single()
                    .reason
                    .shouldBeInstanceOf<Dropped.EmptyPrice>()
            }
        }

        test("an item with a sale price but no base price is not treated as empty-priced") {
            runTest {
                val (saved, _) =
                    run(item(priceKopecks = 0L, salePriceKopecks = 5_000L), BusinessRules())

                saved shouldBe 1
            }
        }

        test("a dropped item produces exactly one Drop, not one plus a generic duplicate") {
            runTest {
                val (_, sides) = run(item(priceKopecks = 10L), BusinessRules(minPriceKopecks = 50_000L))

                sides.filterIsInstance<Side.Drop>().size shouldBe 1
            }
        }

        test("dropped items are counted separately from saved ones") {
            runTest {
                val pipeline =
                    buildParserPipeline(
                        downloader = { t -> Either.Right(fetched(t)) },
                        parser = { f ->
                            ParsedPage(
                                f,
                                listOf(
                                    item(productId = 1L, priceKopecks = 100_000L),
                                    item(productId = 2L, priceKopecks = 10L),
                                ),
                                null,
                                false,
                            )
                        },
                        save = { Step.Done(Unit) },
                        targetId = targetId,
                        rules = BusinessRules(minPriceKopecks = 50_000L),
                        retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMs = 1, maxDelayMs = 2),
                        clock = clock,
                    )

                val (crawled, sides) =
                    pipeline.run(listOf(task())).getOrElse { throw AssertionError("Expected Right") }

                crawled.pagesCrawled shouldBe 1
                crawled.itemsSaved shouldBe 1
                sides.filterIsInstance<Side.Drop>().size shouldBe 1
            }
        }
    })
