package ru.wbparser.app.scheduling

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.Database
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroupConfig
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.app.config.ProxyConfig
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.testing.FakeBrowserAutomation
import ru.wbparser.browser.testing.FakeBrowserPage
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.BrowserAccess
import ru.wbparser.infra.http.EgressPacerRegistry
import ru.wbparser.infra.http.EgressRouter
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The pre-flight: what the crawler checks about itself before it starts.
 *
 * The probe is the only thing that can catch a group that is not the isolation the
 * configuration promised. A misconfigured group has to stop the crawler when the
 * operator asked for the guarantee, and be a warning when they did not.
 */
class EgressPreflightRunnerTest :
    FunSpec({

        /**
         * A page that answers every echo request with [answer].
         */
        fun pageAnswering(answer: String): BrowserPage = FakeBrowserPage(evaluateAnswer = answer)

        /**
         * A database that is not there.
         *
         * The runner has to survive that: the probe reports what it saw, and the
         * ledger is a convenience, not a condition to start.
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

        fun runner(
            groups: EgressGroups.Resolved,
            spider: Spider = Spider(),
            workerEnabled: Boolean = true,
            answer: String = "203.0.113.7",
        ): EgressPreflightRunner {
            val profiles = groups.profiles
            val pool =
                BrowserPool(
                    profiles = profiles,
                    browserAutomations = profiles.map { FakeBrowserAutomation(_isConnected = true, _currentPage = pageAnswering(answer)) },
                )
            val properties =
                CrawlProperties(
                    worker = Worker(enabled = workerEnabled),
                    spider = spider,
                )
            val database = DatabaseHandle(brokenDatabase, Database.connect(brokenDatabase))
            return EgressPreflightRunner(
                properties = properties,
                groups = groups,
                probeService =
                    EgressProbeService(
                        browser = BrowserAccess(pool = pool),
                        properties = properties,
                        groups = groups,
                        database = database,
                        router = EgressRouter(pool, EgressPacerRegistry(groups = listOf("ip-1"))),
                    ),
            )
        }

        fun twoGroups() =
            EgressGroups.resolve(
                Spider(
                    egressGroups =
                        listOf(
                            EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                            EgressGroupConfig(id = "ip-2", profiles = listOf("p2"), proxy = ProxyConfig(type = "none")),
                        ),
                ),
            )

        test("the pre-flight does not stop the crawler when the ledger is not there") {
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
                    ),
                )

            // The database is not there and the probe still runs: a missing ledger is
            // not a reason to refuse to start.
            runner(groups, spider = spider).run(ApplicationArgumentsStub)
        }

        test("the pre-flight does nothing when the crawler is not running") {
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
                    ),
                )

            // An instance that only serves the catalog must not start a browser.
            runner(groups, workerEnabled = false).run(ApplicationArgumentsStub)
        }

        test("two groups seen at one address are refused when the guarantee was asked for") {
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                    requireIsolatedEgressGroups = true,
                )

            // Both profiles answer with the same address: the isolation the crawl was
            // promised is not there, and the operator asked for the guarantee.
            val failure =
                shouldThrow<IllegalStateException> {
                    runner(twoGroups(), spider = spider).run(ApplicationArgumentsStub)
                }

            failure.message!! shouldContain "ip-1"
        }

        test("two groups seen at one address are a warning when the guarantee was not asked for") {
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                )

            // The crawl still works — it just does not get the extra budget the
            // configuration promised. The operator was told, and the operator decides.
            runner(twoGroups(), spider = spider).run(ApplicationArgumentsStub)
        }

        test("an echo service that does not answer does not stop the crawler") {
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                    requireIsolatedEgressGroups = true,
                )

            // "The check could not run" is not "the check failed": a third-party echo
            // service being down must not keep the crawler from working.
            runner(twoGroups(), spider = spider, answer = "").run(ApplicationArgumentsStub)
        }
    })

/**
 * The runner does not read its arguments, but the interface demands them.
 */
private val ApplicationArgumentsStub = org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>())
