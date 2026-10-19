# Decision Log Digest

Auto-generated consolidated rules from `docs/decisions/`. The agent
reads this at session start. Per-decision entries
(`docs/decisions/YYYY-MM-DD-*.md`) are the human-facing reasoning. Refresh with:

```bash
./scripts/refresh-decisions-digest.sh
```

Markers that surface as Critical: `**Always**`, `**Never**`, `**MUST**`.

## Critical

- **Always** include `PR <N>` in subject for non-trivial changes that belong to a logical series. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)
- **Always** prefix commits with a type: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:`. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)
- **Never** use `fix:` for a pure refactor — `fix:` implies a bug was fixed; use `refactor:` for restructuring without behaviour change. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)
- **Never** skip domain unit tests for "quick fixes" — the <1 s execution time makes it free to run. _(from `2026-09-08-domain-coverage-pr4`)
- New code in `domain/pipeline/**` **MUST** include a unit test in `domain/src/test/kotlin/` that uses `FakeSideInterpreterRegistry`. _(from `2026-09-08-domain-coverage-pr4`)
- **Always** use `SideInterpreterRegistry` to register all side-effect handlers before calling `Pipeline.run()`. _(from `2026-09-08-functional-pipeline-refactor`)
- **Never** call `delay()` or `UUID.randomUUID()` inside a `Stage` body — Stage functions must remain referentially transparent. The `delayFn` (for retry back-off) and `idGen` (for pagination task IDs) live in the `Pipeline` coordinator and are injected via the `Pipeline` constructor; they are called by the coordinator, never by a Stage. _(from `2026-09-08-functional-pipeline-refactor`)
- **Never** emit a `Side` from a stage without a corresponding interpreter registered — the pipeline will throw at runtime. _(from `2026-09-08-functional-pipeline-refactor`)
- New code in `domain/pipeline/**` **MUST** be accompanied by a pure-Kotlin test in `domain/src/test/kotlin/` using a fake registry. _(from `2026-09-08-functional-pipeline-refactor`)
- **Always** call `server.baseUrl()` inside the test lambda (not at class instantiation), because the port is assigned on `start()` _(from `2026-09-08-integration-testing-pr7`)
- **Always** emit `Step.Retry` from download stage for retryable errors — `Step.Fail` bypasses `stageWithRetry` _(from `2026-09-08-integration-testing-pr7`)
- **Always** use `jdbc:sqlite:file::memory:?cache=shared` for in-memory SQLite in tests _(from `2026-09-08-integration-testing-pr7`)
- **Never** use `Random.Default` in retry tests — use seeded `Random` for deterministic back-off _(from `2026-09-08-integration-testing-pr7`)
- **Always** use `NoRetryKtorDownloader` in integration tests that verify retry. _(from `2026-09-08-retry-semantics-pr8`)
- **Never** emit `Step.Fail` for a condition that should be retried — use _(from `2026-09-08-retry-semantics-pr8`)
- **Never** register a live side-effect interpreter that the pipeline never calls. _(from `2026-09-08-retry-semantics-pr8`)
- **Never** write a `when` over a sealed interface when all branches are identical — access the common property directly _(from `2026-09-09-retry-backoff-collapse-pr9-5`)
- **Always** use `Retry.Database` (or the appropriate `Retry.*` variant) for transient storage errors — not `Step.Fail`. _(from `2026-09-09-save-stage-retry-pr11`)
- **Always** wrap external-state stages (DB, HTTP, file I/O) in a retry loop. _(from `2026-09-09-save-stage-retry-pr11`)
- **Always** discover test schemas from the classpath; **Never** enumerate migration _(from `2026-10-08-discover-test-migrations`)
- **Always** read migration bodies with `Class.getResourceAsStream`, not the classloader — _(from `2026-10-08-discover-test-migrations`)
- **Never** weaken `Migrations` to tolerate a layout it does not recognise. A suite that _(from `2026-10-08-discover-test-migrations`)
- **Always** break the input on purpose when adding a gate, and record the observed _(from `2026-10-08-gates-that-can-fail`)
- **Always** discover inputs rather than list them. `Migrations.discover()` in the _(from `2026-10-08-gates-that-can-fail`)
- **Never** treat a zero exit as evidence. Ask what was inspected; if the answer is "an _(from `2026-10-08-gates-that-can-fail`)
- **Always** interpret accumulated sides on failure — never lose diagnostic evidence _(from `2026-10-09-pipelinefailure-carries-sides`)
- **Always** configure `crawler.spider.rules` explicitly when deploying, even to the defaults, _(from `2026-10-09-wire-business-rules-into-filter`)
- **Never** widen a rule's boundary without adding the boundary case to a test. The comparison _(from `2026-10-09-wire-business-rules-into-filter`)
- **Always** reset `warm = false` when `getOrCreatePage()` opens a new page or when a profile _(from `2026-10-14-antibot-session-is-site-wide`)
- **Always** run one cold request per profile after a CDP reconnect; the flag is the only _(from `2026-10-14-antibot-session-is-site-wide`)
- **Never** remove the jitter expecting it to be free. It is ~0.35 s of the 0.66 s steady-state _(from `2026-10-14-antibot-session-is-site-wide`)
- **Always** pass the compose file's path explicitly to the role _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Always** treat a deploy as unproven until the container reports healthy. _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Never** give an image tag a `latest` fallback. A deploy that forgot to name a _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Never** put a credential in the `.env` that compose interpolates. Secrets go _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Always** keep request construction in `WbCatalogRequest`, not back inside the adapter. A _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** give a Kover-exercised DTO a required field that a single malformed API entry can _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** solve a coverage drop on untestable adapters by excluding them. Extract the logic _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Always** keep `target-concurrency` low enough to leave connections for the save path. Each _(from `2026-10-14-parallel-crawl-targets`)
- **Always** open as many AdsPower profiles as `target-concurrency`, or lower the setting. The _(from `2026-10-14-parallel-crawl-targets`)
- **Never** read a pool-exhaustion error as evidence that WB blocked us. It means no slot freed _(from `2026-10-14-parallel-crawl-targets`)
- **Never** set `target-concurrency` from a guess about how many browsers are open. The pool _(from `2026-10-14-parallel-crawl-targets`)
- **Always** inject `BrowserAutomation` via constructor for testability — never call `Playwright` directly in `ProfileState`. _(from `2026-10-17-browser-automation-interface`)
- **Always** inject `AdsPowerApi` via constructor for testability — never call `HttpURLConnection` directly in `ProfileState`. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)
- **Always** handle back-off delays at the coordinator level (inside `Pipeline.run()` or `processItems`) — never inside `async {}`. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)
- **Always** keep the startup gate green: `./check.sh` runs it as part of `tests:test`, and a _(from `2026-10-19-application-startup-gate`)
- **Always** supply `ADMIN_API_KEY` to the test JVM as an environment variable. The filter reads _(from `2026-10-19-application-startup-gate`)
- **Never** add a dependency that is needed only at runtime to `implementation` when the _(from `2026-10-19-application-startup-gate`)
- **Never** make the startup gate depend on a browser, the network or AdsPower. The `public` _(from `2026-10-19-application-startup-gate`)
- **Never** weaken the migration assertion to a fixed list of versions. The expected set is _(from `2026-10-19-application-startup-gate`)
- **Always** remove the accepted-findings entry in the same change that fixes the problem it _(from `2026-10-19-dependency-analysis-plugin`)
- **Always** report a dependency that the plugin flags but that we know to be false as an accepted _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** accept a finding that a line of source can close. A finding the plugin reports _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** add an entry to the accepted-findings list in `build.gradle.kts` without a reason in a _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** declare a dependency in `implementation` when only the runtime needs it. A runtime-only _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** exclude a family of artifacts to silence one member of it. The Spring regex hid _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** run `fixDependencies` in CI. It rewrites build scripts and cannot see runtime-only _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** switch off the `usedTransitiveDependencies` rule for a module to make the gate quiet. _(from `2026-10-19-dependency-analysis-plugin`)
- **Always** declare a dependency in the module that uses it. A dependency that reaches the _(from `2026-10-19-explicit-module-boundary`)
- **Always** name the application class in the startup gate. The `tests` module needs the _(from `2026-10-19-explicit-module-boundary`)
- **Always** use `runtimeOnly` for a dependency that is loaded but never named in source — a _(from `2026-10-19-explicit-module-boundary`)
- **Never** accept a finding about a project dependency that the code can name. A finding _(from `2026-10-19-explicit-module-boundary`)
- **Never** duplicate a dependency in a consumer to make it compile. If the consumer needs the _(from `2026-10-19-explicit-module-boundary`)
- **Never** let a module's dependencies be the application's dependencies by accident. The _(from `2026-10-19-explicit-module-boundary`)
- **Never** use `implementation` for a type that appears in a public signature. It belongs in _(from `2026-10-19-explicit-module-boundary`)
- **Always** keep the CI and the Docker image on the same JDK version as the _(from `2026-10-19-jdk-toolchain`)
- **Always** set the JDK through the toolchain in the root build's `subprojects { }` _(from `2026-10-19-jdk-toolchain`)
- **Never** bump one surface alone. The version, `ci.yml`, `Dockerfile.app` and _(from `2026-10-19-jdk-toolchain`)
- **Never** pin `org.gradle.java.home` in a committed `gradle.properties`. A path that _(from `2026-10-19-jdk-toolchain`)
- **Always** compare the artifact that ships with the classpath the build _(from `2026-10-19-runtime-classpath-gate`)
- **Always** read `jdeps` output rather than its exit code. It reports missing _(from `2026-10-19-runtime-classpath-gate`)
- **Never** ship a class twice. A class that exists both loose and in a jar can _(from `2026-10-19-runtime-classpath-gate`)
- **Never** trust `gradle dependencies` output as a classpath. It collapses _(from `2026-10-19-runtime-classpath-gate`)

## Per-tag

### `ansible`

- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `antibot`

- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_

### `architecture`

- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_

### `arrow`

- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_

### `behaviour`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_

### `browser`

- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_

### `bug-fix`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_

### `build`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_
- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `ci`

- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_

### `cleanup`

- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `retryDelayMs()` reduced from 19 lines to 11 lines _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_

### `code-quality`

- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_

### `concurrency`

- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_

### `configuration`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_

### `conventions`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_

### `coverage`

- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_

### `database`

- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_

### `dead-code`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_

### `dependencies`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_

### `deployment`

- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `docker`

- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `domain`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_

### `either`

- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `flyway`

- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_

### `G10`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_

### `gates`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_

### `gate`

- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `infrastructure`

- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `packaging`

- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `performance`

- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_

### `pipeline`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `retryDelayMs()` reduced from 19 lines to 11 lines _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_

### `PR-11-5`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `pr-13`

- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_

### `refactoring`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `runtime`

- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `scheduler`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_

### `side-effects`

- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `spring`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_

### `testing`

- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_

### `workflow`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_


## Index (slug → tags)

- `2026-09-08-conventional-commits-and-cleanup-prs` — workflow  conventions
- `2026-09-08-domain-coverage-pr4` — testing  domain  pipeline
- `2026-09-08-functional-pipeline-refactor` — pipeline  architecture  domain
- `2026-09-08-integration-testing-pr7` — testing  pipeline  architecture
- `2026-09-08-retry-semantics-pr8` — pipeline  bug-fix
- `2026-09-09-arrow-either-pr20` — arrow  pipeline  code-quality
- `2026-09-09-concurrency-wiring-pr17` — pipeline  configuration  architecture
- `2026-09-09-dead-code-purge-pr19` — dead-code  domain  code-quality
- `2026-09-09-dead-code-purge-pr9` — cleanup  architecture
- `2026-09-09-pipeline-extraction-pr11-5` — refactoring  pipeline  PR-11-5
- `2026-09-09-retry-backoff-collapse-pr9-5` — cleanup  pipeline
- `2026-09-09-save-stage-retry-pr11` — pipeline  bug-fix  G10
- `2026-09-09-scheduler-wireup-pr18` — scheduler  spring  architecture
- `2026-09-09-test-module-boundary-pr10` — testing  architecture
- `2026-10-08-discover-test-migrations` — testing  architecture  database
- `2026-10-08-gates-that-can-fail` — testing  ci  architecture
- `2026-10-09-pipelinefailure-carries-sides` — pipeline  either  side-effects
- `2026-10-09-wire-business-rules-into-filter` — pipeline  behaviour  configuration
- `2026-10-14-antibot-session-is-site-wide` — infrastructure  browser  antibot  performance
- `2026-10-14-deploy-crawler-as-registry-image` — deployment  infrastructure  ansible  docker
- `2026-10-14-extract-browser-request-logic-for-testability` — infrastructure  testing  coverage  browser
- `2026-10-14-parallel-crawl-targets` — infrastructure  concurrency  browser  performance
- `2026-10-14-pipeline-purity-idgen` — pipeline  refactoring  architecture
- `2026-10-17-browser-automation-interface` — browser  architecture  testing
- `2026-10-17-browserpool-testability-adspower-api-interface` — browser  architecture  testing
- `2026-10-17-coordinator-extraction` — pipeline  architecture  pr-13
- `2026-10-17-pipeline-purity-delayfn-extraction` — pipeline  architecture  concurrency
- `2026-10-17-stage-data-class-dsl` — pipeline  architecture  pr-13
- `2026-10-19-application-startup-gate` — testing  gates  spring  database  flyway
- `2026-10-19-dependency-analysis-plugin` — build  dependencies  gates  ci
- `2026-10-19-explicit-module-boundary` — build  architecture  dependencies  spring
- `2026-10-19-jdk-toolchain` — build  toolchain  ci  docker
- `2026-10-19-runtime-classpath-gate` — build  runtime  gate  packaging

## Active entries

- `2026-09-08-conventional-commits-and-cleanup-prs` — Conventional Commits + PR-numbered cleanup series
- `2026-09-08-domain-coverage-pr4` — Domain coverage — Retry/StageFailure/Pipeline EmptyPage tests
- `2026-09-08-functional-pipeline-refactor` — Functional pipeline refactor — Stage/Side/Interpreter registry
- `2026-09-08-integration-testing-pr7` — Integration testing — SQLite + Java HttpServer
- `2026-09-08-retry-semantics-pr8` — Retry signal must be Step.Retry, not Step.Fail
- `2026-09-09-arrow-either-pr20` — Arrow Either.map side-effect analysis — PR 20 conclusion
- `2026-09-09-concurrency-wiring-pr17` — Wire Spider.concurrency into Pipeline.run() — close 4-layer config gap
- `2026-09-09-dead-code-purge-pr19` — Dead code purge — DomainError isStopped/isDrop + Pipeline.processItems task parameter
- `2026-09-09-dead-code-purge-pr9` — Dead code purge — PR 9 cluster C
- `2026-09-09-pipeline-extraction-pr11-5` — PR 11.5 — Pipeline.run() extraction — behaviour-preserving refactor
- `2026-09-09-retry-backoff-collapse-pr9-5` — Collapse identical Retry back-off branches
- `2026-09-09-save-stage-retry-pr11` — Save stage retry — G10: transient DB blip must not fail the entire crawl
- `2026-09-09-scheduler-wireup-pr18` — Replace installCrawler with Spring @Scheduled bean — wire scheduler lifecycle
- `2026-09-09-test-module-boundary-pr10` — Test module boundary — where tests live
- `2026-10-08-discover-test-migrations` — Test schemas are discovered, never listed — and refuse to guess
- `2026-10-08-gates-that-can-fail` — A gate that cannot fail is worse than no gate — and that is a shared rule
- `2026-10-09-pipelinefailure-carries-sides` — Pipeline failure carries accumulated sides
- `2026-10-09-wire-business-rules-into-filter` — Business rules are wired into the filter stage, and filtering is now on by default
- `2026-10-14-antibot-session-is-site-wide` — Anti-bot warm-up is amortized per profile, not per request
- `2026-10-14-deploy-crawler-as-registry-image` — Deploy the crawler as a registry image, built on the operator's machine
- `2026-10-14-extract-browser-request-logic-for-testability` — Browser fetch extracts request construction instead of excluding itself from coverage
- `2026-10-14-parallel-crawl-targets` — Crawl targets run in parallel; the browser pool is what actually bounds it
- `2026-10-14-pipeline-purity-idgen` — Pipeline idGen injectable; delay() in stageWithRetry is intentional
- `2026-10-17-browser-automation-interface` — BrowserAutomation interface — extract Playwright CDP lifecycle from BrowserPool.ProfileState
- `2026-10-17-browserpool-testability-adspower-api-interface` — BrowserPool testability — AdsPowerApi interface extraction
- `2026-10-17-coordinator-extraction` — Extract Coordinator class from Pipeline.run
- `2026-10-17-pipeline-purity-delayfn-extraction` — Extract delayFn from async dispatcher into coordinator-level retry loop
- `2026-10-17-stage-data-class-dsl` — Stage data class + StageDSL for readable stage construction
- `2026-10-19-application-startup-gate` — The application must be startable, and something must prove it
- `2026-10-19-dependency-analysis-plugin` — Dependency drift is a gate, not a reviewer's job
- `2026-10-19-explicit-module-boundary` — The boundary between the crawler and the application is declared, not implied
- `2026-10-19-jdk-toolchain` — The JDK version is one number in one place, and the toolchain provisions itself
- `2026-10-19-runtime-classpath-gate` — The image is compared with the classpath the build resolved
