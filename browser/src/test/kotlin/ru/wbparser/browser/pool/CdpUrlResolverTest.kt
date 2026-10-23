package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import ru.wbparser.browser.testing.FakeAdsPowerApi

/**
 * Where the browser of a profile is, and what it took to get there.
 *
 * One decision with three answers — the configured URL, the AdsPower Local API, and,
 * only when the API is unreachable, the DevTools port scan — and two ways it can fail
 * in a way the caller has to be told about rather than papered over.
 *
 * The first failure is the dangerous one: the Local API answers, the profile does not
 * come up, and the port scan would attach this profile to *some* browser. Five
 * profiles and four browsers is how one anti-bot session ends up wearing five names,
 * so the resolver says what is wrong instead of returning a browser that is not this
 * profile's.
 *
 * The second is the API dying mid-call: then the scan is all that is left, and it is
 * a worse answer than the API's, but it is the only one there is.
 */
class CdpUrlResolverTest :
    FunSpec({

        fun context(endpoints: List<String> = listOf("ws://localhost:36931/devtools/browser/one")): BrowserPoolContext =
            BrowserPoolContext(
                index = 0,
                profileCount = 1,
                allProfileIds = listOf("p0"),
                resolveEndpoints = { endpoints },
                log = LoggerFactory.getLogger("CdpUrlResolverTest"),
            )

        fun resolver(
            api: FakeAdsPowerApi,
            profileId: String = "p0",
        ): CdpUrlResolver =
            CdpUrlResolver(
                profileConfig = BrowserProfileConfig(profileId),
                api = api,
                context = context(),
            )

        test("the configured URL wins over everything else") {
            val api = FakeAdsPowerApi()
            val resolver =
                CdpUrlResolver(
                    profileConfig = BrowserProfileConfig("p0", "ws://configured/devtools/browser/p0"),
                    api = api,
                    context = context(),
                )

            val resolution = resolver.resolve()

            resolution.url shouldBe "ws://configured/devtools/browser/p0"
            // Nobody else may close it: this process did not start it.
            resolution.alreadyRunning shouldBe false
            resolution.startedByUs shouldBe false
            api.calls shouldBe emptyList()
        }

        test("a browser that is already running is found, and is not ours to claim") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p0", "ws://running/devtools/browser/p0")

            val resolution = resolver(api).resolve()

            resolution.url shouldBe "ws://running/devtools/browser/p0"
            resolution.alreadyRunning shouldBe true
            resolution.startedByUs shouldBe false
        }

        test("a browser this run started is ours to close") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p0", null)
            api.whenStartProfile("p0", "ws://started/devtools/browser/p0")

            val resolution = resolver(api).resolve()

            resolution.url shouldBe "ws://started/devtools/browser/p0"
            resolution.alreadyRunning shouldBe false
            resolution.startedByUs shouldBe true
        }

        test("a reachable Local API whose profile does not come up is reported, not papered over") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p0", null)
            api.whenStartProfile("p0", null)

            // The port scan would attach this profile to some browser — possibly one
            // that belongs to another profile. One anti-bot session wearing five
            // names is exactly what the port scan caused before.
            val failure =
                shouldThrow<IllegalStateException> {
                    resolver(api).resolve()
                }

            failure.message shouldBe
                "AdsPower Local API is reachable but profile 'p0' could not be started. " +
                "Open the profile in AdsPower, and check that its profile directory is " +
                "writable by the user running the AdsPower runtime."
        }

        test("a Local API that fails mid-call falls back to the port scan") {
            val api = FakeAdsPowerApi()
            api.apiAvailable = false
            api.checkActiveFailure = RuntimeException("the runtime went away")

            val resolution = resolver(api).resolve()

            // The scan is a worse answer than the API's, and it is the only one left.
            resolution.url shouldBe "ws://localhost:36931/devtools/browser/one"
            resolution.alreadyRunning shouldBe false
            resolution.startedByUs shouldBe false
        }
    })
