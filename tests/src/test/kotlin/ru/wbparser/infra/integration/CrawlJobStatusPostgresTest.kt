package ru.wbparser.infra.integration

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.testing.PostgresFixture

/**
 * The `crawl_jobs` row must describe what actually happened.
 *
 * ## The defect this pins
 *
 * `runTarget` collapsed a failed crawl with `is Either.Left -> TargetResult(0, 0)`,
 * throwing the `DomainError` away. `runUnsafe` then closed the job with the literal
 * `"Completed"`. The result was a fully failed run recorded as a success, with zero
 * pages, zero items and a **NULL** `error_message` — the exact opposite of what a
 * diagnostic reading `crawl_jobs` would conclude.
 *
 * ## Why a real PostgreSQL
 *
 * The status column carries `CHECK (status IN (...))`. Asserting on the string that
 * reached the database is the only way to catch a status the schema would reject —
 * a unit test on an enum proves nothing about what was persisted.
 */
class CrawlJobStatusPostgresTest :
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

        /** A downloader that fails for URLs containing [failsOn] and succeeds otherwise. */
        fun downloader(failsOn: String? = null): suspend (Crawling) -> Either<NetworkError, Fetched> =
            { task ->
                if (failsOn != null && task.url.toString().contains(failsOn)) {
                    Either.Left(NetworkError("simulated transport failure", null, task.url.toString()))
                } else {
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
            }

        val emptyParser: (Fetched) -> ParsedPage = { fetched ->
            ParsedPage(fetched, emptyList(), null, false)
        }

        test("a crawl whose download fails is recorded as Failed, not Completed") {
            runTest {
                db.seedTarget("Падающий")

                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(failsOn = "Падающий"),
                        parser = emptyParser,
                    )

                val result = runner.run()

                (result is CrawlRunner.RunResult.Failure) shouldBe true

                val job = db.row("SELECT status, error_message, pages_crawled FROM crawl_jobs")
                job["status"] shouldBe "Failed"
                (job["error_message"] as? String).shouldNotBeEmpty()
            }
        }

        test("the failed run reports the failing target, not a bare status") {
            runTest {
                db.seedTarget("Падающий")

                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(failsOn = "Падающий"),
                        parser = emptyParser,
                    )
                runner.run()

                val message = db.row("SELECT error_message FROM crawl_jobs")["error_message"]
                (message as String).contains("Падающий") shouldBe true
            }
        }

        test("a fully successful crawl is still recorded as Completed") {
            runTest {
                db.seedTarget("Рабочий")

                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(),
                        parser = emptyParser,
                    )

                val result = runner.run()

                (result is CrawlRunner.RunResult.Success) shouldBe true
                db.row("SELECT status FROM crawl_jobs")["status"] shouldBe "Completed"
            }
        }

        test("partial success across targets is Failed — the schema has no Partial status") {
            runTest {
                db.seedTarget("Рабочий")
                db.seedTarget("Падающий")

                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(failsOn = "Падающий"),
                        parser = emptyParser,
                    )

                val result = runner.run()

                // One target crawled, one did not. Reporting "Completed" would hide a lost
                // catalog behind a successful sibling.
                (result is CrawlRunner.RunResult.Failure) shouldBe true
                val job = db.row("SELECT status, error_message FROM crawl_jobs")
                job["status"] shouldBe "Failed"
                (job["error_message"] as? String).shouldNotBeEmpty()
            }
        }

        test("a crawl with nothing due opens no job row at all") {
            runTest {
                // crawl_jobs.target_id is NOT NULL, so an empty selection must not open a row.
                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(),
                        parser = emptyParser,
                    )

                val result = runner.run()

                (result is CrawlRunner.RunResult.Success) shouldBe true
                db.rows("SELECT id FROM crawl_jobs").size shouldBe 0
            }
        }

        test("a crawl can be run twice in a row — the advisory lock is released between runs") {
            runTest {
                db.seedTarget("Рабочий")

                suspend fun runOnce() =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(),
                        parser = emptyParser,
                    ).run()

                val first = runOnce()
                val second = runOnce()

                // The lock release used to run `executeUpdate()` against pg_advisory_unlock,
                // which returns a boolean. PostgreSQL rejected it, so the run after the first
                // never happened: the caller saw an exception and the lock leaked.
                (first is CrawlRunner.RunResult.Success) shouldBe true
                (second is CrawlRunner.RunResult.Success) shouldBe true
                db.rows("SELECT id FROM crawl_jobs").size shouldBe 2
            }
        }

        test("every status written is one the schema accepts") {
            runTest {
                db.seedTarget("Падающий")
                val runner =
                    CrawlRunner(
                        db = handle(),
                        downloader = downloader(failsOn = "Падающий"),
                        parser = emptyParser,
                    )
                runner.run()

                // Guards the boundary between the domain enum and the database constraint:
                // an enum addition that the schema has not been migrated for would fail
                // here rather than in production.
                val allowed =
                    setOf("Created", "Running", "Completed", "Failed", "Cancelled", "Crashed")
                val status = db.row("SELECT status FROM crawl_jobs")["status"]
                (status in allowed) shouldBe true
                db.row("SELECT status FROM crawl_jobs")["status"].shouldNotBeNull()
            }
        }
    })
