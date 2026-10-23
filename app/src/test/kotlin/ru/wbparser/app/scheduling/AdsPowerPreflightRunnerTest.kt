package ru.wbparser.app.scheduling

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.Database
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroupConfig
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.app.config.ProxyConfig
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.AdsPowerProfileProxy
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.testing.FakeBrowserPage
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.BrowserAccess
import ru.wbparser.infra.http.EgressPacerRegistry
import ru.wbparser.infra.http.EgressRouter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
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
        fun pageAnswering(answer: String): BrowserPage = FakeBrowserPage(evaluateAnswer = answer)

        class FakeBrowser(
            private val page: BrowserPage,
        ) : BrowserAutomation {
            override var isConnected: Boolean = true
            override val currentPage: BrowserPage? get() = page

            override fun healthCheck(): Boolean = true

            override fun connect(cdpUrl: String): BrowserPage = page

            override fun close() {
                isConnected = false
            }
        }

        class FakeAdsPower : AdsPowerApi {
            var available: Boolean = true
            var proxies: List<AdsPowerProfileProxy> = emptyList()

            /** When set, [checkActiveV2] throws this instead of answering. */
            var checkActiveFailure: RuntimeException? = null

            override fun checkActiveV2(profileId: String): String? {
                checkActiveFailure?.let { throw it }
                return null
            }

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
                    java.util.logging.Logger
                        .getLogger("fake")

                override fun <T : Any> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

                override fun isWrapperFor(iface: Class<*>?): Boolean = false
            }

        /**
         * A cache directory with one profile in it.
         *
         * The pre-flight reads the real AdsPower directory, and a test that depends
         * on the machine's AdsPower installation is a test that says nothing about
         * the code. This is the fixture: a directory named the way AdsPower names a
         * profile, with whatever the check is supposed to find inside it.
         */
        fun cacheDirWithProfile(
            profileId: String,
            prepare: (Path) -> Unit = {},
        ): Path {
            val cacheDir = Files.createTempDirectory("adspower-cache")
            val profileDir = cacheDir.resolve("${profileId}_fixture")
            Files.createDirectories(profileDir)
            prepare(profileDir)
            return cacheDir
        }

        fun runner(
            api: FakeAdsPower,
            groups: EgressGroups.Resolved,
            spider: Spider = Spider(),
            workerEnabled: Boolean = true,
            answer: String = "203.0.113.7",
        ): AdsPowerPreflightRunner {
            val profiles = groups.profiles
            val pool =
                BrowserPool(
                    profiles = profiles,
                    adsPower = AdsPowerConfig(api = api),
                    browserAutomations = profiles.map { FakeBrowser(pageAnswering(answer)) },
                )
            val properties =
                CrawlProperties(
                    worker = Worker(enabled = workerEnabled),
                    spider = spider,
                )
            val database = DatabaseHandle(brokenDatabase, Database.connect(brokenDatabase))
            return AdsPowerPreflightRunner(
                adsPowerApi = api,
                properties = properties,
                groups = groups,
                probeService =
                    EgressProbeService(
                        browser = BrowserAccess(pool = pool, adsPowerApi = api),
                        properties = properties,
                        groups = groups,
                        database = database,
                        router = EgressRouter(pool, EgressPacerRegistry(groups = listOf("ip-1"))),
                    ),
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
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
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
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
                    ),
                )

            // An instance that only serves the catalog must not start Chrome.
            runner(api, groups, workerEnabled = false).run(ApplicationArgumentsStub)

            api.available shouldBe true
        }

        test("two groups seen at one address are refused when the guarantee was asked for") {
            val api = FakeAdsPower()
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                    requireIsolatedEgressGroups = true,
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p2"), proxy = ProxyConfig(type = "none")),
                            ),
                    ),
                )

            // Both profiles answer with the same address: the isolation the crawl was
            // promised is not there, and the operator asked for the guarantee.
            val failure =
                shouldThrow<IllegalStateException> {
                    runner(api, groups, spider = spider).run(ApplicationArgumentsStub)
                }

            failure.message!! shouldContain "ip-1"
        }

        test("an echo service that does not answer does not stop the crawler") {
            val api = FakeAdsPower()
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                    requireIsolatedEgressGroups = true,
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p2"), proxy = ProxyConfig(type = "none")),
                            ),
                    ),
                )

            // "The check could not run" is not "the check failed": a third-party echo
            // service being down must not keep the crawler from working.
            runner(api, groups, spider = spider, answer = "").run(ApplicationArgumentsStub)

            api.available shouldBe true
        }

        test("a group whose declared proxy is not the one its profiles use stops the crawler") {
            val api = FakeAdsPower()
            // AdsPower says the profile goes through an http proxy; the configuration
            // says the group leaves through a socks5 one.
            api.proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"))
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1"),
                                    proxy = ProxyConfig(type = "socks5"),
                                ),
                            ),
                    ),
                )

            // This one does not wait for the operator's answer about isolation: a group
            // that leaves somewhere else gets the budget and the metric of an address
            // nobody meant, and the crawl would carry on as if nothing were wrong.
            val failure =
                shouldThrow<IllegalStateException> {
                    runner(api, groups, spider = spider).run(ApplicationArgumentsStub)
                }

            failure.message!! shouldContain "declared proxy"
        }

        test(
            "a group whose profiles are behind different proxies stops the crawler when the " +
                "guarantee was asked for",
        ) {
            val api = FakeAdsPower()
            // Two profiles, two proxies, one group. A group is one address, and the
            // crawler would pace one address as if it were several.
            api.proxies =
                listOf(
                    AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                    AdsPowerProfileProxy("p2", "proxy-b.invalid", "1080", "http"),
                )
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                    requireIsolatedEgressGroups = true,
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1", "p2"),
                                    // The kind alone: the declaration says "this group
                                    // leaves through an http proxy", and both profiles
                                    // do. Which http proxy is another question — and
                                    // the answer to it is the problem here.
                                    proxy = ProxyConfig(type = "http"),
                                ),
                            ),
                    ),
                )

            val failure =
                shouldThrow<IllegalStateException> {
                    runner(api, groups, spider = spider).run(ApplicationArgumentsStub)
                }

            failure.message!! shouldContain "misconfigured"
        }

        test(
            "a group whose profiles are behind different proxies is a warning when the " +
                "guarantee was not asked for",
        ) {
            val api = FakeAdsPower()
            api.proxies =
                listOf(
                    AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                    AdsPowerProfileProxy("p2", "proxy-b.invalid", "1080", "http"),
                )
            val spider =
                Spider(
                    useBrowserFetch = true,
                    egressProbeUrls = listOf("https://echo.invalid/ip"),
                )
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1", "p2"),
                                    // The kind alone: the declaration says "this group
                                    // leaves through an http proxy", and both profiles
                                    // do. Which http proxy is another question — and
                                    // the answer to it is the problem here.
                                    proxy = ProxyConfig(type = "http"),
                                ),
                            ),
                    ),
                )

            // The crawl still works — it just does not get the extra budget the
            // configuration promised. The operator was told, and the operator decides.
            runner(api, groups, spider = spider).run(ApplicationArgumentsStub)

            api.available shouldBe true
        }

        test("a pre-flight check that could not run does not stop the crawler") {
            val api = FakeAdsPower()
            // The runtime answers the wrong question, and the check cannot be done.
            api.checkActiveFailure = RuntimeException("the runtime went away")
            val groups =
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
                    ),
                )
            val cacheDir = cacheDirWithProfile("p1") { dir -> Files.createFile(dir.resolve("SingletonLock")) }

            val runner = runner(api, groups)
            runner.profileCacheDir = cacheDir

            // AdsPower may legitimately come up after the crawler does. A check that
            // could not run is a fact to report, not a reason to refuse to start.
            runner.run(ApplicationArgumentsStub)

            api.available shouldBe true
        }
    })

/**
 * The runner does not read its arguments, but the interface demands them.
 */
private val ApplicationArgumentsStub = org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>())
