package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.pipeline.CrawlEgressGroupsTable
import ru.wbparser.testing.ModelDatabase

/**
 * The ledger of egress groups, against a real PostgreSQL.
 *
 * The table is what an operator looks at when the metrics say a group is not
 * isolated, and the change counter is what says whether the proxy behind a group is
 * sticky — so both have to survive a restart and a second probe.
 */
class EgressGroupQueriesPostgresTest :
    FunSpec({

        val container = PostgreSQLContainer("postgres:16-alpine")
        lateinit var database: ModelDatabase

        beforeSpec {
            container.start()
            database = ModelDatabase(container, listOf(CrawlEgressGroupsTable))
        }
        afterSpec { container.stop() }
        beforeTest { database.clear() }

        test("a group that was never seen is not in the ledger") {
            database.dataSource.fetchEgressGroups() shouldHaveSize 0
        }

        test("a probe is recorded") {
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = true)

            val record = database.dataSource.fetchEgressGroups().single()
            record.groupId shouldBe "ip-1"
            record.egressIp shouldBe "203.0.113.7"
            record.isolated shouldBe true
            record.ipChanges shouldBe 0
        }

        test("the same address again is not a change") {
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = true)
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = true)

            val record = database.dataSource.fetchEgressGroups().single()
            record.ipChanges shouldBe 0
        }

        test("a different address is a change, and the count grows") {
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = true)
            database.dataSource.upsertEgressGroup("ip-1", "198.51.100.9", isolated = true)
            database.dataSource.upsertEgressGroup("ip-1", "192.0.2.3", isolated = true)

            val record = database.dataSource.fetchEgressGroups().single()
            record.egressIp shouldBe "192.0.2.3"
            record.ipChanges shouldBe 2
        }

        test("a group that is not isolated is recorded as such") {
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = false)

            database.dataSource
                .fetchEgressGroups()
                .single()
                .isolated shouldBe false
        }

        test("the groups are kept apart") {
            database.dataSource.upsertEgressGroup("ip-1", "203.0.113.7", isolated = true)
            database.dataSource.upsertEgressGroup("ip-2", "198.51.100.9", isolated = true)

            val records = database.dataSource.fetchEgressGroups()
            records shouldHaveSize 2
            records.map { it.groupId }.sorted() shouldBe listOf("ip-1", "ip-2")
        }
    })
