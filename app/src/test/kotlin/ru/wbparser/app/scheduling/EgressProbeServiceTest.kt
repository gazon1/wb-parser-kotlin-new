package ru.wbparser.app.scheduling

import com.microsoft.playwright.Page
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.sql.Database
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroupConfig
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.AdsPowerApi
import ru.wbparser.infra.http.AdsPowerConfig
import ru.wbparser.infra.http.AdsPowerProfileProxy
import ru.wbparser.infra.http.BrowserAutomation
import ru.wbparser.infra.http.BrowserPool
import ru.wbparser.infra.http.BrowserProfileConfig
import ru.wbparser.infra.http.EgressGroupProbe
import ru.wbparser.infra.http.EgressPacerRegistry
import ru.wbparser.infra.http.EgressReport
import ru.wbparser.infra.http.EgressRouter
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * What happens to a group whose address changed.
 *
 * A changed address is a new identity: the failures the old address collected do not
 * belong to the new one. The circuit closes, the books go, and the targets the old
 * address delayed are released — the address that failed is not the address that will
 * serve next time.
 */
class EgressProbeServiceTest :
    FunSpec({

        /**
         * The AdsPower API of a machine where the profiles are known and have no proxy.
         */
        class FakeAdsPower : AdsPowerApi {
            override fun checkActiveV2(profileId: String): String? = null

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean = true

            override fun listProfileProxies(): List<AdsPowerProfileProxy> = listOf(AdsPowerProfileProxy("p1", null, null, null, "no_proxy"))

            override fun isAvailable(): Boolean = true
        }

        class FakeBrowser(
            private val page: Page,
        ) : BrowserAutomation {
            override var isConnected: Boolean = true
            override val currentPage: Page? get() = page

            override fun healthCheck(): Boolean = true

            override fun connect(cdpUrl: String): Page = page

            override fun close() {
                isConnected = false
            }
        }

        /**
         * A database that is not there.
         *
         * The re-baseline must survive that: the ledger is a convenience, not a
         * condition to keep working.
         */
        val brokenDatabase: DataSource =
            object : DataSource {
                override fun getConnection(): Connection = throw SQLException("no database here")

                override fun getConnection(
                    username: String?,
                    password: String?,
                ): Connection = throw SQLException("no database here")

                override fun getLogWriter(): PrintWriter = throw UnsupportedOperationException()

                override fun setLogWriter(out: PrintWriter?) = Unit

                override fun setLoginTimeout(seconds: Int) = Unit

                override fun getLoginTimeout(): Int = 0

                override fun getParentLogger(): java.util.logging.Logger =
                    java.util.logging.Logger
                        .getLogger("fake")

                override fun <T : Any> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

                override fun isWrapperFor(iface: Class<*>?): Boolean = false
            }

        fun page(): Page {
            val page = mockk<Page>(relaxed = true)
            every { page.evaluate(any<String>()) } returns "203.0.113.9"
            return page
        }

        fun service(router: EgressRouter): EgressProbeService {
            val profiles = listOf(BrowserProfileConfig("p1", "ws://p1", "ip-1"))
            val pool =
                BrowserPool(
                    profiles = profiles,
                    adsPower = AdsPowerConfig(api = FakeAdsPower()),
                    browserAutomations = profiles.map { FakeBrowser(page()) },
                )
            return EgressProbeService(
                browserPool = pool,
                properties =
                    CrawlProperties(
                        worker = Worker(enabled = true),
                        spider = Spider(useBrowserFetch = true),
                    ),
                groups =
                    EgressGroups.resolve(
                        Spider(egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1")))),
                    ),
                database = DatabaseHandle(brokenDatabase, Database.connect(brokenDatabase)),
                router = router,
            )
        }

        test("a group whose address changed starts with an empty book") {
            val profiles = listOf(BrowserProfileConfig("p1", "ws://p1", "ip-1"))
            val pool =
                BrowserPool(
                    profiles = profiles,
                    browserAutomations = profiles.map { FakeBrowser(page()) },
                )
            val router = EgressRouter(pool, EgressPacerRegistry(groups = listOf("ip-1")))
            val borrowed = router.borrow()
            router.onOutcome(borrowed, ru.wbparser.infra.http.DownloadOutcome.Antibot, null)
            borrowed.close()
            router.groupState("ip-1").requests shouldBe 1

            val report =
                EgressReport(
                    groups =
                        listOf(
                            EgressGroupProbe(
                                groupId = "ip-1",
                                egressIp = "203.0.113.9",
                                isolated = true,
                                proxyIdentity = "socks5://203.0.113.9:1080",
                                ipChanged = true,
                            ),
                        ),
                    problems = emptyList(),
                    misconfigured = false,
                )

            service(router).reBaseline(report)

            val state = router.groupState("ip-1")
            state.requests shouldBe 0
            state.refused shouldBe 0
        }

        test("a group whose address did not change keeps its book") {
            val profiles = listOf(BrowserProfileConfig("p1", "ws://p1", "ip-1"))
            val pool =
                BrowserPool(
                    profiles = profiles,
                    browserAutomations = profiles.map { FakeBrowser(page()) },
                )
            val router = EgressRouter(pool, EgressPacerRegistry(groups = listOf("ip-1")))
            val borrowed = router.borrow()
            router.onOutcome(borrowed, ru.wbparser.infra.http.DownloadOutcome.Antibot, null)
            borrowed.close()

            val report =
                EgressReport(
                    groups =
                        listOf(
                            EgressGroupProbe(
                                groupId = "ip-1",
                                egressIp = "203.0.113.9",
                                isolated = true,
                                proxyIdentity = null,
                                ipChanged = false,
                            ),
                        ),
                    problems = emptyList(),
                    misconfigured = false,
                )

            service(router).reBaseline(report)

            router.groupState("ip-1").requests shouldBe 1
        }
    })
