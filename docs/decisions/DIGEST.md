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
- **Never** emit `Step.Fail` for a condition that should be retried — use `Step.Retry(Retry.*)`. _(from `2026-09-08-retry-semantics-pr8`)
- **Never** register a live side-effect interpreter that the pipeline never calls. Use `NoOp*` variants that document why they exist. _(from `2026-09-08-retry-semantics-pr8`)
- **Never** write a `when` over a sealed interface when all branches are identical — access the common property directly _(from `2026-09-09-retry-backoff-collapse-pr9-5`)
- **Always** use `Retry.Database` (or the appropriate `Retry.*` variant) for transient storage errors — not `Step.Fail`. _(from `2026-09-09-save-stage-retry-pr11`)
- **Always** wrap external-state stages (DB, HTTP, file I/O) in a retry loop. Use `stageWithRetry(save, retryPolicy)` or a manual loop inside `saveOne`. _(from `2026-09-09-save-stage-retry-pr11`)
- **Always** discover test schemas from the classpath; **Never** enumerate migration filenames in a test fixture. An unapplied migration cannot be asserted on, so the only defence is not keeping a list to forget. _(from `2026-10-08-discover-test-migrations`)
- **Always** read migration bodies with `Class.getResourceAsStream`, not the classloader — the leading slash difference fails silently as `null`. _(from `2026-10-08-discover-test-migrations`)
- **Never** weaken `Migrations` to tolerate a layout it does not recognise. A suite that quietly applies nothing is the defect this file exists to prevent. _(from `2026-10-08-discover-test-migrations`)
- **Always** break the input on purpose when adding a gate, and record the observed failure in the commit message. `check-gate-wiring.py --self-test` and the controls in `scripts/tests/test_gate_scripts.py` are this rule applied to the gates themselves. _(from `2026-10-08-gates-that-can-fail`)
- **Always** discover inputs rather than list them. `Migrations.discover()` in the crawler's `tests/` and the migration glob in `tests/global-setup.ts` are the same decision, made twice, days apart. _(from `2026-10-08-gates-that-can-fail`)
- **Never** treat a zero exit as evidence. Ask what was inspected; if the answer is "an empty list" or "nothing there to find", the check has not run. _(from `2026-10-08-gates-that-can-fail`)
- **Always** interpret accumulated sides on failure — never lose diagnostic evidence _(from `2026-10-09-pipelinefailure-carries-sides`)
- **Always** configure `crawler.spider.rules` explicitly when deploying, even to the defaults, so the effective policy is visible in the environment rather than in a code default. _(from `2026-10-09-wire-business-rules-into-filter`)
- **Never** widen a rule's boundary without adding the boundary case to a test. The comparison is `priceKopecks < min` and `priceKopecks > max`, so a price exactly equal to a limit is **kept**. This is pinned by tests because "at the limit" is the case most likely to be assumed the other way. _(from `2026-10-09-wire-business-rules-into-filter`)
- **Always** reset `warm = false` when `getOrCreatePage()` opens a new page or when a profile is closed. A warm flag on a freshly connected page makes the API return empty results and the crawl silently saves nothing — the same symptom as the original bug. _(from `2026-10-14-antibot-session-is-site-wide`)
- **Always** run one cold request per profile after a CDP reconnect; the flag is the only thing standing between a new profile and a silent zero-item crawl. _(from `2026-10-14-antibot-session-is-site-wide`)
- **Never** remove the jitter expecting it to be free. It is ~0.35 s of the 0.66 s steady-state budget, but it is what keeps the request pattern from looking mechanical. _(from `2026-10-14-antibot-session-is-site-wide`)
- **Always** pass the compose file's path explicitly to the role (`wbparser_compose_src`); it is owned by this repository and there is no safe default for where somebody's checkout lives. _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Always** treat a deploy as unproven until the container reports healthy. A restart is not a deploy. _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Never** give an image tag a `latest` fallback. A deploy that forgot to name a tag ships whatever the registry considers newest. _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Never** put a credential in the `.env` that compose interpolates. Secrets go in `app.env` and are passed through `env_file`. _(from `2026-10-14-deploy-crawler-as-registry-image`)
- **Always** keep request construction in the `infra/http` request objects, not back inside the adapter. A wrong `dest` or page number answers HTTP 200 with an empty `products` array, so the only defence is a test on the value that gets built. (Since 2026-10-20 those objects are `WbCatalogRequest` — the crawl URL, `WbCatalogApi` — the internal API call, and `WbCatalogFetchScript` — the script; see the ADR on splitting by decision.) _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** give a Kover-exercised DTO a required field that a single malformed API entry can omit. Default it and let the item-level guard reject it. _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** solve a coverage drop on untestable adapters by excluding them. Extract the logic that deserves testing and leave the rest measured. _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Always** keep `target-concurrency` low enough to leave connections for the save path. Each in-flight target holds a pooled connection while it waits for a browser slot. _(from `2026-10-14-parallel-crawl-targets`)
- **Always** open as many AdsPower profiles as `target-concurrency`, or lower the setting. The pool queues beyond the browser count rather than failing, so an over-large value looks like it is working while delivering one session's worth of throughput. _(from `2026-10-14-parallel-crawl-targets`)
- **Never** read a pool-exhaustion error as evidence that WB blocked us. It means no slot freed in time. _(from `2026-10-14-parallel-crawl-targets`)
- **Never** set `target-concurrency` from a guess about how many browsers are open. The pool warns when the two disagree; that warning is the diagnostic, not noise. _(from `2026-10-14-parallel-crawl-targets`)
- **Always** inject `BrowserAutomation` via constructor for testability — never call `Playwright` directly in `ProfileState`. _(from `2026-10-17-browser-automation-interface`)
- **Always** inject `AdsPowerApi` via constructor for testability — never call `HttpURLConnection` directly in `ProfileState`. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)
- **Always** handle back-off delays at the coordinator level (inside `Pipeline.run()` or `processItems`) — never inside `async {}`. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)
- **Always** read targets through `TargetQueries.kt`. The admin API has no SQL of its own for `crawl_targets` any more. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)
- **Never** add a second `ResultSet` → DTO mapping for a table the repository already maps. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)
- **Always** consult the Local API before falling back to the port scan, and **never** fall back while the API is reachable. _(from `2026-10-19-adspower-api-first-browser-resolution`)
- **Never** run AdsPower under `sudo`. A profile created by such a run keeps a `Preferences` file and a cache that belong to root, and AdsPower then reports that it could not read the settings. The fix is a rename of the file, and the pre-flight check says so by name. _(from `2026-10-19-adspower-api-first-browser-resolution`)
- **Always** reach AdsPower through the host's loopback. In Docker that means `network_mode: host` (or an equivalent host-network setup); a bridge network silently breaks every profile. _(from `2026-10-19-adspower-local-api-port-discovery`)
- **Never** treat a failing `checkActiveV2()` as "the browser is gone". It may mean the port moved. `RealAdsPowerApi` now re-resolves on failure for exactly this reason. _(from `2026-10-19-adspower-local-api-port-discovery`)
- **Always** carry the reason a run was skipped to the operator: a scheduled crawl that quietly did nothing is indistinguishable from one that had nothing to do. _(from `2026-10-19-advisory-lock-timeout`)
- **Always** state which one a timeout bounds. "Lock acquisition timed out" and "the crawl took too long" are different facts, and the second one is not the lock's business. _(from `2026-10-19-advisory-lock-timeout`)
- **Always** take and release an advisory lock on the same connection. _(from `2026-10-19-advisory-lock-timeout`)
- **Never** treat a timeout of zero as "no waiting". Zero means wait forever, and that is the documented contract of `crawler.lock.timeout-minutes`. _(from `2026-10-19-advisory-lock-timeout`)
- **Never** wrap the body of a lock-scoped block in the acquisition timeout. A timeout that bounds the wait must not bound the work. _(from `2026-10-19-advisory-lock-timeout`)
- **Always** keep the startup gate green: `./check.sh` runs it as part of `tests:test`, and a failure there means the application does not start. _(from `2026-10-19-application-startup-gate`)
- **Always** supply `ADMIN_API_KEY` to the test JVM as an environment variable. The filter reads it with `System.getenv`; a system property is not read, and the failure looks like a configuration error rather than a missing variable. _(from `2026-10-19-application-startup-gate`)
- **Never** add a dependency that is needed only at runtime to `implementation` when the compiler never sees it — declare it as `runtimeOnly`, so the plugin and the compiler can both reason about it. _(from `2026-10-19-application-startup-gate`)
- **Never** make the startup gate depend on a browser, the network or AdsPower. The `public` profile exists for that reason: the crawler must not start in a unit test. _(from `2026-10-19-application-startup-gate`)
- **Never** weaken the migration assertion to a fixed list of versions. The expected set is discovered from the classpath, so a new migration is covered the day it is added. _(from `2026-10-19-application-startup-gate`)
- **Always** check the effective rights of the catalog role, not the grants that were issued to it. _(from `2026-10-19-catalog-read-api-contract`)
- **Always** keep the catalog's pool separate from the crawler's. The crawl is the component that loses data when it runs out of connections. _(from `2026-10-19-catalog-read-api-contract`)
- **Always** read the catalog through the API, and let the API own the SQL. The moment the storefront writes SQL again, the schema copy returns with it. _(from `2026-10-19-catalog-read-api-contract`)
- **Always** rotate the catalog key on a schedule, and rotate it immediately if it appears in any log. _(from `2026-10-19-catalog-read-api-contract`)
- **Never** change the contract from the storefront's side. The direction is always crawler to storefront, so that two unmerged changes cannot disagree. _(from `2026-10-19-catalog-read-api-contract`)
- **Never** deploy the API publicly. The route stays closed at the proxy, and the check that it is closed runs before the first deployment, not after. _(from `2026-10-19-catalog-read-api-contract`)
- **Never** give the storefront database credentials again. A fallback that reads the database "just in case" is a second code path that only runs when something is broken, which is exactly when it is least likely to be tested. _(from `2026-10-19-catalog-read-api-contract`)
- **Never** weaken `wb_catalog_ro` to make something work. If the catalog needs a column the role cannot see, that is a decision about the read set, made deliberately and recorded. _(from `2026-10-19-catalog-read-api-contract`)
- **Always** give every module with tests a floor in `config/gates/coverage-floor.txt` and a row in `config/gates/test-runs-floor.txt`. A module nobody measures is a module nobody watches. _(from `2026-10-19-coverage-floor-gate`)
- **Always** keep the coverage floor in `config/gates/coverage-floor.txt` and let `scripts/check-coverage.py` enforce it. A second copy of the number in a build script is a second claim about it. _(from `2026-10-19-coverage-floor-gate`)
- **Always** keep the floor at least a percentage point below the measurement. A floor that sits on the measurement is tripped by any edit that adds an uncovered line, and a floor one line above it is a rounding error. _(from `2026-10-19-coverage-floor-gate`)
- **Always** raise the floor in the same commit that raises the coverage, with `./scripts/raise-kover-floor.py`. _(from `2026-10-19-coverage-floor-gate`)
- **Never** lower a coverage floor without `--allow-drop '<reason>'`, and never without a reason in the commit that does it. _(from `2026-10-19-coverage-floor-gate`)
- **Never** rewrite a floor file from a partial run. If the results are not complete, the floor stays as it was. _(from `2026-10-19-coverage-floor-gate`)
- **Never** treat a missing coverage report as zero coverage. It is an absent measurement, and the gate says so with a different exit code. _(from `2026-10-19-coverage-floor-gate`)
- **Always** write the heartbeat while a crawl runs. A job row that never heartbeats is indistinguishable from a job whose process died. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)
- **Always** remove the accepted-findings entry in the same change that fixes the problem it describes. The list may only shrink **in substance**: an entry that describes a structural property of a dependency — a starter is a pom that carries no classes, an aggregator brings parts that cannot be declared apart — cannot be removed by any change in this repository, so it stays; what must never happen is a new entry hiding a finding that a line of source could close. _(from `2026-10-19-dependency-analysis-plugin`)
- **Always** report a dependency that the plugin flags but that we know to be false as an accepted finding with a reason — never by deleting the report, weakening the severity, or turning the gate off. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** accept a finding that a line of source can close. A finding the plugin reports about a project dependency is usually a dependency nobody named. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** add an entry to the accepted-findings list in `build.gradle.kts` without a reason in a comment next to it. The list is a record of known problems, not a place to make the gate quiet. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** declare a dependency in `implementation` when only the runtime needs it. A runtime-only dependency belongs in `runtimeOnly`; that is what the plugin asks for, and it is the difference between a dependency the compiler can check and one it cannot. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** exclude a family of artifacts to silence one member of it. The Spring regex hid nine real libraries from the gate; naming the starters instead of the family brought them back, and the findings that returned were closed by declaring the dependency, not by widening the list. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** run `fixDependencies` in CI. It rewrites build scripts and cannot see runtime-only dependencies, so it can remove one that the application needs at startup. It is a manual tool, and `./check.sh` must pass afterwards. _(from `2026-10-19-dependency-analysis-plugin`)
- **Never** switch off the `usedTransitiveDependencies` rule for a module to make the gate quiet. The rule was temporarily off for `tests` while the module boundary was implicit; it is on for every module now, and the fix for a finding is to declare the dependency, not to silence the rule. _(from `2026-10-19-dependency-analysis-plugin`)
- **Always** declare a dependency in the module that uses it. A dependency that reaches the runtime classpath through another module is invisible to the compiler and to the plugin. _(from `2026-10-19-explicit-module-boundary`)
- **Always** name the application class in the startup gate. The `tests` module needs the `app` project for its resources, and an unnamed dependency on a module is invisible to bytecode analysis — the gate called it unused, and the only fix was an accepted finding. Naming the class says what the suite boots and makes the dependency visible. _(from `2026-10-19-explicit-module-boundary`)
- **Always** use `runtimeOnly` for a dependency that is loaded but never named in source — a JDBC driver, an SLF4J binding, Flyway. It says what is true, and the plugin agrees. _(from `2026-10-19-explicit-module-boundary`)
- **Never** accept a finding about a project dependency that the code can name. A finding that a single line of source removes is not a finding to accept. _(from `2026-10-19-explicit-module-boundary`)
- **Never** duplicate a dependency in a consumer to make it compile. If the consumer needs the type, the producer exposes it with `api`. _(from `2026-10-19-explicit-module-boundary`)
- **Never** let a module's dependencies be the application's dependencies by accident. The migrations and the Flyway configuration are in `app`, so Flyway is in `app`. _(from `2026-10-19-explicit-module-boundary`)
- **Never** use `implementation` for a type that appears in a public signature. It belongs in `api`, or the signature has to change. _(from `2026-10-19-explicit-module-boundary`)
- **Always** log the exception, not only its message, at the point where the exception is turned into a domain error. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Always** write through one statement per row. A multi-row `BatchUpsertStatement` built with `set(...)` + `addBatch()` is rejected by Postgres in this schema. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Never** let a logging path stand between a failure and its report. If a failure cannot be explained from the application's own output, that is a defect in the reporting path, not a detail to work around. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Always** load through `just catalog::load-confirm` (or the script directly). It is idempotent now: running it twice changes nothing and reports "132 updated, 0 inserted". _(from `2026-10-19-idempotent-target-load-and-root-categories`)
- **Never** reintroduce a blind `INSERT` into the loader. A load that creates a second copy of every target doubles the crawler's work. _(from `2026-10-19-idempotent-target-load-and-root-categories`)
- **Always** keep the CI and the Docker image on the same JDK version as the toolchain. The build may run on a downloaded JDK locally, but CI and the image must not silently drift to another version. _(from `2026-10-19-jdk-toolchain`)
- **Always** set the JDK through the toolchain in the root build's `subprojects { }` block. Never repeat the version in a module script — a copy is a place to forget. _(from `2026-10-19-jdk-toolchain`)
- **Never** bump one surface alone. The version, `ci.yml`, `Dockerfile.app` and `Dockerfile.debug` change in the same commit; `./check.sh` and a Docker build are the two halves of the verification. _(from `2026-10-19-jdk-toolchain`)
- **Never** pin `org.gradle.java.home` in a committed `gradle.properties`. A path that exists on one machine cannot exist on another, and the failure looks like a Gradle bug rather than a configuration error. _(from `2026-10-19-jdk-toolchain`)
- **Always** build the jar before `docker compose up` — the debug image does not build it. `just app::run-docker` does it for you. _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** add a second local compose file. There is one stack, and it is `deploy/docker-compose.debug.yml`; the root file includes it. _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** assume a relative path in a compose file is relative to where you ran the command. It is relative to the compose file. _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** mount a directory over `/app/app.jar`. _(from `2026-10-19-local-debug-stack-repairs`)
- **Always** compare the artifact that ships with the classpath the build resolved. A build that produced a jar is not evidence that the jar is right. _(from `2026-10-19-runtime-classpath-gate`)
- **Always** read `jdeps` output rather than its exit code. It reports missing dependencies and still exits zero. _(from `2026-10-19-runtime-classpath-gate`)
- **Never** ship a class twice. A class that exists both loose and in a jar can come from either, and only one of them was built. _(from `2026-10-19-runtime-classpath-gate`)
- **Never** trust `gradle dependencies` output as a classpath. It collapses repeated subtrees; a gate that compares against it compares against a lie. _(from `2026-10-19-runtime-classpath-gate`)
- **Always** change the model and the migration in the same commit when a column changes. The gate is what makes the order irrelevant — but only if both change. _(from `2026-10-19-schema-drift-gate`)
- **Always** write instants through a zoned column type. A naive timestamp in a `TIMESTAMPTZ` column is a silent shift by the machine's offset, and the test suite cannot see it on a UTC host. _(from `2026-10-19-schema-drift-gate`)
- **Never** change the schema to match the model. The model is the description; the schema is the contract that production runs. _(from `2026-10-19-schema-drift-gate`)
- **Never** write a table with hand-written SQL while the model describes the rest of the schema. A table without a description is a table the drift gate cannot see, which makes it the one place a drift hides. _(from `2026-10-19-schema-drift-gate`)
- **Always** load the dictionary before a crawl that is expected to produce names: `just catalog::load-subjects`. Without it the crawl still works and the name columns stay null. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)
- **Never** write a name that did not come from the dictionary. A null name is a missing fact; a made-up one is a false fact. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)
- **Always** compare the score the API holds with the commit this run computed, not merely with "some score". A stale score that used to pass the check is the imposter-commit bug in another shape. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)
- **Always** keep the results upload unconditional. The score is delivered to code scanning on every run, and that is where a reader should look. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)
- **Never** report a publication that was not rejected as rejected. Say what the API answered: `404` is "no score for this repository", not "the publication was rejected". _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)
- **Never** treat a red gate that nobody can act on as a gate. If a check cannot be made to pass by any change in this repository, it either becomes a warning with a named reason, or it is removed. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)
- **Always** check that a publication reached the API. A green run that published nothing is the failure this repository cares most about. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** declare a top-level `permissions:` in every workflow. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** pin an action to a commit, and verify that the pin is a commit of the action's own repository. The checker is the verification. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Never** pass an input an action does not declare. It is a warning on every run and a guard on none. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Never** read a pin as a commit because it is forty characters long. A tag object's SHA is forty characters too. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** прогонять `enrich_catalog_queries.py` перед загрузкой каталога в БД. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Always** сигналить о таком ответе метрикой и предупреждением: протухший токен виден только через этот сигнал. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** грузить в БД цель без `wb_search_query`. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** сохранять ответ с `catalog_type = "presets"`, даже если он выглядит как обычная страница товаров. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** считать такую страницу пройденной страницей каталога: счётчик страниц должен показывать, сколько каталога реально прочитано. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Always** cache Gradle in the CI. Six jobs pulling the same graph from the same registry at the same moment is a race, and the race is lost at random. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Always** treat "the schedule did not run" as a failure. The watchdog reports it, and the gate checks that the watchdog is there. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Never** count a manual run as proof that the schedule works. The question is about the schedule. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Never** let a watchdog open a second issue about the same thing. One open issue is the whole design. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Always** отображать `NetworkError.isAntibot` в `Retry.Antibot`. Серверная ошибка и отказ антибота имеют разные back-off'ы, и путать их — значит повторять отказ внутри блокировки. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** передавать `waiter` в `Pipeline` явно: значение по умолчанию у шага ожидания означал бы, что тест может уснуть на десять секунд там, где проверяет отказ. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** считать долю отказов как `crawl.antibot.responses / crawl.requests`. Без знаменателя числитель ничего не значит. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** вызывать `delay()` в домене. Ожидание — через `Waiter`. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** добавлять новые значения `reason` свободным текстом: набор закрытый. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** регистрировать интерпретатор лога вручную: отсутствие лога — это не повод упасть в момент ошибки. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** держать темп ниже порога WB; «сначала попробуем быстро» — это ровно то, что приводит к 498. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Always** помечать профиль на паузу при антиботе, а не просто повторять тот же запрос. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Never** тратить весь бюджет попыток на запросы к открытой цепи: пауза и back-off должны пережить окно блокировки. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Always** держать настройки `browser-idle-timeout-ms`, `browser-janitor-interval-ms` и `close-browsers-on-exit` в `application.yml` с объяснением: это единственное место, где видно, что браузеры закрываются. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** закрывать простаивающие профили: браузер, которым никто не пользуется, не нужен никому. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** останавливать браузер в AdsPower, а не только закрывать CDP-соединение: соединение — наше, браузер — процесс, и он переживает наше отсоединение. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Never** оставлять браузер открытым только потому, что краул его не открывал, а подключился: так утечка становится постоянной. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Never** ронять выключение из-за того, что AdsPower не ответил. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** treat a value that is older than ten minutes as "as of ten minutes ago", not as "now". The storefront shows the count it was given; it does not recompute it, and it cannot. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)
- **Never** run two crawler instances behind one address while the aggregates are cached in the process. The two would answer with different numbers for the same question, and neither would be wrong. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)
- **Always** define the pipeline once and call it. The schedule is an entry point, not a property of the pipeline: a schedule inside the pipeline is a copy of the entry point, and the copy is what goes stale. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** discover the modules from `settings.gradle.kts` rather than listing them. A module that is not corrected is a module whose findings stay. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** give every job of a callable pipeline its own `permissions`. The caller's grant is a ceiling, not a gift: a job without a declaration inherits all of it, and the ceiling is validated statically, against jobs that never run. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** keep `security-events: write` on CodeQL and `id-token: write` on Scorecard. Without them the analysis runs and nobody sees the result, which is the same as not running it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** keep the CI workflows wired, and keep the gate that proves it. A workflow that silently stops running is indistinguishable from one that never ran. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** pin an action to a commit that exists. `ossf/scorecard-action@v2` does not resolve: the published tags stop at v2.5.0 and there is no `v2`, so a workflow that names it fails before it starts — on the first scheduled run, in the dark. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** run the corrector as one Gradle invocation per module and one task per invocation. A task option given once reaches only the last matching task. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** take the report from the run's API. A hand-written list of the pipeline's jobs is a mirror, and the mirror goes stale silently. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** give a health check the permissions of a release *by accident*. The nightly does not push an image, and the registry write reaches only the job that would have pushed it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** let the auto-fix decide whether the tree is acceptable. It rewrites what it can and reports what it cannot; `ktlintCheck` and `detekt` remain the gate. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** push the auto-fix onto a branch somebody is working on. The corrections are proposed; the human decides. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** put the corrector in the gate registry. It is not a gate: it cannot fail a build, it rewrites the tree instead of judging it, and the verification loop (`check.sh`) must stay a loop that does not modify the repository it verifies. `ktlintCheck` and `detekt` are the gates; the corrector is their colleague, not their replacement. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** report a scheduled failure anywhere else than the issue. A red cron run that nobody reads is not a report. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** добавлять новое поле, а не переименовывать старое, когда меняется только способ вычисления: старое остаётся и объясняет, что оно теперь значит. _(from `2026-10-20-contract-versions-are-paths`)
- **Always** объявлять устаревание в самом контракте (`deprecated: true`) и писать, в какой версии поле исчезнет. _(from `2026-10-20-contract-versions-are-paths`)
- **Always** проверять, что потребитель, который знает только текущую версию, продолжает работать: добавление поля обязано быть безопасным, а изменение смысла — нет. _(from `2026-10-20-contract-versions-are-paths`)
- **Never** менять смысл существующего поля в том же контракте: это ломающее изменение, и оно получает новый путь версии. _(from `2026-10-20-contract-versions-are-paths`)
- **Always** объявлять merged report в root-проекте: без него отчёт модуля `tests` остаётся пустым, а гейт не видит ни одного интеграционного теста. _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** писать интеграционный тест рядом с кодом, который он проверяет, а не в отдельном модуле «интеграционных» тестов. _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** поднимать floor покрытия тем же коммитом, который поднял покрытие (`./scripts/raise-kover-floor.py`). _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Never** дублировать DDL в тестах: схема строится из модели, соответствие миграциям проверяется отдельным тестом на дрейф. _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Never** считать, что отчёт модуля содержит классы зависимостей: он содержит только свои. Чужой код попадает в отчёт через merging module, а не через фильтр. _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** bind a transaction to the DataSource it belongs to — `transaction(exposedOnce())` — and never to "the database that happened to be registered last". Exposed's default database is the last one created, so a bare `transaction { }` writes to whichever DataSource was connected last: in a process that holds two, that is a race with the wrong database as its outcome. _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Always** write `crawl_jobs` timestamps through Exposed with an `OffsetDateTime`. _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Never** use `java.sql.Timestamp` for a `TIMESTAMPTZ` column: it is a naive local time, and Postgres interprets it in the session time zone. _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Always** считать `max_depth` глубиной по дереву категорий, а не лимитом страниц. _(from `2026-10-20-pagination-does-not-increase-depth`)
- **Never** использовать `max_depth` для ограничения числа страниц каталога: лимит страниц — это `known_pages_limit` (у цели) и `max_pages_per_catalog` (у раннера). _(from `2026-10-20-pagination-does-not-increase-depth`)
- **Always** задавать каденцию через `CRAWLER_SCHEDULE_FRESHNESS_MINUTES` (в коде — `crawler.schedule.freshness-minutes`). _(from `2026-10-20-schedule-cadence-single-parameter`)
- **Never** считать cron выражением каденции: это тик. _(from `2026-10-20-schedule-cadence-single-parameter`)
- **Always** держать в `wb_subjects` полный словарь; он обновляется при старте, а не при первом крауле. _(from `2026-10-20-subject-dictionary-loaded-at-startup`)
- **Never** выдумывать имя предмета, если id нет в словаре: `catalog_name` остаётся пустым, а метрика `crawl.items.without_category_name` показывает масштаб расхождения. _(from `2026-10-20-subject-dictionary-loaded-at-startup`)
- **Always** keep a data class for the knobs of a class with many parameters. Detekt does not count data-class constructors, so the grouping costs nothing at the gate and buys a constructor that can be read. _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** keep the request construction of the browser fetch in the `infra/http` request objects, not back inside the adapter. A wrong `dest` or page number answers HTTP 200 with an empty `products` array, so the only defence is a test on the value that gets built. _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** split a class by the decision it holds, not by the layer it sits in. A new unit gets a name that says what it decides. _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Never** give a split unit a second copy of state the original owned. The batch shares the coordinator; the API builder asks the URL algebra for the catalog id. _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Never** move a piece of behaviour into a collaborator without carrying the state it depends on. A split that changes what a piece knows is a behaviour change wearing a refactor's clothes. _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** build an index concurrently, before the migration that names it, and let the migration find it already there. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Always** carry the credential an image needs before deploying that image. A deploy that produces a container which does not start is a failure that looks like a crash, not like a missing configuration. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Always** verify the API from outside, with the key, before the storefront is pointed at it. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Always** write a cutover as a procedure, with the way back for every step that changes something, before the first step is taken. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Never** close the crawler's route to gate the catalog. The key is the gate. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Never** roll the production schema back under the crawler. The migrations are additive and the history is the record; a rollback is not reversible. _(from `2026-10-20-the-catalog-is-opened-by-a-procedure`)
- **Always** измерять `known_pages_limit` до того, как его выставлять. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** перезамерять каталог, а не достраивать лимит вручную: пагинация WB меняется между прогонами (один и тот же каталог измерениями давал 59 и 60 доступных страниц). _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** указывать в отчёте, чем закончилась пагинация (`catalog_end` или `api_cap`). _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** выставлять `known_pages_limit` выше предела API: страница выше приходит ошибкой, а пустым списком — никогда. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** записывать в YAML число, которого нет в отчёте измерения. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** принимать `total` за длину каталога: это размер выдачи поиска. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** держать модель и представление `latest_product_observations` в согласии — тест `the read model and the view agree on what the current observation is` проверяет именно это. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** отдавать `asOf` вместе с кэшированным агрегатом. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** считать, что список каталога — это товары, а `scraped_items` — их история. Запрос, который читает `scraped_items` напрямую, показывает наблюдения. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Never** применять фильтр по цене к строкам, а не к модели: товар, который стоил 500, а стоит 5000, не должен находиться по запросу «до 1000». _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Never** считать «количество товаров» количеством строк: это количество наблюдений, и оно растёт от каждого изменения цены. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** держать `totalItems`/`withCashback` (наблюдения) и `totalProducts`/ `productsWithCashback` (товары) рядом: пара показывает, сколько наблюдений у товара, и это число само по себе полезно. _(from `2026-10-20-the-counters-measure-different-things`)
- **Always** измерять `offset` в строках модели (в товарах) — так же, как строки, из которых он выбирается. _(from `2026-10-20-the-counters-measure-different-things`)
- **Always** называть ось измерения в имени, когда имя можно выбрать: `totalItems` — наблюдения, `totalProducts` — товары. Поле без названной оси — поле, у которого есть ось по умолчанию, а по умолчанию у каждого свой. _(from `2026-10-20-the-counters-measure-different-things`)
- **Always** считать счётчик цели по товарам **этой** цели. Счётчик по всей модели рядом со списком этой цели — это число, которое ничего не говорит о цели. _(from `2026-10-20-the-counters-measure-different-things`)
- **Never** менять смысл существующего поля контракта. Это ломающее изменение, и оно требует нового пути версии. _(from `2026-10-20-the-counters-measure-different-things`)
- **Never** складывать счётчики по целям и называть результат «товарами в каталоге»: товар в нескольких целях считается в каждой. Сумма **≥** `totalProducts`, и это нормально. _(from `2026-10-20-the-counters-measure-different-things`)
- **Always** держать гейт покрытия и ратчет полов на одном измерении: ратчет, который меряет иначе, двигает пол к тому, чего гейт не проверяет. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Always** мерить строку floors по merged-отчёту с фильтром по пакетам модуля. Отчёт модуля не видит покрытие, которое дали чужие сьюты. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Never** выводить «покрытие модуля» из отчёта, который этот модуль не видел. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Never** читать из отчёта модуля вывод о покрытии приложения: в этом репозитории его тесты живут в модуле `tests`, и 35 классов выглядели непокрытыми, будучи покрытыми на 57–100 %. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Always** держать окно свежести длиннее круга по всем целям: круг — это 132 цели × 60 страниц ÷ число профилей. _(from `2026-10-20-the-crawl-cadence-follows-the-measured-catalog`)
- **Always** ждать, что круг занимает часы, а не минуты: полный обход 132 каталогов по 60 страниц — это примерно 4,5 часа при трёх профилях. _(from `2026-10-20-the-crawl-cadence-follows-the-measured-catalog`)
- **Always** пересчитывать расписание, когда меняется измеренная длина каталога, — параметры производные, а не постоянные. _(from `2026-10-20-the-crawl-cadence-follows-the-measured-catalog`)
- **Never** ставить запасной лимит ниже измеренного минимума: цель без `known_pages_limit` обрезается молча. _(from `2026-10-20-the-crawl-cadence-follows-the-measured-catalog`)
- **Never** считать тик ритмом обхода: ритм задаёт окно свежести. _(from `2026-10-20-the-crawl-cadence-follows-the-measured-catalog`)
- **Always** держать `docs/RUNBOOK.md` в состоянии, в котором команды из него работают. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** пересобирать данные процедурой `rebuild_prod_data.sh`, а не ручными `TRUNCATE`; процедура делает бэкап и печатает счётчики. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** показывать усечение каталога отдельным счётчиком: полнота данных не должна быть видна только в YAML. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** прогонять `refresh_catalog_tokens.py` перед загрузкой каталога в БД. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** выставлять `known_pages_limit` по круглому числу: сначала измерить длину каталога. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** грузить в БД цель без `wb_search_query`. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** оставлять в каталоге поле, которое никто не читает. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** давать гейту здоровья время на первый старт: ядро Chrome (520 МБ) качается при первом запуске, поэтому таймаут гейта — 600 с, а не 180. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Always** держать ключ AdsPower в файле, который compose передаёт буквально. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Always** публиковать оба образа из одного коммита и поднимать их одним compose-вызовом: браузер без краула и краул без браузера не работают. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Always** читать compose-файл из репозитория приложения: как краул работает — это его контракт, и его изменение проходит ревью здесь, а не в шаблоне роли. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Never** включать Flyway обратно, пока история миграций базы не совпадает с миграциями сборки: Flyway падает на проверке контрольной суммы, и падает на каждом деплое. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Never** запекать ядро Chrome в образ: ключ AdsPower попадёт в слой. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Never** отдавать контейнеру краула host-сеть: Local API AdsPower и админский API окажутся на публичном адресе. _(from `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace`)
- **Always** измерять модель тем же скриптом (`scripts/load-catalog.sh`) и тем же набором данных, что и предыдущий замер. Сравнение цифр, взятых из разных замеров, — это не сравнение. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)
- **Always** указывать в ответе, когда собраны агрегаты (`asOf` это уже делает) — staleness без признака staleness — это просто устаревшие данные. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)
- **Never** материализовать модель каталога, не решив заранее: кто её пересобирает, в каких профилях, как покупатель узнаёт о staleness и что каталог отдаёт сразу после деплоя. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)
- **Never** считать `latest_product_observations` материализованной моделью: это view, пересчитываемая планировщиком, и её нельзя стареть «по расписанию». _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)
- **Always** брать лимит как максимум по всем измерениям, а не как последнее. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Always** запускать `python3 scripts/check-measurements.py --self-test` после правки гейта: гейт, который не может упасть, не проверяет ничего. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Always** оставлять метаданные пустыми у цели, которую не измеряли, — NULL означает «никто не проверял», и это тоже ответ. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Always** писать в YAML лимит вместе с датой, границей и источником измерения. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Never** записывать в YAML лимит, которого нет в артефакте измерения: гейт `check-measurements.py` это отвергнет. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Never** править `catalog_measurements.json` руками: артефакт перегенерируется командой `sync_measurement_metadata.py`. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Never** ставить `wb_subject_id` по догадке: он измеряется по товарам каталога. _(from `2026-10-20-the-measurement-travels-with-the-limit`)
- **Always** give every `Side` variant a non-null interpreter. The registry refuses to be constructed without one, and the `when` refuses to compile without a branch. _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Always** keep the no-op defaults for `Side.Log` and `Side.Metric`. A missing logger must not take the crawl down. _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Never** add a `Side` variant that no stage emits. If the effect is not real, it is not a side; if it is real, it needs a stage and an interpreter. _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Never** make a side's interpreter nullable to avoid touching a call site. The call site is where the decision belongs. _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Always** keep the security policy's scope narrow and honest. A policy that claims everything is out of scope is not a policy. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** pin an action to a commit and keep the version in a comment. The gate fails otherwise. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** protect `main` with the pipeline's own gates as the required checks. A check that is not required is a check that can be skipped. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Never** let a score be the thing that is maintained. The scorecard report is read; the gate is what holds. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Never** require approving reviews in a repository with one maintainer. A rule that is always bypassed teaches people to bypass rules. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** задавать темп через `RequestPacer`. Второй источник задержки делает интервал неточным, а конфиг — недостоверным. _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Always** клампить границы jitter в ноль: отрицательная задержка — это падение, а не «быстрее». _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Never** ждать перед запросом в `BrowserDownloader`: pacer уже это сделал, а лишнее ожидание не видно ни в конфиге, ни в метриках. _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Never** убирать jitter, считая его бесплатным: он примерно треть времени между запросами и именно он убирает метроном. _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Always** держать `wb_catalog_targets.yaml` в репозитории и прогонять `scripts/wb-catalog/enrich_catalog_queries.py` перед загрузкой в БД. _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)
- **Never** полагаться на YAML как на единственный источник целей: в БД они всё равно лежат в `crawl_targets`, а YAML — это их источник и версия. _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)
- **Always** брать категорию из `crawl_targets.wb_search_query`; пустое значение — отказ, а не «популярный список». _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Always** применять `scripts/wb-catalog/enrich_catalog_queries.py` к YAML перед загрузкой в БД. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Never** считать, что глубина ограничивает число страниц: `max_depth` — это глубина по дереву категорий, а не лимит страниц (см. ADR про глубину пагинации). _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Never** читать `nextPage` из `payload` как единственный источник пагинации: у v18 его нет, страница выводится из URL. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Always** держать nullable там, где «нет» и «пусто» — разные факты. _(from `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too`)
- **Always** спрашивать модель тот вопрос, ради которого проверка и затевалась: «есть ли список товаров», а не «есть ли известный ключ». _(from `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too`)
- **Never** путать отсутствие поля и пустое значение, если различие меняет диагноз: каталог кончился и WB переименовал поле — это разные вещи, и обе выглядят как «0 товаров». _(from `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too`)
- **Never** разбирать тело в дерево ради редкого случая, если модель может ответить сама. _(from `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too`)
- **Always** держать артефакты с разными линиями версий в разных группах Dependabot: один красный бамп не должен держать остальные обновления. _(from `2026-10-21-a-version-nobody-published-is-not-a-version`)
- **Always** помнить, что версия из BOM и версия из каталога могут быть строгими: `jackson-bom` 2.22.4 требует `jackson-annotations` ровно `2.22`, и «подойдёт и 2.22.4» здесь неверно. _(from `2026-10-21-a-version-nobody-published-is-not-a-version`)
- **Always** проверять, что версия, которую предлагает Dependabot, есть на Maven Central для **каждого** артефакта, который на неё ссылается, — иначе красная сборка вместо обновления. _(from `2026-10-21-a-version-nobody-published-is-not-a-version`)
- **Never** держать два артефакта с разными линиями версий на одном ключе версии в `gradle/libs.versions.toml`. Ключ, который пережил переход артефакта на новую линию (`2.19.4 → 2.20`), — это ключ, который однажды принесёт несуществующий релиз. _(from `2026-10-21-a-version-nobody-published-is-not-a-version`)
- **Never** считать бамп, предложение которого стало грубее (`2.19.1 → 2.22`), minor'ом: неизвестный релиз — это не безопасный релиз. _(from `2026-10-21-a-version-nobody-published-is-not-a-version`)
- **Always** выбирать группу по ближайшему свободному слоту паузера. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Always** делить паузу и circuit breaker по egress-группам, а не по процессу. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Always** держать в группе одинаковый прокси: группа — это один источник. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Always** оставлять двухпроходный заём профиля: сначала неза-flagged, потом flagged. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Always** проверять фактический IP через профиль, а не доверять полю `ip` в ответе AdsPower. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Never** брать паузу до того, как профиль взят: слот паузера не должен тратиться впустую. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Never** отдавать профиль в две группы. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Never** ронять старт из-за недоступности эхо-сервиса: probe предупреждает, а не блокирует. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Never** считать, что несколько профилей на группе дают скорость: бюджет задаёт пауза. Несколько профилей на группе — это живучесть при cooldown'е. _(from `2026-10-21-egress-groups-are-the-unit-of-rate-limiting`)
- **Always** выносить чтение машины из приватных методов в функцию с аргументами: код, который смотрит на окружение, иначе не проверяется. _(from `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested`)
- **Always** читать обе формы ответа, когда имя поля зависит от версии сервера: пустой список вместо данных выглядит как успех. _(from `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested`)
- **Never** кэшировать то, что меняется за время краула: набор открытых браузеров — не меняется, а порты — нет. _(from `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested`)
- **Never** оставлять ветвь, которая решает, что краул скажет, без теста: «профиль не поднялся» и «список профилей пуст» — это разные диагнозы, и оба должны быть произносимы. _(from `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested`)
- **Always** брать следующий pull request от свежего `main`, а не от предыдущей ветки: иначе решение наследует чужую историю и чужие конфликты. _(from `2026-10-21-one-decision-is-one-pull-request`)
- **Always** держать одно решение в одном pull request'е, а решение, которое не помещается, — делить до того, как писать код. _(from `2026-10-21-one-decision-is-one-pull-request`)
- **Always** перебазировать ветку на свежий `main` перед открытием pull request'а, а не после: перебазировать то, что уже прочитали, дороже. _(from `2026-10-21-one-decision-is-one-pull-request`)
- **Never** держать в одной ветке больше ~5 коммитов или ~20 файлов: за этой границей решение перестаёт читаться, а конфликт перестаёт быть конфликтом. _(from `2026-10-21-one-decision-is-one-pull-request`)
- **Never** разрешать конфликт в чужой ветке, не перечитав решение, которое в ней лежит: разрешение — это пересборка, и пересборка без чтения выглядит какmerge, а меняет смысл. _(from `2026-10-21-one-decision-is-one-pull-request`)
- **Always** различать `available` (не спрашивать сейчас) и `healthy` (отвечает нормально): это разные вопросы, и health-эндпоинт показывает оба. _(from `2026-10-21-one-state-for-the-egress-group`)
- **Always** считать состояние группы в одном месте (`EgressRouter.groupState`) и читать его оттуда — роутеру, health-эндпоинту и раннеру. _(from `2026-10-21-one-state-for-the-egress-group`)
- **Never** показывать в health-ответе счётчики процесса как счётчики группы: после смены адреса они описывают адрес, которого больше нет. _(from `2026-10-21-one-state-for-the-egress-group`)
- **Never** решать «доступен ли адрес» в вызывающем коде: у состояния один вердикт, и взять его можно только оттуда. _(from `2026-10-21-one-state-for-the-egress-group`)
- **Never** собирать состояние группы из метрик: у процесса — накопительные счётчики, у адреса — окно; это разные числа. _(from `2026-10-21-one-state-for-the-egress-group`)
- **Always** покрывать тестом любое решение о настройках декодера: «строго» — это утверждение, которое надо уметь отличить от «терпимо ко всему». _(from `2026-10-21-the-answer-is-read-strictly`)
- **Always** терпимо относиться к неизвестным полям: `ignoreUnknownKeys` остаётся. _(from `2026-10-21-the-answer-is-read-strictly`)
- **Always** читать ответы каталога одним декодером — `ru.wbparser.infra.json.wbJson`. _(from `2026-10-21-the-answer-is-read-strictly`)
- **Never** включать `isLenient` для ответов WB: он покупает нестандартный синтаксис, которого нет, и съедает причину сбоя разбора. _(from `2026-10-21-the-answer-is-read-strictly`)
- **Never** читать «сбой разбора» как «пустой каталог»: сбой называет поле, пустой каталог — это конец пагинации. _(from `2026-10-21-the-answer-is-read-strictly`)
- **Always** брать идентификатор инстанса из одной константы (`CatalogReadiness.INSTANCE_ID`), чтобы `/ready` и ответы с данными не могли разойтись. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Always** называть момент по тому, что он измеряет: `dataAsOf` — момент данных, `asOf` — момент вычисления агрегата. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Always** описывать ось поля там, где имя её не выражает, — в контракте и в ADR. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Always** считать момент данных по тем строкам, которые ответ читает (`max(scraped_at)`), а не по часам: момент данных принадлежит данным. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Never** давать двум осям одно имя и **Never** переиспользовать `asOf` для момента данных. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Never** переименовывать существующее поле контракта, чтобы снять двусмысленность имени: описание плюс новое поле; переименование — только вместе с новым путём версии. _(from `2026-10-21-the-answer-says-who-answered-and-when-the-data-is`)
- **Always** держать форму ответа под присмотром: изменение формы — самая вероятная причина того, что каталог приходит пустым. _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Always** называть в сообщении о сбое поле, которое не разобралось: это ответ на вопрос «что WB поменял». _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Always** спрашивать, разобрался ли ответ, раньше, чем пуст ли он: пустой каталог и изменение формы — разные факты. _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Never** повторять сбой разбора внутри прогона: форма ответа не меняется между запросами. _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Never** превращать неразобранный ответ в пустую страницу: пустая страница означает «конец каталога», и отчёт о прогоне станет ложным. _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Never** считать пустое тело исчерпанным каталогом: это оборванный ответ. _(from `2026-10-21-the-answer-that-does-not-parse-is-a-failure`)
- **Always** давать каждой новой части собственный тест — о её решении, а не о переносе кода. _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Always** делить класс по решению, которое он держит, и давать каждой части имя по этому решению. _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Always** держать публичную поверхность неизменной при разбиении: фасад делегирует, а потребители не меняются. _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Always** называть в фасаде таблицы, которые читает API: это нужно читателю, который пришёл за фасадом, — а гейт совместимости с тех пор читает пакет решений, а не этот файл (ADR `2026-10-21-the-schema-gate-reads-the-package`). _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Never** собирать SQL в фасаде: фасад держит решения, а не текст запросов. _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Never** тестировать решение о тексте SQL только через базу: где фильтр уехал и какая колонка забыта — видно в самом тексте, и проверять это дешевле и точнее. _(from `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold`)
- **Always** закрывать открытый issue совместимости, когда витрина снова ответила зелёным. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Always** оставлять красным то, за что отвечает краулер: незаданный репозиторий или отсутствующий токен, отказанный dispatch, не появившийся образ, не записанный отчёт. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Always** писать ответ витрины в отчёт: в сводку прогона и в issue с меткой `contract-compatibility`. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Always** считать пин витрины тем местом, где контракт обязателен: пин едет только на зелёном ответе. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Never** добавлять `site-compatibility` в обязательные проверки `main`, пока витрина не меняется из краулера и догоняет краулер после него. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Never** открывать второй issue совместимости, пока первый открыт. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Never** ронять `main` краулера из-за ответа витрины: красный, отменённый, прерванный по таймауту и отсутствующий ответ — это отчёт, а не отказ релизной проверки. _(from `2026-10-21-the-cross-repo-compatibility-is-a-report`)
- **Always** держать публичную поверхность `wbparser` узкой: функция не должна протаскивать в вызывающий модуль типы своей зависимости. _(from `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer`)
- **Always** называть причину разбора одинаково во всех местах, где читается JSON WB: один текст, одно место. _(from `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer`)
- **Always** проверять диагностику тестом: «называет поле» — это утверждение, которое можно потерять при переносе и не заметить. _(from `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer`)
- **Never** держать диагностику в том файле, который оказался первым: она принадлежит вопросу, а не файлу. _(from `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer`)
- **Always** собирать буллет целиком: продолжение присоединяется к первой строке, и правило попадает в digest целиком. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)
- **MUST** держать правило в digest одной строкой: Critical-раздел рассчитан на то, что правило читается и ищется целиком. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)
- **Never** обрезать правило по первой строке: агент читает digest перед задачей, и правило, обрезанное на полуслове, читается как другое правило. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)
- **Never** переносить правило в ADR только ради генератора: перенос — это ширина исходника, а digest собирает правило сам. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)
- **Always** носить прочитанное вместе с отказом: «0 страниц» о цели, которая прочитала страницы, — это ложь в отчёте. _(from `2026-10-21-the-failure-keeps-what-was-read`)
- **Always** сохранять то, что уже прочитано, прежде чем объявлять отказ. _(from `2026-10-21-the-failure-keeps-what-was-read`)
- **Never** отменять разобранные страницы пакета из-за одной сломанной: соседние страницы не связаны с ней. _(from `2026-10-21-the-failure-keeps-what-was-read`)
- **Never** продолжать пагинацию после отказа: отказ останавливает краул этой цели. _(from `2026-10-21-the-failure-keeps-what-was-read`)
- **Always** называть число `total` от WB как `wb_total` — в коде, в базе, в метриках и в отчёте. _(from `2026-10-21-the-number-wb-reports-reaches-the-report`)
- **Always** оставлять полноту каталога за измерением на границе пагинации. _(from `2026-10-21-the-number-wb-reports-reaches-the-report`)
- **Always** отличать «WB не сказал» (`null`) от «выдача пуста» (`0`). _(from `2026-10-21-the-number-wb-reports-reaches-the-report`)
- **Never** делить прочитанное на `wb_total` и называть результат полнотой. _(from `2026-10-21-the-number-wb-reports-reaches-the-report`)
- **Never** суммировать `wb_total` по целям: товар попадает в несколько выдач. _(from `2026-10-21-the-number-wb-reports-reaches-the-report`)
- **Always** держать `kotlinx-serialization` в версионном каталоге и читать с неё DTO: исключение декодирования пришло сюда из библиотеки, а не из кода краула. _(from `2026-10-21-the-parse-failure-names-the-field`)
- **Always** логировать путь элемента, который не разобрался, вместе со смещением и выдержкой входа: путь — это ответ на вопрос «что WB поменял». _(from `2026-10-21-the-parse-failure-names-the-field`)
- **Always** проверять защиту, которую нельзя вызвать настоящим входом, через шов, который позволяет её вызвать: непроверяемая защита — это вера, а не гарантия. _(from `2026-10-21-the-parse-failure-names-the-field`)
- **Never** ловить `Error` широким `catch (e: Exception)`: `StackOverflowError` требует отдельного `catch`, иначе он невидим. _(from `2026-10-21-the-parse-failure-names-the-field`)
- **Never** превращать ошибку разбора в пустую страницу без предупреждения: отчёт «0 предметов» без причины — это отчёт об успехе, которого не было. _(from `2026-10-21-the-parse-failure-names-the-field`)
- **Always** читать оба написания поля, когда имя зависит от версии сервера, и проверять оба. _(from `2026-10-21-the-profile-id-is-read-too`)
- **Never** считать пустой список ответов доказательством того, что данных нет: список может оказаться пустым из-за имени поля, и выглядит это как успех. _(from `2026-10-21-the-profile-id-is-read-too`)
- **Always** измерять модель тем же скриптом и тем же набором данных, что и предыдущий замер (200 000 наблюдений, пул из пяти, страница 24). _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Always** пересматривать это решение на замере `scripts/load-catalog.sh --rows N`, где N — продовый `count(*)` из `scraped_items`, записанный в фазе 0. _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Always** чинить путь записи (#147–#155) раньше, чем считать, что данных «не хватает» по причине модели. _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Never** выводить объём таблицы из замера отказов: 7 успешных запросов из 40 в плохом окне не дают ни объёма, ни его траектории. _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Never** материализовать read-модель каталога, пока p50 модели на продовом объёме не превысил 1 s — это 20 % бюджета витрины в 5 s. _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Never** обещать витрине, что материализация уберёт её таймаут: потолок задаёт пул из пяти соединений и глубокий offset, а они остаются. _(from `2026-10-21-the-read-model-is-not-needed-yet`)
- **Always** называть лицензию в `README.md` и держать текст `LICENSE` дословным по SPDX: переписанный текст лицензии — это уже другая лицензия, и Scorecard этого не заметит, а человек заметит. _(from `2026-10-21-the-repository-is-licensed`)
- **Always** проверять лицензию новой зависимости до того, как она попадает в `gradle/libs.versions.toml`: Apache-2.0 совместима не со всем, и несовместимая лицензия — это не повод для исключения, а повод решить. _(from `2026-10-21-the-repository-is-licensed`)
- **Never** менять лицензию репозитория молча: смена лицензии — это отдельное решение с отдельным ADR, и она не должна ехать вместе с функциональным изменением. _(from `2026-10-21-the-repository-is-licensed`)
- **Never** создавать `NOTICE` без причины: Apache-2.0 требует его только при наличии собственных уведомлений, которых у проекта нет. _(from `2026-10-21-the-repository-is-licensed`)
- **Always** называть в находке и таблицу, и файл, который её читает: находка без имени не годится в работу. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Always** проверять гейт гейтом: фикстура с новой таблицей в части обязана ронять гейт — иначе он ничего не ловит. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Always** читать пакет решений, а не один файл пакета: предмет гейта — то, что читает API, и он лежит в пакете, а не в одном его файле. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Never** перечислять в гейте исключения именами: CTE и псевдонимы подзапросов выводятся из текста, иначе новое имя в новом запросе сделает гейт ложно-красным. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Never** считать прочитанным один файл пакета: фасад не содержит SQL, и гейт, читающий только его, слеп к каждой из десяти частей. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Never** считать чтением то, что написано в комментарии: гейт читает строковые литералы, потому что только они уходят в базу. _(from `2026-10-21-the-schema-gate-reads-the-package`)
- **Always** объявлять egress-группы единственным списком профилей. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Always** проверять изоляцию статически, до первого запроса к WB. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **MUST** различать «проверка прошла» и «проверка не смогла выполниться»: второй случай — это предупреждение, а не успех. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Never** включать `require-isolated-egress-groups` там, где изоляция обеспечивается чем-то, кроме прокси. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Never** держать два способа описать профили: они разойдутся, и разойдутся тихо. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Never** считать профиль без прокси прямой, если AdsPower говорит, что прокси есть, но не отдаёт его адрес. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Never** считать статическую проверку достаточной: два разных прокси могут сходиться на одном адресе, и это видит только живой probe. _(from `2026-10-22-egress-groups-are-declared-and-checked`)
- **Always** писать прогон цели в базу: без этого история не знает, кто обслуживал цель. _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **Always** проверять адрес по расписанию, а не только на старте. _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **MUST** различать «адрес неизвестен» и «адрес подтверждён»: неизвестный адрес не повод закрывать circuit. _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **Never** останавливать краулер из-за недоступного эхо-сервиса: это «проверка не выполнилась», а не «проверка провалилась». _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **Never** переносить накопленное старым адресом на новый: circuit, книги и штраф. _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **Never** считать `crawl_targets` историей прогонов: это агрегат цели, а не журнал. _(from `2026-10-23-a-changed-address-is-a-new-identity`)
- **Always** отличать «нечего делать» от «провалилось»: отдельный исход, а не `Failure` с текстом. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Always** показывать состояние egress в health-ответе. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Always** считать, что недоступный эхо-сервис — это «проверка не выполнилась», а не «проверка провалилась». _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **MUST** называть пороги числами, а не описаниями. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Never** класть адрес в тег метрики: он меняется, а серия остаётся. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Never** открывать прогон, когда ни один адрес не может обслужить запрос. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Never** открывать строку прогона для прогона, который ничего не обслужил. _(from `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault`)
- **Always** брать цели арендой, а не блокировкой базы. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Always** включать общий бюджет, когда за одним адресом работает больше одного хоста. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **MUST** истекать аренду сама: хост может умереть, и цель не должна ждать его вечно. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Never** выдавать истёкшую аренду за занятую: истёкшая аренда — это «свободно». _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Never** дублировать правило свежести в SQL и в коде: правило одно, и его утверждение — в базе. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Never** отдавать аренду раньше, чем цель обработана: чужая машина возьмёт её снова. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Never** считать, что один хост за адресом — это весь трафик на адрес. _(from `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`)
- **Always** читать каталог через его собственный пул и роль. _(from `2026-10-26-the-catalog-reads-from-a-replica`)
- **MUST** сообщать в готовности, что каталог читает не оттуда же, куда пишет писатель. _(from `2026-10-26-the-catalog-reads-from-a-replica`)
- **Never** выдавать отставание реплики за ошибку данных. _(from `2026-10-26-the-catalog-reads-from-a-replica`)
- **Never** направлять писателя на реплику: потерянные данные не выглядят как потерянные. _(from `2026-10-26-the-catalog-reads-from-a-replica`)
- **Never** принимать решение о месте агрегатов вместе с решением о реплике: это разные решения, и у второго свои последствия. _(from `2026-10-26-the-catalog-reads-from-a-replica`)
- **Always** отделять антибот (498) от транспортного сброса (статус 0) в любом счёте отказов: доля отказов — это доля антибота, а не «всего плохого», и смешанный счёт маскирует, какой из двух механизмов сработал. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Always** писать измеренные числа в репозиторий рядом с решением, которое на них опирается: вывод, который нельзя перепроверить на ревью, не является основанием для закупки. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Always** считать параллельную работу на одном адресе отдельным фактором: адрес деградирует от соседа по адресу, и это надо измерять, а не предполагать. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **MUST** повторить замер по правилам протокола (≥3 повторов, рандомизированный порядок, резидентский класс прокси, A0 отдельно), прежде чем чем-либо закупать. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Never** объявлять лимит WB rate-based по одному блоку: гейт протокола требует ≥3 повторов в фиксированном окне, и блок 2026-10-20 этому не удовлетворяет. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Never** откатывать мульти-egress на основании одного замера: конструкция держится на решениях об идентичности группы и единице ограничения, а не на этом блоке. _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Never** покупать второй адрес, опираясь на блок 2026-10-20: гейт не пройден, а измеренное ухудшение адреса от параллельной работы не объясняется моделью «N × бюджет». _(from `2026-10-27-the-measurement-does-not-authorize-a-second-address`)
- **Always** отделять «нечего делать» от «сломалось» в любом исходе, который доходит до оператора: у планировщика это разные коды выхода и разные сообщения. _(from `2026-10-28-the-scheduler-asks-the-crawl-one-question`)
- **MUST** держать тест на каждый исход планировщика: исходов шесть, и «ничего не делать» — тот, который забывают проверить первым. _(from `2026-10-28-the-scheduler-asks-the-crawl-one-question`)
- **Never** глотать отмену в планировщике: `CancellationException` пробрасывается. _(from `2026-10-28-the-scheduler-asks-the-crawl-one-question`)
- **Never** отдавать планировщику конкретного исполнителя, если у исполнителя есть состояние: seam из одного метода — это то, что позволяет тестировать путь. _(from `2026-10-28-the-scheduler-asks-the-crawl-one-question`)
- **Never** прятать исход прогона внутри класса, который его производит: производитель и потребитель — разные слои, и у них должно быть общее имя. _(from `2026-10-28-the-scheduler-asks-the-crawl-one-question`)
- **Always** спрашивать то, что не зависит от работы, **до** того, как работа взята. _(from `2026-10-29-the-address-is-asked-before-the-work-is-taken`)
- **MUST** иметь тест, который доказывает: отказавшийся прогон не оставляет аренды на целях. Регрессия возвращается молча — она видна только по простоявшим целям. _(from `2026-10-29-the-address-is-asked-before-the-work-is-taken`)
- **Never** брать аренду (или любое эксклюзивное право на работу) для того, что не будет сделано: взятое и не сделанное блокирует других на всё время аренды. _(from `2026-10-29-the-address-is-asked-before-the-work-is-taken`)
- **Never** полагаться на `finally` для освобождения того, что взято ранним выходом: `finally` не выполняется для выходов, случившихся до блока. _(from `2026-10-29-the-address-is-asked-before-the-work-is-taken`)
- **Always** говорить в лог о группе, чей адрес не удалось узнать: молчание и «всё хорошо» не должны выглядеть одинаково. _(from `2026-10-30-an-empty-observation-changes-nothing`)
- **Always** отличать «неизвестно» от «изменилось», когда мир задаёт вопрос, на который нельзя ответить. _(from `2026-10-30-an-empty-observation-changes-nothing`)
- **MUST** хранить время последнего настоящего наблюдения, чтобы устаревший ответ был виден как устаревший. _(from `2026-10-30-an-empty-observation-changes-nothing`)
- **Never** записывать наблюдение, которого не было: неизвестный адрес не стирает известный и не выглядит как изменение. _(from `2026-10-30-an-empty-observation-changes-nothing`)
- **Never** позволять счётчику настоящих изменений расти на шум. _(from `2026-10-30-an-empty-observation-changes-nothing`)
- **Always** нести в образах ту версию JDK, которую просит сборка. _(from `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk`)
- **Always** читать версию из кода, а не из комментария: комментарий, объясняющий историю, упоминает и старую версию. _(from `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk`)
- **MUST** иметь гейт, который умеет падать: проверка, отличимая от проверки, которая не может упасть, — не проверка. _(from `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk`)
- **Never** двигать версию в базовом образе, не двигая тулчейн в том же изменении. _(from `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk`)
- **Never** считать `publish-image` защитой от такой поломки: джоб не обязательный, и образ, который не собрался, до `main` не дойдёт — но и PR с ним будет зелёным. _(from `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk`)
- **Always** измерять новый адрес по протоколу (`2026-10-27-the-measurement-does-not-authorize-a-second-address`) до того, как доверять ему обход: гейт протокола, который не пройден, не разрешает адрес. _(from `2026-11-01-one-crawler-many-addresses`)
- **Always** измерять цену хопа: адрес измеряется вместе с задержкой до WB, и эндпоинт, который добавляет больше задержки, чем снимает отказов, адресом не является. _(from `2026-11-01-one-crawler-many-addresses`)
- **Always** объявлять прокси группы в конфигурации краулера и сверять его с AdsPower до старта обхода: расхождение адреса и объявления молча даёт не тот бюджет и не тот адрес, ради которых группа объявлена. _(from `2026-11-01-one-crawler-many-addresses`)
- **MUST** держать один бюджет на адрес: пока процесс один, бюджет живёт в процессе; при появлении второго хоста бюджет переезжает в базу (ADR `2026-10-25-the-work-and-the-budget-belong-where-they-are-known`). _(from `2026-11-01-one-crawler-many-addresses`)
- **Never** множить краулер, чтобы получить больше адресов: адрес — это то, за что считает WB, и его можно получить без второй машины. _(from `2026-11-01-one-crawler-many-addresses`)
- **Never** строить общую очередь заданий ради числа адресов: адреса множатся прокси-эндпоинтами, а очередь появляется только тогда, когда обход упёрся в CPU одного хоста. _(from `2026-11-01-one-crawler-many-addresses`)
- **Never** считать адрес группы известным, если он не объявлен и не наблюдался: «не знаю» и «прямой выход» — разные вещи, и вторая не должна получаться молча. _(from `2026-11-01-one-crawler-many-addresses`)
- **Never** хранить пароль прокси в репозитории: пароль на каждый эндпоинт свой, приходит из секрета, а ротация — сознательное действие, после которого надо убедиться, что AdsPower узнал новый пароль, а не старый. _(from `2026-11-01-one-crawler-many-addresses`)
- **Always** выполнять блок протокола скриптом `scripts/measure_egress.py`, а не вручную: порядок, пауза и время старта должны быть в данных, иначе их нечем проверять. _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **Always** писать в отчёте «нет», когда правило не выполнено: честное «нет» гейт принимает, и это единственное, что делает блок пригодным для следующего решения. _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **Always** читать вердикт блока как вывод о **пригодности блока**, а не как вывод о мире: блок, не прошедший гейт, не авторизует адрес (ADR `2026-10-27-the-measurement-does-not-authorize-a-second-address`). _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **MUST** коммитить сырые JSON вместе с отчётом: отчёт без сырых данных — это утверждение, а не измерение. _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **Never** править числа в отчёте блока рукой: отчёт пишется из данных, и правка расходится с JSON рядом — гейт это находит. _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **Never** считать правило выполненным, если его нельзя проверить по данным: гейт пишет «не решается из данных», и это честнее «да». _(from `2026-11-02-the-protocol-runs-from-the-repository`)
- **Always** держать одну и ту же схему ключа в краулере и в витрине. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Always** записывать решение по зависимости, а номер(а) pull request'а — оставлять в причине. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Always** объяснять гейтом записанное решение, а не падать молча: решение `deferred` с причиной — это объяснение, а не ошибка. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Always** спрашивать решение по зависимости: и в обходе dependabot'а, и в гейтах, которые проверяют согласие версий. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **MUST** падать громко на нечитаемой таблице решений: решение, которое никто не может прочитать, не решение. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **MUST** читать зависимость из тела pull request'а: это то, что dependabot пишет сам, и то, что уже есть у workflow'а. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Never** ключить решение номером pull request'а: номер меняется при каждом цикле dependabot'а, и решение перестаёт находиться. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Never** прощать гейту расхождение без записанного решения: никто не решал, что образ может нести не ту версию. _(from `2026-11-03-the-decision-belongs-to-the-dependency`)
- **Always** отдавать circuit'у окно по времени, а не rate по последним вызовам: измеренный порог измеряется десятками запросов, а не десятью последними. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Always** писать в ledger измерение сессии рядом с адресом: вопрос «кто дал 498» имеет два ответа, и оба должны быть записаны. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Always** считать отказ в сессии и отдельно в группе: два счётчика, разные вопросы, сливать их нельзя. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Never** включать лимиты сессии (число запросов, срок жизни, вывод из ротации) до решающего прогона: без источника свежих профилей ротация тратит ёмкость, а не сохраняет её. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Never** открывать circuit антибота по транспортному сбою: у обрыва сети своя цепь, и адрес за чужую неполомку не отвечает. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Never** считать ёмкость краулера в адресах: измеряется она в живых сессиях, и это верно при любом ключе счётчика. _(from `2026-11-04-the-refusal-is-a-count-against-the-session`)
- **Always** держать в файле роутера только маршрутизацию: выбор группы, её книги и перевод состояния circuit'а в словарь кодовой базы. _(from `2026-11-05-the-router-file-holds-the-routing`)
- **Always** писать в KDoc типа, какое решение он держит: файл без этого ответа повторяет ту же проблему в меньшем масштабе. _(from `2026-11-05-the-router-file-holds-the-routing`)
- **Never** менять поведение вместе с переносом: перенос решения не должен двигать значения по умолчанию, подписи и границы. _(from `2026-11-05-the-router-file-holds-the-routing`)
- **Never** объявлять в файле роутера типы состояния (`DownloadOutcome`, `EgressAttribution`, `EgressRouterConfig` и подобные): у каждого типа — свой файл, названный по типу. _(from `2026-11-05-the-router-file-holds-the-routing`)

## Per-tag

### `G10`

- A DB blip no longer fails the entire crawl. The save still fails the item (returns 0), but the crawl continues. _(from `2026-09-09-save-stage-retry-pr11`)_

### `PR-11-5`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `adspower`

- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а не сбой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут вместе с процессом. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это ожидаемо: краул ходит в чужой API и платит за это скоростью, а не частотой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это означает настоящий блок, а не мгновенный отказ открытой цепи. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `ansible`

- The crawler reaching Wildberries from the VPS is a separate question from reaching a registry, and it is still unverified: the outbound restriction has only ever been observed against HTTPS in general. _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `antibot`

- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels with the borrow instead of being re-derived at each call site. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. Warm-up now belongs to whoever needs a warm page, and is timed by the caller. _(from `2026-10-14-antibot-session-is-site-wide`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не браузера. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от старта. Расписание при этом остаётся не-метрономным — jitter прибавляется к каждому следующему интервалу. _(from `2026-10-20-the-request-rate-has-one-owner`)_

### `api`

- Deleting a target through the admin API deletes its scraped items and category mappings too. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- The API answers a request that arrives right after a restart with a value computed from the database at that moment, and the next one with a value computed before the restart. Both are correct answers to "what did the database say". _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is separate and worth stating here: it is 27 ms of a 125 ms request, and caching it would make the number the storefront shows "N products" wrong by up to ten minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a lie the storefront tells. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role are removed only after the rollback window has closed and a rollback drill has been run — not before. _(from `2026-10-19-catalog-read-api-contract`)_
- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому `cashback_percent` останется пустым, пока не появится другой источник. Это не регрессия: в старых данных кэшбэк тоже был пустым. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- When the topology grows to more than one instance, the aggregates move to a shared cache in the same change that adds the second instance. Doing them separately would leave a window in which the second instance answers from its own empty cache — the exact inconsistency this decision exists to prevent. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the crawler a source for category names. Filling them with a guess would be worse than leaving them empty. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this change keep their nulls — there is no backfill, and the crawler will fill them in on the next visit to each target. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. Их нужно пересобрать (в debug-окружении база пересобрана с нуля). _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_

### `architecture`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to `PostgresAdvisoryLock`, the crawler's own advisory lock, so multi-instance deployments do not need ShedLock. The ShedLock dependency was removed as unused. _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in `config/test-runs-floor.tsv`, 10 files, 145 tests. _(from `2026-10-08-gates-that-can-fail`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Deleting a target through the admin API deletes its scraped items and category mappings too. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for deterministic integration tests. _(from `2026-10-14-pipeline-purity-idgen`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect of the crawler, and it arrives with a stage, an interpreter and a test. _(from `2026-10-20-the-registry-is-total-by-construction`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same commit. Verified by control: removing `TargetQueriesPostgresTest` leaves Gradle reporting `BUILD SUCCESSFUL` while the floor gate exits 1 with "ran 31 tests, floor is 44". _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both repositories. Verified by control in each: deleting the new suite leaves the test task reporting success while the floor gate exits 1. _(from `2026-10-08-gates-that-can-fail`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path tests and leaves `TargetQueries` green. Under the old list it would have failed none. _(from `2026-10-08-discover-test-migrations`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, but this is a fundamental property of any suspend-based retry implementation and does not affect testability or debuggability. _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share `PostgresFixture`, and both call `db.clear()` from `beforeTest`. The SQLite suites already did; the Postgres ones did not, so their results depended on declaration order. _(from `2026-10-08-discover-test-migrations`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the crawler a source for category names. Filling them with a guess would be worse than leaving them empty. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this change keep their nulls — there is no backfill, and the crawler will fill them in on the next visit to each target. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- Гейт не зарегистрирован в `scripts/ci/static-gates.sh`: ему нужна живая база, и его положительный контроль поднимает PostgreSQL в Docker. Поэтому его контроль ходит по CI через тесты скриптов (`scripts/tests/test_production_schema_gate.py`), а не через реестр. _(from `2026-10-21-the-schema-gate-reads-the-package`)_
- Сторона колонок остаётся копией: `REQUIRED_COLUMNS` в гейте и в `CatalogSchemaQueries.kt` — два списка, и гейт сверяет свой со своими запросами, а не с API. Следующий шаг в эту сторону — брать список колонок из самого API. _(from `2026-10-21-the-schema-gate-reads-the-package`)_

### `arrow`

- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_

### `behaviour`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one `Side.Drop` reaches the interpreter per rejected item. _(from `2026-10-09-wire-business-rules-into-filter`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, before the `enrich` stage. Any future rule that needs a field only present on `SavedItem` would have to move to a later stage. _(from `2026-10-09-wire-business-rules-into-filter`)_

### `bootstrap`

- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с предупреждением. _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_

### `browser`

- A profile directory that was never materialised and a profile whose `Preferences` belong to another user are different problems with different fixes, and the pre-flight check names them apart. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user running the AdsPower runtime. A container that mounted `~/.adspowerCli` and ran as root leaves root-owned profile directories and Chrome kernels behind; the host runtime then cannot write `Preferences` and the browser refuses to start with `EACCES`. See `docs/decisions/2026-10-19-local-debug-stack-repairs.md`. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No serialization failures were observed in these runs, so the isolation level was left alone rather than changed on theory. _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is the only thing that will change that, and it needs a profile-per-test fixture CI does not have yet. _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own Chrome over CDP, so Playwright's bundled Chromium is never launched; without the flag `Playwright.create()` downloads 161 MB on every browser borrow and re-downloads it on every retry. `deploy/docker-compose.adspower.yml` already had this. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` overrides it for installations that keep their state elsewhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is AdsPower's own and is not reconstructed. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the resolver logs a warning and falls back to scanning. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the environment and reported by the pre-flight check, but the Local API on loopback is unauthenticated and the key is not sent anywhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake included — must answer `isAvailable()` honestly, or the pool will fall back to the scan when it should have reported a broken profile. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels with the borrow instead of being re-derived at each call site. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. The new concurrency logic was put where it *is* testable: `CrawlRunner`'s bound is verified by a peak-concurrency probe against a real PostgreSQL, including that `target-concurrency = 1` still measures exactly 1, so the probe cannot pass on a runner that ignored the setting. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. Warm-up now belongs to whoever needs a warm page, and is timed by the caller. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не браузера. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут вместе с процессом. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от старта. Расписание при этом остаётся не-метрономным — jitter прибавляется к каждому следующему интервалу. _(from `2026-10-20-the-request-rate-has-one-owner`)_

### `bug-fix`

- A DB blip no longer fails the entire crawl. The save still fails the item (returns 0), but the crawl continues. _(from `2026-09-09-save-stage-retry-pr11`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_

### `build`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` job, symmetrically — Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-coverage-floor-gate`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-dependency-analysis-plugin`)_
- The startup gate stays: this gate cannot see a dependency that the framework wires at runtime (`spring-jdbc` was missing, and no bytecode referenced it). _(from `2026-10-19-runtime-classpath-gate`)_

### `cache`

- The API answers a request that arrives right after a restart with a value computed from the database at that moment, and the next one with a value computed before the restart. Both are correct answers to "what did the database say". _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is separate and worth stating here: it is 27 ms of a 125 ms request, and caching it would make the number the storefront shows "N products" wrong by up to ten minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a lie the storefront tells. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- When the topology grows to more than one instance, the aggregates move to a shared cache in the same change that adds the second instance. Doing them separately would leave a window in which the second instance answers from its own empty cache — the exact inconsistency this decision exists to prevent. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_

### `catalog`

- A YAML written by an older selector has no `root_category_name`; run `just catalog::fill-roots` before loading it. Pages missing from the menu tree are reported by name and left alone rather than guessed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- The API answers a request that arrives right after a restart with a value computed from the database at that moment, and the next one with a value computed before the restart. Both are correct answers to "what did the database say". _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is separate and worth stating here: it is 27 ms of a 125 ms request, and caching it would make the number the storefront shows "N products" wrong by up to ten minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a lie the storefront tells. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role are removed only after the rollback window has closed and a rollback drill has been run — not before. _(from `2026-10-19-catalog-read-api-contract`)_
- When the topology grows to more than one instance, the aggregates move to a shared cache in the same change that adds the second instance. Doing them separately would leave a window in which the second instance answers from its own empty cache — the exact inconsistency this decision exists to prevent. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled for one page per run; a target that leaves it NULL uses the runner-wide `maxPagesPerCatalog`. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно в `scraped_items`. _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of the crawl's write path and nothing depends on it being present. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- Пока решения нет, цифры живут в ADR `2026-10-20-the-catalog-lists-products-not-observations.md` и пересчитываются тем же скриптом. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)_

### `ci`

- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in `config/test-runs-floor.tsv`, 10 files, 145 tests. _(from `2026-10-08-gates-that-can-fail`)_
- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The badge stays empty while the repository is not indexed by the API. When it is indexed, the strict comparison starts working on the next run, with no change to the workflow. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)_
- The executed-test floors are raised in the same commit that adds tests, in both repositories. Verified by control in each: deleting the new suite leaves the test task reporting success while the floor gate exits 1. _(from `2026-10-08-gates-that-can-fail`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-dependency-analysis-plugin`)_
- The nightly now costs two workflows a week instead of one: the run itself, and the watchdog that checks it. The watchdog is a few seconds of API calls. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The nightly run costs a full pipeline every day. It is the price of knowing that the repository is still healthy when nobody touches it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a commit that moves a hash. That is the intended cost: the change is visible, reviewable, and reversible. _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit is sixty requests an hour, which is fewer than the number of pins here. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_

### `cleanup`

- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_
- `retryDelayMs()` reduced from 19 lines to 11 lines _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_

### `code-quality`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_

### `concurrency`

- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No serialization failures were observed in these runs, so the isolation level was left alone rather than changed on theory. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. The new concurrency logic was put where it *is* testable: `CrawlRunner`'s bound is verified by a peak-concurrency probe against a real PostgreSQL, including that `target-concurrency = 1` still measures exactly 1, so the probe cannot pass on a runner that ignored the setting. _(from `2026-10-14-parallel-crawl-targets`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_

### `configuration`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one `Side.Drop` reaches the interpreter per rejected item. _(from `2026-10-09-wire-business-rules-into-filter`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, before the `enrich` stage. Any future rule that needs a field only present on `SavedItem` would have to move to a later stage. _(from `2026-10-09-wire-business-rules-into-filter`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- Окно свежести остаётся в памяти процесса, поэтому перезапуск приложения делает все цели «просроченными» и первый тик запускает полный краул. Это стоит держать в голове при развёртывании: рестарт = краул. _(from `2026-10-20-schedule-cadence-single-parameter`)_

### `contract`

- The schema copy in the storefront, its `DATABASE_URL` and its read-only role are removed only after the rollback window has closed and a rollback drill has been run — not before. _(from `2026-10-19-catalog-read-api-contract`)_

### `conventions`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_

### `coverage`

- The browser adapters remain uncovered by design. Adding a browser-backed integration test is the only thing that will change that, and it needs a profile-per-test fixture CI does not have yet. _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` job, symmetrically — Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-coverage-floor-gate`)_

### `crawler`

- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому `cashback_percent` останется пустым, пока не появится другой источник. Это не регрессия: в старых данных кэшбэк тоже был пустым. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а не сбой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. Их нужно пересобрать (в debug-окружении база пересобрана с нуля). _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не браузера. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут вместе с процессом. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от старта. Расписание при этом остаётся не-метрономным — jitter прибавляется к каждому следующему интервалу. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это ожидаемо: краул ходит в чужой API и платит за это скоростью, а не частотой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с предупреждением. _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это означает настоящий блок, а не мгновенный отказ открытой цепи. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `data`

- A YAML written by an older selector has no `root_category_name`; run `just catalog::fill-roots` before loading it. Pages missing from the menu tree are reported by name and left alone rather than guessed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled for one page per run; a target that leaves it NULL uses the runner-wide `maxPagesPerCatalog`. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of the crawl's write path and nothing depends on it being present. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_

### `data-quality`

- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому `cashback_percent` останется пустым, пока не появится другой источник. Это не регрессия: в старых данных кэшбэк тоже был пустым. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. Их нужно пересобрать (в debug-окружении база пересобрана с нуля). _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с предупреждением. _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_

### `database`

- A job with no heartbeat and no start time falls back to its creation time — the third branch — so a row that was never touched still ages out. _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- Deleting a target through the admin API deletes its scraped items and category mappings too. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Jobs left `Running` by a crash are now reported as `Crashed` with an explanatory `error_message`, instead of looking like a running crawl. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same commit. Verified by control: removing `TargetQueriesPostgresTest` leaves Gradle reporting `BUILD SUCCESSFUL` while the floor gate exits 1 with "ran 31 tests, floor is 44". _(from `2026-10-08-discover-test-migrations`)_
- The gate compares columns — names, types, nullability. It does not compare defaults, indexes or constraints: the unique index on `(target_id, content_hash)` and the `now()` defaults are described in the migrations and read by the code, and a comparison of them would be a second specification to maintain. _(from `2026-10-19-schema-drift-gate`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently. _(from `2026-10-19-application-startup-gate`)_
- The item path writes one statement per item, and the category path one per distinct category. A future change that batches these needs the empty-row behaviour of `addBatch()` handled explicitly — and a test that would fail if it were not. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- The reconciliation runs on every boot. It touches only rows that have been quiet for more than five minutes, so a normal startup changes nothing. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The reconciliation stays a single statement over all stale rows, and its result does not depend on the time zone of the machine that runs it. _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role are removed only after the rollback window has closed and a rollback drill has been run — not before. _(from `2026-10-19-catalog-read-api-contract`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path tests and leaves `TargetQueries` green. Under the old list it would have failed none. _(from `2026-10-08-discover-test-migrations`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a debugging session. The crawl's log volume grows by two orders of magnitude while it is on. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share `PostgresFixture`, and both call `db.clear()` from `beforeTest`. The SQLite suites already did; the Postgres ones did not, so their results depended on declaration order. _(from `2026-10-08-discover-test-migrations`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the crawler a source for category names. Filling them with a guess would be worse than leaving them empty. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was always null should be revisited. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно в `scraped_items`. _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this change keep their nulls — there is no backfill, and the crawler will fill them in on the next visit to each target. _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Гейт не зарегистрирован в `scripts/ci/static-gates.sh`: ему нужна живая база, и его положительный контроль поднимает PostgreSQL в Docker. Поэтому его контроль ходит по CI через тесты скриптов (`scripts/tests/test_production_schema_gate.py`), а не через реестр. _(from `2026-10-21-the-schema-gate-reads-the-package`)_
- Пока решения нет, цифры живут в ADR `2026-10-20-the-catalog-lists-products-not-observations.md` и пересчитываются тем же скриптом. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)_
- Сторона колонок остаётся копией: `REQUIRED_COLUMNS` в гейте и в `CatalogSchemaQueries.kt` — два списка, и гейт сверяет свой со своими запросами, а не с API. Следующий шаг в эту сторону — брать список колонок из самого API. _(from `2026-10-21-the-schema-gate-reads-the-package`)_

### `dead-code`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_

### `debugging`

- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user running the AdsPower runtime. A container that mounted `~/.adspowerCli` and ran as root leaves root-owned profile directories and Chrome kernels behind; the host runtime then cannot write `Preferences` and the browser refuses to start with `EACCES`. See `docs/decisions/2026-10-19-local-debug-stack-repairs.md`. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the image is cheaper than the alternative — a browser that cannot be found. _(from `2026-10-19-local-debug-stack-repairs`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own Chrome over CDP, so Playwright's bundled Chromium is never launched; without the flag `Playwright.create()` downloads 161 MB on every browser borrow and re-downloads it on every retry. `deploy/docker-compose.adspower.yml` already had this. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The item path writes one statement per item, and the category path one per distinct category. A future change that batches these needs the empty-row behaviour of `addBatch()` handled explicitly — and a test that would fail if it were not. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the resolver logs a warning and falls back to scanning. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a debugging session. The crawl's log volume grows by two orders of magnitude while it is on. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_

### `dependencies`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-dependency-analysis-plugin`)_

### `deployment`

- The API answers a request that arrives right after a restart with a value computed from the database at that moment, and the next one with a value computed before the restart. Both are correct answers to "what did the database say". _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is separate and worth stating here: it is 27 ms of a 125 ms request, and caching it would make the number the storefront shows "N products" wrong by up to ten minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a lie the storefront tells. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The crawler reaching Wildberries from the VPS is a separate question from reaching a registry, and it is still unverified: the outbound restriction has only ever been observed against HTTPS in general. _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- When the topology grows to more than one instance, the aggregates move to a shared cache in the same change that adds the second instance. Doing them separately would leave a window in which the second instance answers from its own empty cache — the exact inconsistency this decision exists to prevent. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_

### `docker`

- The crawler reaching Wildberries from the VPS is a separate question from reaching a registry, and it is still unverified: the outbound restriction has only ever been observed against HTTPS in general. _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the image is cheaper than the alternative — a browser that cannot be found. _(from `2026-10-19-local-debug-stack-repairs`)_

### `documentation`

- Длинное правило в digest — длинная строка. Это цена решения: строка должна читаться целиком, и «прочитать половину» — не чтение. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)_
- Правило без маркера (`Always`, `Never`, `MUST`) попадает в раздел по тегам так же целиком, но в критический раздел не попадает — маркер по-прежнему обязателен. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)_

### `domain`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect of the crawler, and it arrives with a stage, an interpreter and a test. _(from `2026-10-20-the-registry-is-total-by-construction`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- Проверка `stopAt` на границе пагинации теперь вызывается с глубиной текущей задачи. _(from `2026-10-20-pagination-does-not-increase-depth`)_
- Тест `PipelineDedupTest` на самоссылку ждёт одну страницу вместо трёх: очередь опустела, а не упёрлась в лимит. _(from `2026-10-20-pagination-does-not-increase-depth`)_

### `domain-model`

- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled for one page per run; a target that leaves it NULL uses the runner-wide `maxPagesPerCatalog`. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of the crawl's write path and nothing depends on it being present. _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_

### `either`

- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on `Either.Left` must unpack the pair _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `PipelineFailure` third-Either variant remains available as a future option if more metadata needs to travel on failure _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `flyway`

- The gate needs Docker, like the rest of the integration suites, and is skipped by `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently. _(from `2026-10-19-application-startup-gate`)_

### `gate`

- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` job, symmetrically — Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-coverage-floor-gate`)_
- The nightly now costs two workflows a week instead of one: the run itself, and the watchdog that checks it. The watchdog is a few seconds of API calls. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The nightly run costs a full pipeline every day. It is the price of knowing that the repository is still healthy when nobody touches it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a commit that moves a hash. That is the intended cost: the change is visible, reviewable, and reversible. _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The startup gate stays: this gate cannot see a dependency that the framework wires at runtime (`spring-jdbc` was missing, and no bytecode referenced it). _(from `2026-10-19-runtime-classpath-gate`)_

### `gates`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently. _(from `2026-10-19-application-startup-gate`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit is sixty requests an hour, which is fewer than the number of pins here. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_
- Гейт не зарегистрирован в `scripts/ci/static-gates.sh`: ему нужна живая база, и его положительный контроль поднимает PostgreSQL в Docker. Поэтому его контроль ходит по CI через тесты скриптов (`scripts/tests/test_production_schema_gate.py`), а не через реестр. _(from `2026-10-21-the-schema-gate-reads-the-package`)_
- Сторона колонок остаётся копией: `REQUIRED_COLUMNS` в гейте и в `CatalogSchemaQueries.kt` — два списка, и гейт сверяет свой со своими запросами, а не с API. Следующий шаг в эту сторону — брать список колонок из самого API. _(from `2026-10-21-the-schema-gate-reads-the-package`)_

### `infrastructure`

- A profile directory that was never materialised and a profile whose `Preferences` belong to another user are different problems with different fixes, and the pre-flight check names them apart. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user running the AdsPower runtime. A container that mounted `~/.adspowerCli` and ran as root leaves root-owned profile directories and Chrome kernels behind; the host runtime then cannot write `Preferences` and the browser refuses to start with `EACCES`. See `docs/decisions/2026-10-19-local-debug-stack-repairs.md`. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No serialization failures were observed in these runs, so the isolation level was left alone rather than changed on theory. _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is the only thing that will change that, and it needs a profile-per-test fixture CI does not have yet. _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The crawler reaching Wildberries from the VPS is a separate question from reaching a registry, and it is still unverified: the outbound restriction has only ever been observed against HTTPS in general. _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the image is cheaper than the alternative — a browser that cannot be found. _(from `2026-10-19-local-debug-stack-repairs`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own Chrome over CDP, so Playwright's bundled Chromium is never launched; without the flag `Playwright.create()` downloads 161 MB on every browser borrow and re-downloads it on every retry. `deploy/docker-compose.adspower.yml` already had this. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` overrides it for installations that keep their state elsewhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is AdsPower's own and is not reconstructed. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the resolver logs a warning and falls back to scanning. _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the environment and reported by the pre-flight check, but the Local API on loopback is unauthenticated and the key is not sent anywhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake included — must answer `isAvailable()` honestly, or the pool will fall back to the scan when it should have reported a broken profile. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels with the borrow instead of being re-derived at each call site. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. The new concurrency logic was put where it *is* testable: `CrawlRunner`'s bound is verified by a peak-concurrency probe against a real PostgreSQL, including that `target-concurrency = 1` still measures exactly 1, so the probe cannot pass on a runner that ignored the setting. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. Warm-up now belongs to whoever needs a warm page, and is timed by the caller. _(from `2026-10-14-antibot-session-is-site-wide`)_

### `lint`

- The nightly run costs a full pipeline every day. It is the price of knowing that the repository is still healthy when nobody touches it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_

### `migrations`

- The gate compares columns — names, types, nullability. It does not compare defaults, indexes or constraints: the unique index on `(target_id, content_hash)` and the `now()` defaults are described in the migrations and read by the code, and a comparison of them would be a second specification to maintain. _(from `2026-10-19-schema-drift-gate`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно в `scraped_items`. _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_

### `observability`

- The badge stays empty while the repository is not indexed by the API. When it is indexed, the strict comparison starts working on the next run, with no change to the workflow. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)_
- The nightly now costs two workflows a week instead of one: the run itself, and the watchdog that checks it. The watchdog is a few seconds of API calls. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_

### `operations`

- The API answers a request that arrives right after a restart with a value computed from the database at that moment, and the next one with a value computed before the restart. Both are correct answers to "what did the database say". _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is separate and worth stating here: it is 27 ms of a 125 ms request, and caching it would make the number the storefront shows "N products" wrong by up to ten minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a lie the storefront tells. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- When the topology grows to more than one instance, the aggregates move to a shared cache in the same change that adds the second instance. Doing them separately would leave a window in which the second instance answers from its own empty cache — the exact inconsistency this decision exists to prevent. _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут вместе с процессом. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_

### `packaging`

- The startup gate stays: this gate cannot see a dependency that the framework wires at runtime (`spring-jdbc` was missing, and no bytecode referenced it). _(from `2026-10-19-runtime-classpath-gate`)_

### `performance`

- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No serialization failures were observed in these runs, so the isolation level was left alone rather than changed on theory. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels with the borrow instead of being re-derived at each call site. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. The new concurrency logic was put where it *is* testable: `CrawlRunner`'s bound is verified by a peak-concurrency probe against a real PostgreSQL, including that `target-concurrency = 1` still measures exactly 1, so the probe cannot pass on a runner that ignored the setting. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. Warm-up now belongs to whoever needs a warm page, and is timed by the caller. _(from `2026-10-14-antibot-session-is-site-wide`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не браузера. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от старта. Расписание при этом остаётся не-метрономным — jitter прибавляется к каждому следующему интервалу. _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Пока решения нет, цифры живут в ADR `2026-10-20-the-catalog-lists-products-not-observations.md` и пересчитываются тем же скриптом. _(from `2026-10-20-the-materialized-read-model-is-a-separate-decision`)_

### `pipeline`

- A DB blip no longer fails the entire crawl. The save still fails the item (returns 0), but the crawl continues. _(from `2026-09-09-save-stage-retry-pr11`)_
- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one `Side.Drop` reaches the interpreter per rejected item. _(from `2026-10-09-wire-business-rules-into-filter`)_
- A job with no heartbeat and no start time falls back to its creation time — the third branch — so a row that was never touched still ages out. _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for deterministic integration tests. _(from `2026-10-14-pipeline-purity-idgen`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, before the `enrich` stage. Any future rule that needs a field only present on `SavedItem` would have to move to a later stage. _(from `2026-10-09-wire-business-rules-into-filter`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect of the crawler, and it arrives with a stage, an interpreter and a test. _(from `2026-10-20-the-registry-is-total-by-construction`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit is sixty requests an hour, which is fewer than the number of pins here. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_
- The reconciliation stays a single statement over all stale rows, and its result does not depend on the time zone of the machine that runs it. _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, but this is a fundamental property of any suspend-based retry implementation and does not affect testability or debuggability. _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on `Either.Left` must unpack the pair _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `PipelineFailure` third-Either variant remains available as a future option if more metadata needs to travel on failure _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- `retryDelayMs()` reduced from 19 lines to 11 lines _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Проверка `stopAt` на границе пагинации теперь вызывается с глубиной текущей задачи. _(from `2026-10-20-pagination-does-not-increase-depth`)_
- Тест `PipelineDedupTest` на самоссылку ждёт одну страницу вместо трёх: очередь опустела, а не упёрлась в лимит. _(from `2026-10-20-pagination-does-not-increase-depth`)_

### `pr-13`

- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_

### `refactoring`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for deterministic integration tests. _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, but this is a fundamental property of any suspend-based retry implementation and does not affect testability or debuggability. _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `reliability`

- A profile directory that was never materialised and a profile whose `Preferences` belong to another user are different problems with different fixes, and the pre-flight check names them apart. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Jobs left `Running` by a crash are now reported as `Crashed` with an explanatory `error_message`, instead of looking like a running crawl. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The item path writes one statement per item, and the category path one per distinct category. A future change that batches these needs the empty-row behaviour of `addBatch()` handled explicitly — and a test that would fail if it were not. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- The nightly now costs two workflows a week instead of one: the run itself, and the watchdog that checks it. The watchdog is a few seconds of API calls. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` overrides it for installations that keep their state elsewhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is AdsPower's own and is not reconstructed. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The reconciliation runs on every boot. It touches only rows that have been quiet for more than five minutes, so a normal startup changes nothing. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the environment and reported by the pre-flight check, but the Local API on loopback is unauthenticated and the key is not sent anywhere. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake included — must answer `isAvailable()` honestly, or the pool will fall back to the scan when it should have reported a broken profile. _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a debugging session. The crawl's log volume grows by two orders of magnitude while it is on. _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was always null should be revisited. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_

### `resilience`

- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а не сбой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это ожидаемо: краул ходит в чужой API и платит за это скоростью, а не частотой. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это означает настоящий блок, а не мгновенный отказ открытой цепи. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `runtime`

- The startup gate stays: this gate cannot see a dependency that the framework wires at runtime (`spring-jdbc` was missing, and no bytecode referenced it). _(from `2026-10-19-runtime-classpath-gate`)_

### `schedule`

- The nightly now costs two workflows a week instead of one: the run itself, and the watchdog that checks it. The watchdog is a few seconds of API calls. _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_

### `scheduler`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to `PostgresAdvisoryLock`, the crawler's own advisory lock, so multi-instance deployments do not need ShedLock. The ShedLock dependency was removed as unused. _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_

### `scheduling`

- Jobs left `Running` by a crash are now reported as `Crashed` with an explanatory `error_message`, instead of looking like a running crawl. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The reconciliation runs on every boot. It touches only rows that have been quiet for more than five minutes, so a normal startup changes nothing. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was always null should be revisited. _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- Окно свежести остаётся в памяти процесса, поэтому перезапуск приложения делает все цели «просроченными» и первый тик запускает полный краул. Это стоит держать в голове при развёртывании: рестарт = краул. _(from `2026-10-20-schedule-cadence-single-parameter`)_

### `schema`

- The gate compares columns — names, types, nullability. It does not compare defaults, indexes or constraints: the unique index on `(target_id, content_hash)` and the `now()` defaults are described in the migrations and read by the code, and a comparison of them would be a second specification to maintain. _(from `2026-10-19-schema-drift-gate`)_

### `scorecard`

- The pin bumps arrive as pull requests from Dependabot, and each one is a commit that moves a hash. That is the intended cost: the change is visible, reviewable, and reversible. _(from `2026-10-20-the-repository-measures-its-own-posture`)_

### `security`

- The badge stays empty while the repository is not indexed by the API. When it is indexed, the strict comparison starts working on the next run, with no change to the workflow. _(from `2026-10-20-a-missing-score-is-not-a-failed-posture`)_
- The nightly run costs a full pipeline every day. It is the price of knowing that the repository is still healthy when nobody touches it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a commit that moves a hash. That is the intended cost: the change is visible, reviewable, and reversible. _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit is sixty requests an hour, which is fewer than the number of pins here. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role are removed only after the rollback window has closed and a rollback drill has been run — not before. _(from `2026-10-19-catalog-read-api-contract`)_

### `side-effects`

- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on `Either.Left` must unpack the pair _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `PipelineFailure` third-Either variant remains available as a future option if more metadata needs to travel on failure _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `spring`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to `PostgresAdvisoryLock`, the crawler's own advisory lock, so multi-instance deployments do not need ShedLock. The ShedLock dependency was removed as unused. _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently. _(from `2026-10-19-application-startup-gate`)_

### `supply-chain`

- The pin bumps arrive as pull requests from Dependabot, and each one is a commit that moves a hash. That is the intended cost: the change is visible, reviewable, and reversible. _(from `2026-10-20-the-repository-measures-its-own-posture`)_

### `test`

- The gate compares columns — names, types, nullability. It does not compare defaults, indexes or constraints: the unique index on `(target_id, content_hash)` and the `now()` defaults are described in the migrations and read by the code, and a comparison of them would be a second specification to maintain. _(from `2026-10-19-schema-drift-gate`)_

### `testing`

- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in `config/test-runs-floor.tsv`, 10 files, 145 tests. _(from `2026-10-08-gates-that-can-fail`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is the only thing that will change that, and it needs a profile-per-test fixture CI does not have yet. _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same commit. Verified by control: removing `TargetQueriesPostgresTest` leaves Gradle reporting `BUILD SUCCESSFUL` while the floor gate exits 1 with "ran 31 tests, floor is 44". _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both repositories. Verified by control in each: deleting the new suite leaves the test task reporting success while the floor gate exits 1. _(from `2026-10-08-gates-that-can-fail`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently. _(from `2026-10-19-application-startup-gate`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path tests and leaves `TargetQueries` green. Under the old list it would have failed none. _(from `2026-10-08-discover-test-migrations`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share `PostgresFixture`, and both call `db.clear()` from `beforeTest`. The SQLite suites already did; the Postgres ones did not, so their results depended on declaration order. _(from `2026-10-08-discover-test-migrations`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_

### `tests`

- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` job, symmetrically — Part E of `scripts/check-gate-wiring.py` compares the two surfaces. _(from `2026-10-19-coverage-floor-gate`)_

### `tooling`

- A YAML written by an older selector has no `root_category_name`; run `just catalog::fill-roots` before loading it. Pages missing from the menu tree are reported by name and left alone rather than guessed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- The nightly run costs a full pipeline every day. It is the price of knowing that the repository is still healthy when nobody touches it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_

### `types`

- The `Side` hierarchy now has three variants. A fourth one is a new effect of the crawler, and it arrives with a stage, an interpreter and a test. _(from `2026-10-20-the-registry-is-total-by-construction`)_

### `workflow`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- Длинное правило в digest — длинная строка. Это цена решения: строка должна читаться целиком, и «прочитать половину» — не чтение. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)_
- Правило без маркера (`Always`, `Never`, `MUST`) попадает в раздел по тегам так же целиком, но в критический раздел не попадает — маркер по-прежнему обязателен. _(from `2026-10-21-the-digest-keeps-the-whole-rule`)_


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
- `2026-10-19-admin-api-target-write-path-and-category-tree` — api  database  architecture
- `2026-10-19-adspower-api-first-browser-resolution` — browser  infrastructure  reliability
- `2026-10-19-adspower-local-api-port-discovery` — browser  infrastructure  debugging
- `2026-10-19-advisory-lock-timeout` — concurrency  runtime  correctness
- `2026-10-19-application-startup-gate` — testing  gates  spring  database  flyway
- `2026-10-19-catalog-read-api-contract` — catalog  api  database  security  contract
- `2026-10-19-coverage-floor-gate` — build  tests  gate  coverage
- `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation` — database  scheduling  reliability
- `2026-10-19-dependency-analysis-plugin` — build  dependencies  gates  ci
- `2026-10-19-explicit-module-boundary` — build  architecture  dependencies  spring
- `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors` — database  reliability  debugging
- `2026-10-19-idempotent-target-load-and-root-categories` — catalog  data  tooling
- `2026-10-19-jdk-toolchain` — build  toolchain  ci  docker
- `2026-10-19-local-debug-stack-repairs` — docker  debugging  infrastructure
- `2026-10-19-runtime-classpath-gate` — build  runtime  gate  packaging
- `2026-10-19-schema-drift-gate` — database  schema  test  migrations
- `2026-10-19-subject-dictionary-and-known-pages-limit` — catalog  data  domain-model
- `2026-10-20-a-missing-score-is-not-a-failed-posture` — ci  security  observability
- `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked` — pipeline  security  ci  gates
- `2026-10-20-a-popular-list-is-not-a-catalog` — crawler  data-quality  catalog  api
- `2026-10-20-a-schedule-that-does-not-run-is-a-failure` — ci  reliability  schedule  observability  gate
- `2026-10-20-anti-bot-refusals-are-measured` — crawler  antibot  metrics  pipeline
- `2026-10-20-antibot-profile-rotation-and-backoff` — crawler  resilience  adspower
- `2026-10-20-browsers-are-closed-when-they-are-not-needed` — crawler  browser  adspower  operations
- `2026-10-20-catalog-aggregate-cache-is-in-process` — catalog  api  cache  operations  deployment
- `2026-10-20-ci-security-gates-and-lint-autofix` — ci  security  tooling  gate  lint
- `2026-10-20-contract-versions-are-paths` — catalog  contract
- `2026-10-20-coverage-is-measured-where-the-code-lives` — build  tests  gate  coverage
- `2026-10-20-job-timestamps-carry-an-offset` — database  pipeline
- `2026-10-20-pagination-does-not-increase-depth` — pipeline  domain
- `2026-10-20-schedule-cadence-single-parameter` — scheduling  configuration
- `2026-10-20-subject-dictionary-loaded-at-startup` — bootstrap  data-quality  crawler
- `2026-10-20-the-big-classes-are-split-by-decision` — architecture  refactoring  maintainability
- `2026-10-20-the-catalog-is-opened-by-a-procedure` — catalog  operations  database
- `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary` — crawler  catalog  data-quality  operations
- `2026-10-20-the-catalog-lists-products-not-observations` — catalog  database  contract
- `2026-10-20-the-counters-measure-different-things` — catalog  contract  observability
- `2026-10-20-the-coverage-floor-measures-the-code-not-the-module` — build  tests  gate  coverage
- `2026-10-20-the-crawl-cadence-follows-the-measured-catalog` — crawler  scheduling  configuration  catalog
- `2026-10-20-the-crawl-reports-its-own-limits` — crawler  data-quality  catalog  operations
- `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace` — deployment  infrastructure  docker  crawler
- `2026-10-20-the-materialized-read-model-is-a-separate-decision` — catalog  performance  database
- `2026-10-20-the-measurement-travels-with-the-limit` — crawler  catalog  data-quality  operations  tooling
- `2026-10-20-the-registry-is-total-by-construction` — pipeline  architecture  domain  types
- `2026-10-20-the-repository-measures-its-own-posture` — ci  security  supply-chain  scorecard  gate
- `2026-10-20-the-request-rate-has-one-owner` — crawler  antibot  browser  performance
- `2026-10-20-versioned-catalog-and-latest-observation-view` — catalog  database  migrations
- `2026-10-20-wb-catalog-api-endpoint-and-token` — crawler  api  data-quality
- `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too` — crawler  diagnostics  catalog  parsing
- `2026-10-21-a-version-nobody-published-is-not-a-version` — dependencies  build  workflow
- `2026-10-21-egress-groups-are-the-unit-of-rate-limiting` — infrastructure  browser  antibot  architecture
- `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested` — browser  tests  coverage  diagnostics
- `2026-10-21-one-decision-is-one-pull-request` — workflow  conventions
- `2026-10-21-one-state-for-the-egress-group` — infrastructure  antibot  operations
- `2026-10-21-the-answer-is-read-strictly` — crawler  parsing  data-quality
- `2026-10-21-the-answer-says-who-answered-and-when-the-data-is` — catalog  contract  observability
- `2026-10-21-the-answer-that-does-not-parse-is-a-failure` — crawler  diagnostics  catalog  pipeline
- `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold` — architecture  refactoring  maintainability
- `2026-10-21-the-cross-repo-compatibility-is-a-report` — ci  workflow  contract
- `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer` — diagnostics  api  architecture
- `2026-10-21-the-digest-keeps-the-whole-rule` — documentation  workflow
- `2026-10-21-the-failure-keeps-what-was-read` — crawler  pipeline  observability
- `2026-10-21-the-number-wb-reports-reaches-the-report` — crawler  reporting  catalog  data-quality
- `2026-10-21-the-parse-failure-names-the-field` — crawler  diagnostics  catalog  api
- `2026-10-21-the-profile-id-is-read-too` — adspower  api  tests
- `2026-10-21-the-read-model-is-not-needed-yet` — catalog  performance  database  measurement
- `2026-10-21-the-repository-is-licensed` — legal  project  governance
- `2026-10-21-the-schema-gate-reads-the-package` — gates  database  architecture
- `2026-10-22-egress-groups-are-declared-and-checked` — infrastructure  browser  antibot  operations
- `2026-10-23-a-changed-address-is-a-new-identity` — infrastructure  antibot  database  operations
- `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault` — infrastructure  antibot  observability  operations
- `2026-10-25-the-work-and-the-budget-belong-where-they-are-known` — architecture  database  concurrency  antibot
- `2026-10-26-the-catalog-reads-from-a-replica` — architecture  database  operations  catalog
- `2026-10-27-the-measurement-does-not-authorize-a-second-address` — antibot  operations  infrastructure  measurement
- `2026-10-28-the-scheduler-asks-the-crawl-one-question` — architecture  testing  scheduling
- `2026-10-29-the-address-is-asked-before-the-work-is-taken` — architecture  concurrency  antibot  database
- `2026-10-30-an-empty-observation-changes-nothing` — infrastructure  antibot  operations  database
- `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk` — infrastructure  build  operations
- `2026-11-01-one-crawler-many-addresses` — architecture  egress  operations  antibot
- `2026-11-02-the-protocol-runs-from-the-repository` — operations  measurement  antibot
- `2026-11-03-the-decision-belongs-to-the-dependency` — workflow  dependencies  conventions
- `2026-11-04-the-refusal-is-a-count-against-the-session` — antibot  egress  architecture  measurement
- `2026-11-05-the-router-file-holds-the-routing` — architecture  refactoring  infrastructure

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
- `2026-10-19-admin-api-target-write-path-and-category-tree` — Admin API — one Target mapping, a write path, and the category tree
- `2026-10-19-adspower-api-first-browser-resolution` — AdsPower — API-first browser resolution, one client, and a pre-flight check
- `2026-10-19-adspower-local-api-port-discovery` — AdsPower Local API — runtime port discovery instead of a hardcoded port
- `2026-10-19-advisory-lock-timeout` — The lock timeout bounds the wait, not the work
- `2026-10-19-application-startup-gate` — The application must be startable, and something must prove it
- `2026-10-19-catalog-read-api-contract` — The storefront reads the catalog through the crawler's HTTP API
- `2026-10-19-coverage-floor-gate` — The coverage floor lives in one file, and the gate reads the report
- `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation` — Crawl jobs — heartbeat and crash reconciliation
- `2026-10-19-dependency-analysis-plugin` — Dependency drift is a gate, not a reviewer's job
- `2026-10-19-explicit-module-boundary` — The boundary between the crawler and the application is declared, not implied
- `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors` — Exposed writes are one statement per row, and database errors are never masked
- `2026-10-19-idempotent-target-load-and-root-categories` — Loading targets is idempotent, and root categories come from WB's public tree
- `2026-10-19-jdk-toolchain` — The JDK version is one number in one place, and the toolchain provisions itself
- `2026-10-19-local-debug-stack-repairs` — Local debug stack — paths, ports and the runtime classpath the app actually needs
- `2026-10-19-runtime-classpath-gate` — The image is compared with the classpath the build resolved
- `2026-10-19-schema-drift-gate` — The table definitions are compared with the schema the migrations create
- `2026-10-19-subject-dictionary-and-known-pages-limit` — Category names come from WB's subject dictionary, and known_pages_limit is a limit
- `2026-10-20-a-missing-score-is-not-a-failed-posture` — A missing score is not a failed posture
- `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked` — A pin is a commit, and a publication is checked, not assumed
- `2026-10-20-a-popular-list-is-not-a-catalog` — Популярная ротация WB — не каталог: её не сохраняют
- `2026-10-20-a-schedule-that-does-not-run-is-a-failure` — A schedule that does not run is a failure, and a CI failure should be about the code
- `2026-10-20-anti-bot-refusals-are-measured` — Отказы антибота измеряются и получают собственный back-off
- `2026-10-20-antibot-profile-rotation-and-backoff` — Антибот: ротация профиля, свой back-off и ожидание вместо отказа
- `2026-10-20-browsers-are-closed-when-they-are-not-needed` — Браузеры AdsPower закрываются, когда они не нужны, и при выходе
- `2026-10-20-catalog-aggregate-cache-is-in-process` — The catalog's aggregates are cached in the process, and that shapes the topology
- `2026-10-20-ci-security-gates-and-lint-autofix` — The repository's CI surface is CodeQL, Scorecard, a nightly run that opens an issue, and a lint auto-fix
- `2026-10-20-contract-versions-are-paths` — Версия контракта — это путь, а не поле
- `2026-10-20-coverage-is-measured-where-the-code-lives` — Покрытие меряется там, где живёт код, и по всему репозиторию сразу
- `2026-10-20-job-timestamps-carry-an-offset` — Job timestamps are written as offsets, not as naive local times
- `2026-10-20-pagination-does-not-increase-depth` — Пагинация не увеличивает глубину краула
- `2026-10-20-schedule-cadence-single-parameter` — Каденция краула задаётся одним параметром — окном свежести
- `2026-10-20-subject-dictionary-loaded-at-startup` — Справочник предметов WB загружается при старте приложения
- `2026-10-20-the-big-classes-are-split-by-decision` — The three large classes are split by the decision they hold, not by the layer they live in
- `2026-10-20-the-catalog-is-opened-by-a-procedure` — The catalog is opened by a procedure, and the schema is never rolled back
- `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary` — the catalog length is measured at the pagination boundary, and the limit follows it
- `2026-10-20-the-catalog-lists-products-not-observations` — Каталог показывает товары, а не наблюдения
- `2026-10-20-the-counters-measure-different-things` — Счётчики измеряют разные вещи, и ось названа в имени
- `2026-10-20-the-coverage-floor-measures-the-code-not-the-module` — Покрытие меряется по коду, а не по модулю, которому он принадлежит
- `2026-10-20-the-crawl-cadence-follows-the-measured-catalog` — the crawl cadence follows the measured catalog
- `2026-10-20-the-crawl-reports-its-own-limits` — Краул показывает свои границы: усечение каталога, токены, пересборка данных
- `2026-10-20-the-crawler-and-its-browser-share-one-network-namespace` — the crawler and its browser share one network namespace
- `2026-10-20-the-materialized-read-model-is-a-separate-decision` — Материализованная модель каталога — это отдельное решение, и вот какое
- `2026-10-20-the-measurement-travels-with-the-limit` — the measurement travels with the limit: the metadata belongs to the target, the evidence belongs to the repository
- `2026-10-20-the-registry-is-total-by-construction` — The registry is total by construction, and a side nobody emits is not a side
- `2026-10-20-the-repository-measures-its-own-posture` — The repository measures its own posture, and the actions it runs are pinned
- `2026-10-20-the-request-rate-has-one-owner` — Темп запросов задаёт pacer — один, а не два
- `2026-10-20-versioned-catalog-and-latest-observation-view` — Каталог целей версионируется в репозитории, последнее наблюдение — во view
- `2026-10-20-wb-catalog-api-endpoint-and-token` — Каталог WB читается через u-search/exactmatch v18 с токеном меню
- `2026-10-21-a-rename-inside-a-wrapper-is-a-change-of-shape-too` — Переименование ключа внутри обёртки — тоже изменение формы
- `2026-10-21-a-version-nobody-published-is-not-a-version` — Версия, которой никто не публиковал, — это не версия
- `2026-10-21-egress-groups-are-the-unit-of-rate-limiting` — Egress-группа — единица ограничения, а не профиль
- `2026-10-21-every-branch-of-the-browser-path-that-names-the-failure-is-tested` — Каждая ветвь браузерного пути, которая говорит краулу «что сломалось», проверена
- `2026-10-21-one-decision-is-one-pull-request` — Одно решение — один pull request, и он перебазирован на свежий main
- `2026-10-21-one-state-for-the-egress-group` — Состояние egress-группы — один ответ, а не три
- `2026-10-21-the-answer-is-read-strictly` — Ответ читается строго: нестандартный JSON — это сбой с именем поля
- `2026-10-21-the-answer-says-who-answered-and-when-the-data-is` — Ответ говорит, кто ответил и о каком моменте данные
- `2026-10-21-the-answer-that-does-not-parse-is-a-failure` — Ответ, который не разобрался, — это сбой, а не конец каталога
- `2026-10-21-the-catalogs-reads-live-in-the-decisions-they-hold` — Запросы каталога живут в решениях, которые они держат, а фасад держит поверхность
- `2026-10-21-the-cross-repo-compatibility-is-a-report` — Кросс-репозиторная совместимость — это отчёт, а не блокирующий гейт
- `2026-10-21-the-diagnostic-lives-in-one-place-and-reads-every-answer` — Диагностика разбора живёт в одном месте и читает все ответы WB
- `2026-10-21-the-digest-keeps-the-whole-rule` — Правило попадает в digest целиком, а не по первой строке
- `2026-10-21-the-failure-keeps-what-was-read` — Отказ не выбрасывает прочитанное
- `2026-10-21-the-number-wb-reports-reaches-the-report` — Число, которое называет WB, доходит до отчёта — под своим именем и без приписки полноты
- `2026-10-21-the-parse-failure-names-the-field` — Ошибка разбора называет поле, а не страницу
- `2026-10-21-the-profile-id-is-read-too` — Имя поля профиля читается в обеих написаниях
- `2026-10-21-the-read-model-is-not-needed-yet` — Материализованная read-модель каталога пока не нужна — вот цифры, по которым это решено
- `2026-10-21-the-repository-is-licensed` — Публичный репозиторий лицензирован Apache-2.0
- `2026-10-21-the-schema-gate-reads-the-package` — Гейт совместимости схемы читает пакет решений каталога, а не один файл
- `2026-10-22-egress-groups-are-declared-and-checked` — Egress-группы объявляются и проверяются, а не угадываются
- `2026-10-23-a-changed-address-is-a-new-identity` — Сменившийся адрес — это новая личность, а прогон оставляет след
- `2026-10-24-the-crawlers-capacity-is-not-the-targets-fault` — Мощность краулера — не вина цели, и она видна
- `2026-10-25-the-work-and-the-budget-belong-where-they-are-known` — Работа и бюджет живут там, где про них известно
- `2026-10-26-the-catalog-reads-from-a-replica` — Каталог читает с реплики, краулер пишет в primary
- `2026-10-27-the-measurement-does-not-authorize-a-second-address` — Измерение не даёт оснований покупать второй адрес
- `2026-10-28-the-scheduler-asks-the-crawl-one-question` — Планировщик спрашивает обход об одном и говорит, как он прошёл
- `2026-10-29-the-address-is-asked-before-the-work-is-taken` — Адрес спрашивают раньше, чем берут работу
- `2026-10-30-an-empty-observation-changes-nothing` — Пустое наблюдение не меняет строку леджера
- `2026-10-31-the-image-and-the-build-ask-for-the-same-jdk` — Образ и сборка просят одну и ту же версию JDK
- `2026-11-01-one-crawler-many-addresses` — Один краулер — много адресов
- `2026-11-02-the-protocol-runs-from-the-repository` — Правило протокола живёт в репозитории, а не в памяти оператора
- `2026-11-03-the-decision-belongs-to-the-dependency` — Решение принадлежит зависимости, а не номеру pull request'а
- `2026-11-04-the-refusal-is-a-count-against-the-session` — Отказ — это счётчик, и код считает его по сессии
- `2026-11-05-the-router-file-holds-the-routing` — Файл роутера содержит только маршрутизацию, а типы состояния живут по одному на файл
