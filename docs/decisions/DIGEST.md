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
- **Always** keep request construction in the `infra/http` request objects, not back inside the _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** give a Kover-exercised DTO a required field that a single malformed API entry can _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Never** solve a coverage drop on untestable adapters by excluding them. Extract the logic _(from `2026-10-14-extract-browser-request-logic-for-testability`)
- **Always** keep `target-concurrency` low enough to leave connections for the save path. Each _(from `2026-10-14-parallel-crawl-targets`)
- **Always** open as many AdsPower profiles as `target-concurrency`, or lower the setting. The _(from `2026-10-14-parallel-crawl-targets`)
- **Never** read a pool-exhaustion error as evidence that WB blocked us. It means no slot freed _(from `2026-10-14-parallel-crawl-targets`)
- **Never** set `target-concurrency` from a guess about how many browsers are open. The pool _(from `2026-10-14-parallel-crawl-targets`)
- **Always** inject `BrowserAutomation` via constructor for testability — never call `Playwright` directly in `ProfileState`. _(from `2026-10-17-browser-automation-interface`)
- **Always** inject `AdsPowerApi` via constructor for testability — never call `HttpURLConnection` directly in `ProfileState`. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)
- **Always** handle back-off delays at the coordinator level (inside `Pipeline.run()` or `processItems`) — never inside `async {}`. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)
- **Always** read targets through `TargetQueries.kt`. The admin API has no SQL of _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)
- **Never** add a second `ResultSet` → DTO mapping for a table the repository _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)
- **Always** consult the Local API before falling back to the port scan, and _(from `2026-10-19-adspower-api-first-browser-resolution`)
- **Never** run AdsPower under `sudo`. A profile created by such a run keeps a _(from `2026-10-19-adspower-api-first-browser-resolution`)
- **Always** reach AdsPower through the host's loopback. In Docker that means _(from `2026-10-19-adspower-local-api-port-discovery`)
- **Never** treat a failing `checkActiveV2()` as "the browser is gone". It may mean the port _(from `2026-10-19-adspower-local-api-port-discovery`)
- **Always** carry the reason a run was skipped to the operator: a scheduled _(from `2026-10-19-advisory-lock-timeout`)
- **Always** state which one a timeout bounds. "Lock acquisition timed out" and _(from `2026-10-19-advisory-lock-timeout`)
- **Always** take and release an advisory lock on the same connection. _(from `2026-10-19-advisory-lock-timeout`)
- **Never** treat a timeout of zero as "no waiting". Zero means wait forever, _(from `2026-10-19-advisory-lock-timeout`)
- **Never** wrap the body of a lock-scoped block in the acquisition timeout. A _(from `2026-10-19-advisory-lock-timeout`)
- **Always** keep the startup gate green: `./check.sh` runs it as part of `tests:test`, and a _(from `2026-10-19-application-startup-gate`)
- **Always** supply `ADMIN_API_KEY` to the test JVM as an environment variable. The filter reads _(from `2026-10-19-application-startup-gate`)
- **Never** add a dependency that is needed only at runtime to `implementation` when the _(from `2026-10-19-application-startup-gate`)
- **Never** make the startup gate depend on a browser, the network or AdsPower. The `public` _(from `2026-10-19-application-startup-gate`)
- **Never** weaken the migration assertion to a fixed list of versions. The expected set is _(from `2026-10-19-application-startup-gate`)
- **Always** check the effective rights of the catalog role, not the grants _(from `2026-10-19-catalog-read-api-contract`)
- **Always** keep the catalog's pool separate from the crawler's. The crawl is _(from `2026-10-19-catalog-read-api-contract`)
- **Always** read the catalog through the API, and let the API own the SQL. The _(from `2026-10-19-catalog-read-api-contract`)
- **Always** rotate the catalog key on a schedule, and rotate it immediately if _(from `2026-10-19-catalog-read-api-contract`)
- **Never** change the contract from the storefront's side. The direction is _(from `2026-10-19-catalog-read-api-contract`)
- **Never** deploy the API publicly. The route stays closed at the proxy, and _(from `2026-10-19-catalog-read-api-contract`)
- **Never** give the storefront database credentials again. A fallback that reads _(from `2026-10-19-catalog-read-api-contract`)
- **Never** weaken `wb_catalog_ro` to make something work. If the catalog needs _(from `2026-10-19-catalog-read-api-contract`)
- **Always** give every module with tests a floor in `config/gates/coverage-floor.txt` _(from `2026-10-19-coverage-floor-gate`)
- **Always** keep the coverage floor in `config/gates/coverage-floor.txt` and _(from `2026-10-19-coverage-floor-gate`)
- **Always** keep the floor at least a percentage point below the measurement. _(from `2026-10-19-coverage-floor-gate`)
- **Always** raise the floor in the same commit that raises the coverage, with _(from `2026-10-19-coverage-floor-gate`)
- **Never** lower a coverage floor without `--allow-drop '<reason>'`, and never _(from `2026-10-19-coverage-floor-gate`)
- **Never** rewrite a floor file from a partial run. If the results are not _(from `2026-10-19-coverage-floor-gate`)
- **Never** treat a missing coverage report as zero coverage. It is an absent _(from `2026-10-19-coverage-floor-gate`)
- **Always** write the heartbeat while a crawl runs. A job row that never _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)
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
- **Always** log the exception, not only its message, at the point where the _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Always** write through one statement per row. A multi-row _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Never** let a logging path stand between a failure and its report. If a _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)
- **Always** load through `just catalog::load-confirm` (or the script directly). _(from `2026-10-19-idempotent-target-load-and-root-categories`)
- **Never** reintroduce a blind `INSERT` into the loader. A load that creates a _(from `2026-10-19-idempotent-target-load-and-root-categories`)
- **Always** keep the CI and the Docker image on the same JDK version as the _(from `2026-10-19-jdk-toolchain`)
- **Always** set the JDK through the toolchain in the root build's `subprojects { }` _(from `2026-10-19-jdk-toolchain`)
- **Never** bump one surface alone. The version, `ci.yml`, `Dockerfile.app` and _(from `2026-10-19-jdk-toolchain`)
- **Never** pin `org.gradle.java.home` in a committed `gradle.properties`. A path that _(from `2026-10-19-jdk-toolchain`)
- **Always** build the jar before `docker compose up` — the debug image does not build it. _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** add a second local compose file. There is one stack, and it is _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** assume a relative path in a compose file is relative to where you ran the _(from `2026-10-19-local-debug-stack-repairs`)
- **Never** mount a directory over `/app/app.jar`. _(from `2026-10-19-local-debug-stack-repairs`)
- **Always** compare the artifact that ships with the classpath the build _(from `2026-10-19-runtime-classpath-gate`)
- **Always** read `jdeps` output rather than its exit code. It reports missing _(from `2026-10-19-runtime-classpath-gate`)
- **Never** ship a class twice. A class that exists both loose and in a jar can _(from `2026-10-19-runtime-classpath-gate`)
- **Never** trust `gradle dependencies` output as a classpath. It collapses _(from `2026-10-19-runtime-classpath-gate`)
- **Always** change the model and the migration in the same commit when a column _(from `2026-10-19-schema-drift-gate`)
- **Always** write instants through a zoned column type. A naive timestamp in a _(from `2026-10-19-schema-drift-gate`)
- **Never** change the schema to match the model. The model is the description; _(from `2026-10-19-schema-drift-gate`)
- **Never** write a table with hand-written SQL while the model describes the _(from `2026-10-19-schema-drift-gate`)
- **Always** load the dictionary before a crawl that is expected to produce _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)
- **Never** write a name that did not come from the dictionary. A null name is _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)
- **Always** check that a publication reached the API. A green run that _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** declare a top-level `permissions:` in every workflow. _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** pin an action to a commit, and verify that the pin is a commit _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Never** pass an input an action does not declare. It is a warning on _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Never** read a pin as a commit because it is forty characters long. A _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)
- **Always** прогонять `enrich_catalog_queries.py` перед загрузкой каталога в БД. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Always** сигналить о таком ответе метрикой и предупреждением: протухший токен виден только _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** грузить в БД цель без `wb_search_query`. _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** сохранять ответ с `catalog_type = "presets"`, даже если он выглядит как обычная _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Never** считать такую страницу пройденной страницей каталога: счётчик страниц должен _(from `2026-10-20-a-popular-list-is-not-a-catalog`)
- **Always** cache Gradle in the CI. Six jobs pulling the same graph from _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Always** treat "the schedule did not run" as a failure. The watchdog _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Never** count a manual run as proof that the schedule works. The _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Never** let a watchdog open a second issue about the same thing. One _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)
- **Always** отображать `NetworkError.isAntibot` в `Retry.Antibot`. Серверная ошибка и отказ _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** передавать `waiter` в `Pipeline` явно: значение по умолчанию у шага ожидания означал _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** считать долю отказов как `crawl.antibot.responses / crawl.requests`. Без знаменателя _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** вызывать `delay()` в домене. Ожидание — через `Waiter`. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** добавлять новые значения `reason` свободным текстом: набор закрытый. _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Never** регистрировать интерпретатор лога вручную: отсутствие лога — это не повод упасть в _(from `2026-10-20-anti-bot-refusals-are-measured`)
- **Always** держать темп ниже порога WB; «сначала попробуем быстро» — это ровно то, что _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Always** помечать профиль на паузу при антиботе, а не просто повторять тот же запрос. _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Never** тратить весь бюджет попыток на запросы к открытой цепи: пауза и back-off _(from `2026-10-20-antibot-profile-rotation-and-backoff`)
- **Always** держать настройки `browser-idle-timeout-ms`, `browser-janitor-interval-ms` и _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** закрывать простаивающие профили: браузер, которым никто не пользуется, не _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** останавливать браузер в AdsPower, а не только закрывать CDP-соединение: _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Never** оставлять браузер открытым только потому, что краул его не открывал, а _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Never** ронять выключение из-за того, что AdsPower не ответил. _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)
- **Always** treat a value that is older than ten minutes as "as of ten minutes _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)
- **Never** run two crawler instances behind one address while the aggregates are _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)
- **Always** define the pipeline once and call it. The schedule is an entry _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** discover the modules from `settings.gradle.kts` rather than _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** give every job of a callable pipeline its own `permissions`. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** keep `security-events: write` on CodeQL and `id-token: write` _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** keep the CI workflows wired, and keep the gate that proves it. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** pin an action to a commit that exists. `ossf/scorecard-action@v2` _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** run the corrector as one Gradle invocation per module and one _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** take the report from the run's API. A hand-written list of the _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** give a health check the permissions of a release *by accident*. _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** let the auto-fix decide whether the tree is acceptable. It _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** push the auto-fix onto a branch somebody is working on. The _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** put the corrector in the gate registry. It is not a gate: it _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Never** report a scheduled failure anywhere else than the issue. A red _(from `2026-10-20-ci-security-gates-and-lint-autofix`)
- **Always** добавлять новое поле, а не переименовывать старое, когда меняется только _(from `2026-10-20-contract-versions-are-paths`)
- **Always** объявлять устаревание в самом контракте (`deprecated: true`) и писать, в _(from `2026-10-20-contract-versions-are-paths`)
- **Always** проверять, что потребитель, который знает только текущую версию, продолжает _(from `2026-10-20-contract-versions-are-paths`)
- **Never** менять смысл существующего поля в том же контракте: это ломающее изменение, _(from `2026-10-20-contract-versions-are-paths`)
- **Always** объявлять merged report в root-проекте: без него отчёт модуля `tests` _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** писать интеграционный тест рядом с кодом, который он проверяет, а не в _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** поднимать floor покрытия тем же коммитом, который поднял покрытие _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Never** дублировать DDL в тестах: схема строится из модели, соответствие миграциям _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Never** считать, что отчёт модуля содержит классы зависимостей: он содержит только _(from `2026-10-20-coverage-is-measured-where-the-code-lives`)
- **Always** bind a transaction to the DataSource it belongs to — _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Always** write `crawl_jobs` timestamps through Exposed with an `OffsetDateTime`. _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Never** use `java.sql.Timestamp` for a `TIMESTAMPTZ` column: it is a naive _(from `2026-10-20-job-timestamps-carry-an-offset`)
- **Always** считать `max_depth` глубиной по дереву категорий, а не лимитом страниц. _(from `2026-10-20-pagination-does-not-increase-depth`)
- **Never** использовать `max_depth` для ограничения числа страниц каталога: лимит _(from `2026-10-20-pagination-does-not-increase-depth`)
- **Always** задавать каденцию через `CRAWLER_SCHEDULE_FRESHNESS_MINUTES` (в коде — _(from `2026-10-20-schedule-cadence-single-parameter`)
- **Never** считать cron выражением каденции: это тик. _(from `2026-10-20-schedule-cadence-single-parameter`)
- **Always** держать в `wb_subjects` полный словарь; он обновляется при старте, а не при _(from `2026-10-20-subject-dictionary-loaded-at-startup`)
- **Never** выдумывать имя предмета, если id нет в словаре: `catalog_name` остаётся _(from `2026-10-20-subject-dictionary-loaded-at-startup`)
- **Always** keep a data class for the knobs of a class with many parameters. Detekt does not _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** keep the request construction of the browser fetch in the `infra/http` request _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** split a class by the decision it holds, not by the layer it sits in. A new unit _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Never** give a split unit a second copy of state the original owned. The batch shares the _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Never** move a piece of behaviour into a collaborator without carrying the state it _(from `2026-10-20-the-big-classes-are-split-by-decision`)
- **Always** измерять `known_pages_limit` до того, как его выставлять. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** перезамерять каталог, а не достраивать лимит вручную: пагинация WB _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** указывать в отчёте, чем закончилась пагинация (`catalog_end` или `api_cap`). _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** выставлять `known_pages_limit` выше предела API: страница выше приходит _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** записывать в YAML число, которого нет в отчёте измерения. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Never** принимать `total` за длину каталога: это размер выдачи поиска. _(from `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary`)
- **Always** держать модель и представление `latest_product_observations` в согласии — _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** отдавать `asOf` вместе с кэшированным агрегатом. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** считать, что список каталога — это товары, а `scraped_items` — их история. _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Never** применять фильтр по цене к строкам, а не к модели: товар, который стоил _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Never** считать «количество товаров» количеством строк: это количество наблюдений, _(from `2026-10-20-the-catalog-lists-products-not-observations`)
- **Always** держать гейт покрытия и ратчет полов на одном измерении: ратчет, который _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Always** мерить строку floors по merged-отчёту с фильтром по пакетам модуля. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Never** выводить «покрытие модуля» из отчёта, который этот модуль не видел. _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Never** читать из отчёта модуля вывод о покрытии приложения: в этом репозитории _(from `2026-10-20-the-coverage-floor-measures-the-code-not-the-module`)
- **Always** держать `docs/RUNBOOK.md` в состоянии, в котором команды из него работают. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** пересобирать данные процедурой `rebuild_prod_data.sh`, а не ручными _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** показывать усечение каталога отдельным счётчиком: полнота данных не должна _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** прогонять `refresh_catalog_tokens.py` перед загрузкой каталога в БД. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** выставлять `known_pages_limit` по круглому числу: сначала измерить длину _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** грузить в БД цель без `wb_search_query`. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Never** оставлять в каталоге поле, которое никто не читает. _(from `2026-10-20-the-crawl-reports-its-own-limits`)
- **Always** give every `Side` variant a non-null interpreter. The registry _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Always** keep the no-op defaults for `Side.Log` and `Side.Metric`. A _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Never** add a `Side` variant that no stage emits. If the effect is not _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Never** make a side's interpreter nullable to avoid touching a call site. _(from `2026-10-20-the-registry-is-total-by-construction`)
- **Always** keep the security policy's scope narrow and honest. A policy _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** pin an action to a commit and keep the version in a comment. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** protect `main` with the pipeline's own gates as the required _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Never** let a score be the thing that is maintained. The scorecard _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Never** require approving reviews in a repository with one maintainer. _(from `2026-10-20-the-repository-measures-its-own-posture`)
- **Always** задавать темп через `RequestPacer`. Второй источник задержки делает интервал _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Always** клампить границы jitter в ноль: отрицательная задержка — это падение, а не «быстрее». _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Never** ждать перед запросом в `BrowserDownloader`: pacer уже это сделал, а лишнее ожидание _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Never** убирать jitter, считая его бесплатным: он примерно треть времени между запросами и _(from `2026-10-20-the-request-rate-has-one-owner`)
- **Always** держать `wb_catalog_targets.yaml` в репозитории и прогонять _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)
- **Never** полагаться на YAML как на единственный источник целей: в БД они всё равно _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)
- **Always** брать категорию из `crawl_targets.wb_search_query`; пустое значение — отказ, _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Always** применять `scripts/wb-catalog/enrich_catalog_queries.py` к YAML перед _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Never** считать, что глубина ограничивает число страниц: `max_depth` — это глубина по _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)
- **Never** читать `nextPage` из `payload` как единственный источник пагинации: у v18 его _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)

## Per-tag

### `G10`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_

### `PR-11-5`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `adspower`

- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `ansible`

- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_

### `antibot`

- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от _(from `2026-10-20-the-request-rate-has-one-owner`)_

### `api`

- Deleting a target through the admin API deletes its scraped items and category _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- The API answers a request that arrives right after a restart with a value computed _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role _(from `2026-10-19-catalog-read-api-contract`)_
- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- When the topology grows to more than one instance, the aggregates move to a shared _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_

### `architecture`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Deleting a target through the admin API deletes its scraped items and category _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Dispatcher threads are never blocked by retry back-off. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor _(from `2026-10-17-stage-data-class-dsl`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- New `stage()` DSL makes stage bodies read like domain language _(from `2026-10-17-stage-data-class-dsl`)_
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect _(from `2026-10-20-the-registry-is-total-by-construction`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places _(from `2026-10-17-stage-data-class-dsl`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt` _(from `2026-10-17-coordinator-extraction`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage` _(from `2026-10-17-stage-data-class-dsl`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_

### `arrow`

- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_

### `behaviour`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_

### `bootstrap`

- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_

### `browser`

- A profile directory that was never materialised and a profile whose _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user _(from `2026-10-19-adspower-local-api-port-discovery`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от _(from `2026-10-20-the-request-rate-has-one-owner`)_

### `bug-fix`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_

### `build`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` _(from `2026-10-19-coverage-floor-gate`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_
- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `cache`

- The API answers a request that arrives right after a restart with a value computed _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- When the topology grows to more than one instance, the aggregates move to a shared _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_

### `catalog`

- A YAML written by an older selector has no `root_category_name`; run _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- The API answers a request that arrives right after a restart with a value computed _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role _(from `2026-10-19-catalog-read-api-contract`)_
- When the topology grows to more than one instance, the aggregates move to a shared _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_

### `ci`

- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_
- The nightly now costs two workflows a week instead of one: the run _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The nightly run costs a full pipeline every day. It is the price of _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_

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
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_

### `configuration`

- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- Окно свежести остаётся в памяти процесса, поэтому перезапуск приложения делает все цели _(from `2026-10-20-schedule-cadence-single-parameter`)_

### `contract`

- The schema copy in the storefront, its `DATABASE_URL` and its read-only role _(from `2026-10-19-catalog-read-api-contract`)_

### `conventions`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_

### `coverage`

- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` _(from `2026-10-19-coverage-floor-gate`)_

### `crawler`

- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `data`

- A YAML written by an older selector has no `root_category_name`; run _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_

### `data-quality`

- WB больше не отдаёт кэшбэк в этом ответе (`price.cashback = 0`), поэтому _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Данные в базе, собранные старым эндпоинтом, — популярная ротация под чужими названиями. _(from `2026-10-20-wb-catalog-api-endpoint-and-token`)_
- Скачивание при старте добавляет сетевую зависимость: без интернета старт проходит с _(from `2026-10-20-subject-dictionary-loaded-at-startup`)_

### `database`

- A job with no heartbeat and no start time falls back to its creation time — the _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- Deleting a target through the admin API deletes its scraped items and category _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- Jobs left `Running` by a crash are now reported as `Crashed` with an _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The gate compares columns — names, types, nullability. It does not compare _(from `2026-10-19-schema-drift-gate`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_
- The item path writes one statement per item, and the category path one per _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- The reconciliation runs on every boot. It touches only rows that have been _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The reconciliation stays a single statement over all stale rows, and its result _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role _(from `2026-10-19-catalog-read-api-contract`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this _(from `2026-10-19-admin-api-target-write-path-and-category-tree`)_

### `dead-code`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_

### `debugging`

- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the _(from `2026-10-19-local-debug-stack-repairs`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The item path writes one statement per item, and the category path one per _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_

### `dependencies`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_

### `deployment`

- The API answers a request that arrives right after a restart with a value computed _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- When the topology grows to more than one instance, the aggregates move to a shared _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_

### `docker`

- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the _(from `2026-10-19-local-debug-stack-repairs`)_

### `domain`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect _(from `2026-10-20-the-registry-is-total-by-construction`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop. _(from `2026-09-08-functional-pipeline-refactor`)_
- Проверка `stopAt` на границе пагинации теперь вызывается с глубиной текущей задачи. _(from `2026-10-20-pagination-does-not-increase-depth`)_
- Тест `PipelineDedupTest` на самоссылку ждёт одну страницу вместо трёх: очередь _(from `2026-10-20-pagination-does-not-increase-depth`)_

### `domain-model`

- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_
- `wb_subjects` is a reference table the crawler only reads. It is not part of _(from `2026-10-19-subject-dictionary-and-known-pages-limit`)_

### `either`

- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `flyway`

- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_

### `gate`

- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` _(from `2026-10-19-coverage-floor-gate`)_
- The nightly now costs two workflows a week instead of one: the run _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The nightly run costs a full pipeline every day. It is the price of _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `gates`

- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer. _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of _(from `2026-10-19-dependency-analysis-plugin`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_

### `infrastructure`

- A profile directory that was never materialised and a profile whose _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user _(from `2026-10-19-adspower-local-api-port-discovery`)_
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The crawler reaching Wildberries from the VPS is a separate question from _(from `2026-10-14-deploy-crawler-as-registry-image`)_
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the _(from `2026-10-19-local-debug-stack-repairs`)_
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own _(from `2026-10-19-adspower-local-api-port-discovery`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the _(from `2026-10-19-adspower-local-api-port-discovery`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_

### `lint`

- The nightly run costs a full pipeline every day. It is the price of _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_

### `migrations`

- The gate compares columns — names, types, nullability. It does not compare _(from `2026-10-19-schema-drift-gate`)_
- `latest_product_observations` отдаёт по строке на товар; историю цен по-прежнему видно _(from `2026-10-20-versioned-catalog-and-latest-observation-view`)_

### `observability`

- The nightly now costs two workflows a week instead of one: the run _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_

### `operations`

- The API answers a request that arrives right after a restart with a value computed _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- The `totalCount` of a page of `/items` is **not** cached, and the reason is _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- When the topology grows to more than one instance, the aggregates move to a shared _(from `2026-10-20-catalog-aggregate-cache-is-in-process`)_
- Ноль в `browser-idle-timeout-ms` отключает закрытие простаивающих браузеров: они живут _(from `2026-10-20-browsers-are-closed-when-they-are-not-needed`)_

### `packaging`

- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `performance`

- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels _(from `2026-10-14-antibot-session-is-site-wide`)_
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser. _(from `2026-10-14-parallel-crawl-targets`)_
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time. _(from `2026-10-14-antibot-session-is-site-wide`)_
- Конфиг `browser-jitter-min-ms`/`browser-jitter-max-ms` описывает jitter **pacer'а**, а не _(from `2026-10-20-the-request-rate-has-one-owner`)_
- Первый запрос pacer'а не ждёт: интервал отсчитывается от предыдущего разрешения, а не от _(from `2026-10-20-the-request-rate-has-one-owner`)_

### `pipeline`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one _(from `2026-10-09-wire-business-rules-into-filter`)_
- A job with no heartbeat and no start time falls back to its creation time — the _(from `2026-10-20-job-timestamps-carry-an-offset`)_
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
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks. _(from `2026-10-17-pipeline-purity-delayfn-extraction`)_
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`, _(from `2026-10-09-wire-business-rules-into-filter`)_
- The `Side` hierarchy now has three variants. A fourth one is a new effect _(from `2026-10-20-the-registry-is-total-by-construction`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_
- The reconciliation stays a single statement over all stale rows, and its result _(from `2026-10-20-job-timestamps-carry-an-offset`)_
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`) _(from `2026-10-17-stage-data-class-dsl`)_
- `Coordinator` is the sole owner of deduplication and retry attempt state _(from `2026-10-17-coordinator-extraction`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter _(from `2026-10-17-coordinator-extraction`)_
- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_
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
- Тест `PipelineDedupTest` на самоссылку ждёт одну страницу вместо трёх: очередь _(from `2026-10-20-pagination-does-not-increase-depth`)_

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
- Pagination task IDs and the start task ID are now explicitly controllable — critical for _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` is still not referentially transparent due to `delay()` in retry loops, _(from `2026-10-14-pipeline-purity-idgen`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `reliability`

- A profile directory that was never materialised and a profile whose _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- Jobs left `Running` by a crash are now reported as `Crashed` with an _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The item path writes one statement per item, and the category path one per _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- The nightly now costs two workflows a week instead of one: the run _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME` _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- The reconciliation runs on every boot. It touches only rows that have been _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `AdsPowerApi` has three methods now. A new implementation — the test fake _(from `2026-10-19-adspower-api-first-browser-resolution`)_
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a _(from `2026-10-19-exposed-single-row-writes-and-unmasked-db-errors`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_

### `resilience`

- В логах появляются «профиль отдывает» и «цепи ждёт окна» — это ожидаемое поведение, а _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Полный обход 132 каталогов по 5 страниц при темпе 3 с занимает около 35 минут. Это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_
- Цель, у которой антибот не отпустил за 5 попыток, всё равно падает — но теперь это _(from `2026-10-20-antibot-profile-rotation-and-backoff`)_

### `runtime`

- The startup gate stays: this gate cannot see a dependency that the framework _(from `2026-10-19-runtime-classpath-gate`)_

### `schedule`

- The nightly now costs two workflows a week instead of one: the run _(from `2026-10-20-a-schedule-that-does-not-run-is-a-failure`)_

### `scheduler`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_

### `scheduling`

- Jobs left `Running` by a crash are now reported as `Crashed` with an _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- The reconciliation runs on every boot. It touches only rows that have been _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was _(from `2026-10-19-crawl-job-heartbeat-and-crash-reconciliation`)_
- Окно свежести остаётся в памяти процесса, поэтому перезапуск приложения делает все цели _(from `2026-10-20-schedule-cadence-single-parameter`)_

### `schema`

- The gate compares columns — names, types, nullability. It does not compare _(from `2026-10-19-schema-drift-gate`)_

### `scorecard`

- The pin bumps arrive as pull requests from Dependabot, and each one is a _(from `2026-10-20-the-repository-measures-its-own-posture`)_

### `security`

- The nightly run costs a full pipeline every day. It is the price of _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- The pin bumps arrive as pull requests from Dependabot, and each one is a _(from `2026-10-20-the-repository-measures-its-own-posture`)_
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit _(from `2026-10-20-a-pin-is-a-commit-and-a-publication-is-checked`)_
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role _(from `2026-10-19-catalog-read-api-contract`)_

### `side-effects`

- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on _(from `2026-10-09-pipelinefailure-carries-sides`)_
- `PipelineFailure` third-Either variant remains available as a future option if more _(from `2026-10-09-pipelinefailure-carries-sides`)_

### `spring`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` was not read at the time — it has since been wired to _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_

### `supply-chain`

- The pin bumps arrive as pull requests from Dependabot, and each one is a _(from `2026-10-20-the-repository-measures-its-own-posture`)_

### `test`

- The gate compares columns — names, types, nullability. It does not compare _(from `2026-10-19-schema-drift-gate`)_

### `testing`

- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in _(from `2026-10-08-gates-that-can-fail`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is _(from `2026-10-14-extract-browser-request-logic-for-testability`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floors are raised in the same commit that adds tests, in both _(from `2026-10-08-gates-that-can-fail`)_
- The gate needs Docker, like the rest of the integration suites, and is skipped by _(from `2026-10-19-application-startup-gate`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_
- `BorrowedPage` remains unchanged — it still wraps a real `Page`. _(from `2026-10-17-browser-automation-interface`)_
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle. _(from `2026-10-17-browser-automation-interface`)_
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it. _(from `2026-10-17-browserpool-testability-adspower-api-interface`)_
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`. _(from `2026-10-17-browser-automation-interface`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_

### `tests`

- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage` _(from `2026-10-19-coverage-floor-gate`)_

### `tooling`

- A YAML written by an older selector has no `root_category_name`; run _(from `2026-10-19-idempotent-target-load-and-root-categories`)_
- The nightly run costs a full pipeline every day. It is the price of _(from `2026-10-20-ci-security-gates-and-lint-autofix`)_
- `GET /api/admin/targets` returns everything unless a `limit` is passed. _(from `2026-10-19-idempotent-target-load-and-root-categories`)_

### `types`

- The `Side` hierarchy now has three variants. A fourth one is a new effect _(from `2026-10-20-the-registry-is-total-by-construction`)_

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
- `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary` — crawler  catalog  data-quality  operations
- `2026-10-20-the-catalog-lists-products-not-observations` — catalog  database  contract
- `2026-10-20-the-coverage-floor-measures-the-code-not-the-module` — build  tests  gate  coverage
- `2026-10-20-the-crawl-reports-its-own-limits` — crawler  data-quality  catalog  operations
- `2026-10-20-the-registry-is-total-by-construction` — pipeline  architecture  domain  types
- `2026-10-20-the-repository-measures-its-own-posture` — ci  security  supply-chain  scorecard  gate
- `2026-10-20-the-request-rate-has-one-owner` — crawler  antibot  browser  performance
- `2026-10-20-versioned-catalog-and-latest-observation-view` — catalog  database  migrations
- `2026-10-20-wb-catalog-api-endpoint-and-token` — crawler  api  data-quality

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
- `2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary` — the catalog length is measured at the pagination boundary, and the limit follows it
- `2026-10-20-the-catalog-lists-products-not-observations` — Каталог показывает товары, а не наблюдения
- `2026-10-20-the-coverage-floor-measures-the-code-not-the-module` — Покрытие меряется по коду, а не по модулю, которому он принадлежит
- `2026-10-20-the-crawl-reports-its-own-limits` — Краул показывает свои границы: усечение каталога, токены, пересборка данных
- `2026-10-20-the-registry-is-total-by-construction` — The registry is total by construction, and a side nobody emits is not a side
- `2026-10-20-the-repository-measures-its-own-posture` — The repository measures its own posture, and the actions it runs are pinned
- `2026-10-20-the-request-rate-has-one-owner` — Темп запросов задаёт pacer — один, а не два
- `2026-10-20-versioned-catalog-and-latest-observation-view` — Каталог целей версионируется в репозитории, последнее наблюдение — во view
- `2026-10-20-wb-catalog-api-endpoint-and-token` — Каталог WB читается через u-search/exactmatch v18 с токеном меню
