package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.db.repositories.firstActiveTargetId
import ru.wbparser.testing.PostgresFixture
import java.time.Instant

/**
 * Integration test for [fetchActiveTargets] and [firstActiveTargetId].
 *
 * ## Why this needed its own suite
 *
 * `TargetQueries.kt` had no test anywhere in the repository — not in `infrastructure`
 * (which has no test source set at all), not in `tests`. It is the other half of the
 * write path: every `scraped_items.target_id` value originates as a `Target.id` read
 * through [fetchActiveTargets], and the comment on `toTarget()` says so — "the real
 * UUID, it is written back as scraped_items.target_id (a foreign key)".
 *
 * So an error here does not look like a broken scheduler, it looks like an empty
 * `scraped_items` table: the crawl runs, every insert violates the foreign key, and
 * nothing anywhere reports why.
 *
 * The untested line most likely to break silently is
 * `getObject("updated_at", OffsetDateTime::class.java)`. `crawl_targets.updated_at` is
 * `TIMESTAMPTZ`. Reading it as a `LocalDateTime` — the obvious refactor, and one that
 * still compiles — shifts every timestamp by the JVM offset, and the symptom is a
 * scheduler that runs at the wrong hour rather than an error.
 */
class TargetQueriesPostgresTest :
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

        // -------------------------------------------------------------------------
        // fetchActiveTargets
        // -------------------------------------------------------------------------

        test("reads the real UUID, not a derived one — scraped_items.target_id is a foreign key") {
            val expected = db.seedTarget("Ноутбуки")

            val targets = db.ds.fetchActiveTargets()

            targets shouldHaveSize 1
            targets.single().id shouldBe expected
        }

        test("returns only active targets") {
            db.seedTarget("Активный", isActive = true)
            db.seedTarget("Выключенный", isActive = false)

            db.ds.fetchActiveTargets().map { it.name } shouldContainExactly listOf("Активный")
        }

        test("an empty table yields an empty list, not an error") {
            // The crawler starts against an empty database more often than it should.
            // A query that threw here would take the scheduler down at boot.
            db.ds.fetchActiveTargets() shouldBe emptyList()
        }

        test("maps the scalar columns the crawler acts on") {
            db.seedTarget("Кроссовки")

            val target = db.ds.fetchActiveTargets().single()

            target.name shouldBe "Кроссовки"
            target.url shouldBe "https://example.invalid/Кроссовки"
            target.maxDepth shouldBe 1
            target.isActive shouldBe true
        }

        test("updated_at arrives as the instant it was written, not shifted by the JVM offset") {
            // A deliberately awkward hour: read as a local time instead of an instant, this
            // lands nine hours off on a Europe/Moscow host, which no narrow window absorbs.
            val written = Instant.parse("2026-03-15T23:30:00Z")
            db.seedTarget("Смещение", updatedAt = written)

            val target = db.ds.fetchActiveTargets().single { it.name == "Смещение" }

            target.lastScheduledAt.shouldNotBeNull()
            (target.lastScheduledAt == written) shouldBe true
        }

        test("updated_at is NOT NULL — the schema, not the mapping, guarantees a non-null lastScheduledAt") {
            // `toTarget()` reads it through a nullable `getObject(...)?`, so a reader may
            // reasonably assume the column can be absent. It cannot: V1 declares it
            // `TIMESTAMPTZ NOT NULL DEFAULT now()`. If a later migration relaxes that, the
            // null branch in `toTarget()` becomes live and the crawler will schedule against
            // a target it has never run — better to learn that here.
            val nullable =
                db.row(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name = 'crawl_targets' AND column_name = 'updated_at'
                    """.trimIndent(),
                )["is_nullable"]

            nullable shouldBe "NO"
        }

        test("every active target is returned, in no particular order") {
            val ids = (1..5).map { db.seedTarget("Цель $it") }

            val fetched = db.ds.fetchActiveTargets().map { it.id }

            fetched shouldHaveSize 5
            // No ORDER BY in the query, so this asserts set membership rather than sequence.
            fetched.toSet() shouldBe ids.toSet()
        }

        // -------------------------------------------------------------------------
        // firstActiveTargetId
        // -------------------------------------------------------------------------

        test("returns the oldest active target, not an arbitrary one") {
            // `ORDER BY created_at` is the whole point: without it PostgreSQL returns rows
            // in whatever order the scan produces, and a restart can hand a different crawl
            // a different target.
            val base = Instant.parse("2026-01-01T00:00:00Z")
            val oldest = db.seedTarget("Первый", createdAt = base, updatedAt = base)
            db.seedTarget("Второй", createdAt = base.plusSeconds(60), updatedAt = base.plusSeconds(60))
            db.seedTarget("Третий", createdAt = base.plusSeconds(120), updatedAt = base.plusSeconds(120))

            db.ds.firstActiveTargetId() shouldBe oldest
        }

        test("ignores inactive targets even when they are older") {
            val base = Instant.parse("2026-01-01T00:00:00Z")
            db.seedTarget("Выключенный", isActive = false, createdAt = base, updatedAt = base)
            val active = db.seedTarget("Рабочий", createdAt = base.plusSeconds(60), updatedAt = base.plusSeconds(60))

            db.ds.firstActiveTargetId() shouldBe active
        }

        test("returns null when nothing is active — the crawler has no target to attribute a job to") {
            db.seedTarget("Выключенный", isActive = false)

            db.ds.firstActiveTargetId() shouldBe null
        }

        test("returns null against an empty table") {
            db.ds.firstActiveTargetId() shouldBe null
        }

        // -------------------------------------------------------------------------
        // The two functions must agree
        // -------------------------------------------------------------------------

        test("firstActiveTargetId is a member of fetchActiveTargets") {
            // They are separate queries against the same table; a divergence means one of
            // them disagrees with the other about what "active" means.
            db.seedTarget("Первый")
            db.seedTarget("Второй")
            db.seedTarget("Выключенный", isActive = false)

            val fromList = db.ds.fetchActiveTargets().map { it.id }
            val fromSingle = db.ds.firstActiveTargetId()

            fromSingle.shouldNotBeNull()
            (fromSingle in fromList) shouldBe true
        }

        test("crawl_targets.updated_at is TIMESTAMPTZ — the mapping above depends on it") {
            // If a future migration changes this to TEXT or TIMESTAMP WITHOUT TIME ZONE,
            // `toTarget()` starts shifting or failing and this suite should say so in the
            // migration that caused it, not three weeks later in production.
            val dataType =
                db.row(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_name = 'crawl_targets' AND column_name = 'updated_at'
                    """.trimIndent(),
                )["data_type"]

            dataType shouldBe "timestamp with time zone"
        }
    })
