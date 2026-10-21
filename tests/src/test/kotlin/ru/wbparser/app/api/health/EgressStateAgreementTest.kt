package ru.wbparser.app.api.health

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.db.repositories.upsertEgressGroup
import ru.wbparser.infra.http.EgressAttribution
import ru.wbparser.infra.http.EgressGroupCounters
import ru.wbparser.infra.http.EgressGroupState
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.infra.runner.CrawlRunnerConfig
import ru.wbparser.infra.runner.CrawlSource
import ru.wbparser.testing.PostgresFixture
import java.util.UUID

/**
 * The health answer and the runner's decision, read from one state.
 *
 * «Доступен ли адрес» was answered in three places, and the answers drifted apart: the
 * health endpoint showed a picture the runner did not believe, and the operator and the
 * crawl blamed different addresses for the same failure. The state of a group is one
 * answer now, and this is the test that says the two readers cannot disagree about it.
 */
class EgressStateAgreementTest :
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

        /**
         * A downloader that answers with a body and no products: the crawl reads a page
         * that is not the catalog.
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

        val emptyParser: (Fetched) -> Either<ParseError, ParsedPage> = { fetched ->
            Either.Right(ParsedPage(fetched, emptyList(), null, false))
        }

        test("the health answer and the runner's decision are the same verdict") {
            runTest {
                db.seedTarget("Пустая")
                db.ds.upsertEgressGroup(groupId = "ip-1", egressIp = "203.0.113.7", isolated = true)

                // One state, and the two readers that must not disagree about it. The
                // totals of the group add up to 5 %, and the window is full of refusals:
                // a verdict made of the totals would call the address fine.
                val state =
                    EgressGroupState(
                        groupId = "ip-1",
                        counters = EgressGroupCounters(requests = 20, refused = 1, pages = 19),
                        refusalRate = 1.0,
                    )
                val egress =
                    object : EgressAttribution {
                        override fun lastGroupFor(targetId: UUID): String? = "ip-1"

                        override fun groupState(groupId: String): EgressGroupState = state

                        override fun groupStates(): List<EgressGroupState> = listOf(state)
                    }

                val runner =
                    CrawlRunner(
                        db = DatabaseHandle(db.ds, Database.connect(db.ds)),
                        source =
                            CrawlSource(
                                downloader = emptyButSuccessful,
                                parser = emptyParser,
                                egress = egress,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 1,
                                retryPolicy = RetryPolicy(maxAttempts = 1),
                            ),
                    )
                runner.run()

                // The runner's decision: the address is refusing, so the target is not to
                // blame for what it answered.
                val target = db.row("SELECT consecutive_failures, egress_failure_count FROM crawl_targets")
                target["consecutive_failures"] shouldBe 0
                target["egress_failure_count"] shouldBe 1

                // The health answer, asked about the same state, says the same thing.
                val answer = egressStats(SimpleMeterRegistry(), db.ds.fetchEgressGroups(), egress.groupStates())
                val group = (answer["groups"] as List<*>).single() as Map<*, *>
                group["healthy"] shouldBe false
                group["refusal_rate"] shouldBe 1.0
                group["requests"] shouldBe 20L
                group["refused"] shouldBe 1L
                // The address is still asked: it is refusing, but its circuit is closed.
                group["available"] shouldBe true
                // And the group that served the target is the one the answer talks about.
                group["group"] shouldBe "ip-1"
            }
        }
    })
