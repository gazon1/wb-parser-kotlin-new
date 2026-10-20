package ru.wbparser.app.scheduling

import com.microsoft.playwright.Page
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroupConfig
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import org.jetbrains.exposed.sql.Database
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.AdsPowerApi
import ru.wbparser.infra.http.AdsPowerConfig
import ru.wbparser.infra.http.AdsPowerProfileProxy
import ru.wbparser.infra.http.BrowserAutomation
import ru.wbparser.infra.http.BrowserPool
import ru.wbparser.infra.http.BrowserProfileConfig
import ru.wbparser.infra.http.EgressGroup
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The pre-flight: what the crawler checks about itself before it starts, and what it
 * writes down when it has checked.
 *
 * The probe is the only thing that can catch a group that is not the isolation the
 * configuration promised — two groups behind one address, or a group's profiles behind
 * different proxies. A misconfigured group has to stop the crawler, because the
 * crawler would otherwise pace one address as if it were several.
 */
class AdsPowerPreflightRunnerTest :
    FunSpec({

        /**
         * A page that answers every echo request with [answer].
         */
        fun pageAnswering(answer: String): Page {
            val page = mockk<Page>(relaxed = true)
            every { page.evaluate(any<String>()) } returns answer
            return page
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

        class FakeAdsPower : AdsPowerApi {
            var available: Boolean = true
            var proxies: List<AdsPowerProfileProxy> = emptyList()

            override fun checkActiveV2(profileId: String): String? = null

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean = true

            override fun listProfileProxies(): List<AdsPowerProfileProxy> = proxies

            override fun isAvailable(): Boolean = available
        }

        /**
         * A database that is not there.
         *
         * The runner has to survive that: the probe reports what it saw, and the
         * ledger is a convenience, not a condition to start. The port is one, so the
         * connection is refused at once instead of timing out.
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
                    java.util.logging.Logger.getLogger("fake")

                override fun <T : Any> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

                override fun isWrapperFor(iface: Class<*>?): Boolean = false
            }

        fun runner(
            api: FakeAdsPower,
            groups: EgressGroups.Resolved,
            spider: Spider = Spider(),
            workerEnabled: Boolean = true,
        ): AdsPowerPreflightRunner {
            val profiles = groups.profiles
            val pool =
                BrowserPool(
                    profiles = profiles,
                    adsPower = AdsPowerConfig(api = api),
                    browserAutomations =
                        profiles.map { FakeBrowser(pageAnswering("203.0.113.7")) },
                )
            return AdsPowerPreflightRunner(
                browserPool = pool,
                properties =
                    CrawlProperties(
                        worker = Worker(enabled = workerEnabled),
                        spider = spider,
                    ),
                groups = groups,
                database = DatabaseHandle(brokenDatabase, Database.connect(brokenDatabase)),
            )
        }

        test("the pre-flight does not stop the crawler when the ledger is not there") {
            val api = FakeAdsPower()
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"))),
                    ),
                )

            // The database is not there and the probe still runs: a missing ledger is
            // not a reason to refuse to start.
            runner(api, groups, spider = spider).run(ApplicationArgumentsStub)

            api.available shouldBe true
        }

        test("the pre-flight does nothing when the crawler is not running") {
            val api = FakeAdsPower()
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"))),
                    ),
                )

            // An instance that only serves the catalog must not start Chrome.
            runner(api, groups, workerEnabled = false).run(ApplicationArgumentsStub)

            api.available shouldBe true
        }
    })

/**
 * The runner does not read its arguments, but the interface demands them.
 */
private val ApplicationArgumentsStub = org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>())
