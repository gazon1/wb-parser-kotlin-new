package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.claimDueTargets
import ru.wbparser.infra.db.repositories.releaseTargetLeases
import ru.wbparser.infra.scheduler.FreshnessPolicy
import ru.wbparser.testing.PostgresFixture
import java.time.Duration
import java.time.Instant

/**
 * The claim: which targets a host takes, and for how long.
 *
 * The claim is what lets several hosts share the work instead of queueing for it: the
 * rows are taken atomically, so two hosts never take the same target, and a lease
 * that is not released expires on its own.
 *
 * The rules of "due" live in the claim's SQL, so they are tested here — against a real
 * database, which is the only place those rules mean anything.
 */
class TargetLeaseTest :
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

        val policy = FreshnessPolicy(defaultFreshnessMinutes = 240)

        fun claim(
            owner: String,
            now: Instant = Instant.now(),
            limit: Int = 100,
            lease: Duration = Duration.ofHours(2),
        ): List<java.util.UUID> =
            db.ds.claimDueTargets(
                owner = owner,
                now = now,
                freshness = policy,
                limit = limit,
                leaseFor = lease,
            )

        test("a target that was never read is claimed") {
            val id = db.seedTarget("fresh")

            claim("host-a") shouldBe listOf(id)
        }

        test("a target that was read recently is not claimed") {
            val id = db.seedTarget("recent")
            db.ds.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE crawl_targets SET last_crawled_at = now() - interval '10 minutes' WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }

            claim("host-a") shouldBe emptyList()
        }

        test("a target that was read long ago is claimed") {
            val id = db.seedTarget("old")
            db.ds.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE crawl_targets SET last_crawled_at = now() - interval '10 hours' WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }

            claim("host-a") shouldBe listOf(id)
        }

        test("a target that is waiting after a failure is not claimed") {
            val id = db.seedTarget("waiting")
            db.ds.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE crawl_targets SET next_attempt_at = now() + interval '30 minutes' WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }

            claim("host-a") shouldBe emptyList()
        }

        test("an inactive target is not claimed") {
            db.seedTarget("off", isActive = false)

            claim("host-a") shouldBe emptyList()
        }

        test("a target another host holds is not claimed") {
            val id = db.seedTarget("taken")

            claim("host-a") shouldBe listOf(id)
            // The second host finds nothing: the first one has it.
            claim("host-b") shouldBe emptyList()
        }

        test("an expired lease is a free lease") {
            val id = db.seedTarget("expired")
            db.ds.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE crawl_targets SET lease_owner = 'dead-host', lease_until = now() - interval '1 minute' WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }

            claim("host-b") shouldBe listOf(id)
        }

        test("a released lease frees the target again") {
            val id = db.seedTarget("released")

            claim("host-a") shouldBe listOf(id)
            db.ds.releaseTargetLeases("host-a") shouldBe 1
            claim("host-b") shouldBe listOf(id)
        }

        test("the claim takes as many targets as it may, and no more") {
            val ids = (1..5).map { db.seedTarget("target-$it") }

            claim("host-a", limit = 3).size shouldBe 3
            // The rest wait for the next run — the lease is what holds them.
            claim("host-b").size shouldBe 2
            ids.size shouldBe 5
        }
    })
