package ru.wbparser.infra.integration

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.EgressAttribution
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.infra.runner.CrawlRunnerConfig
import ru.wbparser.infra.runner.CrawlSource
import ru.wbparser.testing.PostgresFixture
import java.util.UUID

/**
 * What a crawl leaves behind in `crawl_targets`.
 *
 * The state used to live in the crawler's memory, which died with the process: after a
 * restart every target looked unread, and a target that had *failed* looked read as
 * well — it was marked crawled along with the ones that worked, and stayed out of the
 * schedule for a whole freshness window.
 *
 * The two failures are now told apart as well. A refusal is the address's fault and
 * gets a short pause; an answer that is not the catalog is the catalog's fault and
 * gets a long one. Mixing them up put healthy catalogs to sleep for a day because one
 * IP had run into a limit.
 */
class CrawlTargetStateTest :
    FunSpec({

        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        val db = PostgresFixture()

        beforeSpec {
            container.start()
            db.start(container)
        }

        beforeTest { db.clear() }

        afterSpec { container.stop() }

        fun handle() = DatabaseHandle(db.ds, Database.connect(db.ds))

        val emptyParser: (Fetched) -> ParsedPage = { fetched ->
            ParsedPage(fetched, emptyList(), null, false)
        }

        /**
         * A parser that finds one product, so the crawl has something to store.
         */
        val parserWithOneItem: (Fetched) -> ParsedPage = { fetched ->
            ParsedPage(
                input = fetched,
                items =
                    listOf(
                        ru.wbparser.domain.model.ParsedItem(
                            productId =
                                ru.wbparser.domain.value
                                    .ProductId(1L),
                            name = "Товар",
                            priceKopecks = 1000L,
                            salePriceKopecks = null,
                            cashbackPercent = null,
                            cashbackKopecks = null,
                            brand = null,
                            category = null,
                            imageUrl = null,
                            pageUrl = fetched.task.url,
                            brandId = null,
                            subjectId = null,
                            supplierId = null,
                            inStock = true,
                        ),
                    ),
                nextPageUrl = null,
                isEmptyPage = false,
            )
        }

        /**
         * A downloader that answers with a body and no products.
         */
        val emptyButSuccessful: suspend (Crawling) -> Either<NetworkError, Fetched> = { task ->
            Either.Right(
                Fetched(
                    task = task,
                    statusCode = CrawlHttpStatusCode(200),
                    body = "<html></html>",
                    headers = emptyMap(),
                    durationMs = 1L,
                ),
            )
        }

        /**
         * A downloader that is refused.
         */
        val refused: suspend (Crawling) -> Either<NetworkError, Fetched> = { task ->
            Either.Left(NetworkError("HTTP 498", null, task.url.toString(), isAntibot = true))
        }

        /**
         * An egress side that says the address is fine.
         */
        val healthyEgress =
            object : EgressAttribution {
                override fun lastGroupFor(targetId: UUID): String? = "ip-1"

                override fun isGroupHealthy(groupId: String): Boolean = true
            }

        /**
         * An egress side that says the address is refusing everything.
         */
        val sickEgress =
            object : EgressAttribution {
                override fun lastGroupFor(targetId: UUID): String? = "ip-1"

                override fun isGroupHealthy(groupId: String): Boolean = false
            }

        fun runnerWith(
            downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
            egress: EgressAttribution? = null,
            parser: (Fetched) -> ParsedPage = emptyParser,
        ) = CrawlRunner(
            db = handle(),
            source = CrawlSource(downloader = downloader, parser = parser, egress = egress),
            config =
                CrawlRunnerConfig(
                    targetConcurrency = 1,
                    // One attempt: the retry back-off is real time, and this test is
                    // about what the crawl records, not about how often it retries.
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                ),
        )

        fun stateOf(targetId: UUID): Map<String, Any?> =
            db.row(
                """
                SELECT last_crawled_at, consecutive_failures, egress_failure_count,
                       next_attempt_at, anomaly_group
                  FROM crawl_targets
                 WHERE id = '$targetId'
                """.trimIndent(),
            )

        test("a target that was read is marked as read") {
            runTest {
                val id = db.seedTarget("Прочитанная")

                runnerWith(emptyButSuccessful, parser = parserWithOneItem).run()

                val state = stateOf(id)
                (state["last_crawled_at"] != null) shouldBe true
                state["consecutive_failures"] shouldBe 0
                state["egress_failure_count"] shouldBe 0
                (state["next_attempt_at"] == null) shouldBe true
            }
        }

        test("a target that was refused is not marked as read, and the address is not blamed") {
            runTest {
                val id = db.seedTarget("Отказанная")

                runnerWith(refused, healthyEgress).run()

                val state = stateOf(id)
                (state["last_crawled_at"] == null) shouldBe true
                // The address failed, not the catalog: the target's own counter stays at zero.
                state["consecutive_failures"] shouldBe 0
                state["egress_failure_count"] shouldBe 1
                (state["next_attempt_at"] != null) shouldBe true
            }
        }

        test("an answer that is not the catalog is the target's fault when the address is fine") {
            runTest {
                val id = db.seedTarget("Пустая")

                runnerWith(emptyButSuccessful, healthyEgress).run()

                val state = stateOf(id)
                (state["last_crawled_at"] == null) shouldBe true
                state["consecutive_failures"] shouldBe 1
                state["egress_failure_count"] shouldBe 0
                (state["next_attempt_at"] != null) shouldBe true
            }
        }

        test("an answer that is not the catalog is not the target's fault when the address is refusing") {
            runTest {
                val id = db.seedTarget("Пустая-при-отказе")

                runnerWith(emptyButSuccessful, sickEgress).run()

                val state = stateOf(id)
                (state["last_crawled_at"] == null) shouldBe true
                state["consecutive_failures"] shouldBe 0
                state["egress_failure_count"] shouldBe 1
                // The group that produced the anomaly is remembered, so the same anomaly
                // from another group can be told apart from it.
                state["anomaly_group"] shouldBe "ip-1"
            }
        }

        test("a target that was read is not due again straight away") {
            runTest {
                db.seedTarget("Прочитанная")

                val runner = runnerWith(emptyButSuccessful, parser = parserWithOneItem)
                runner.run()
                val second = runner.run()

                // The second run has nothing to do: the freshness window has not passed.
                (second is CrawlRunner.RunResult.NothingDue) shouldBe true
            }
        }
    })
