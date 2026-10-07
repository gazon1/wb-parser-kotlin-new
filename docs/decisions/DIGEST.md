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
- **Never** call `delay()` or `UUID.randomUUID()` inside a `Stage` — inject timing/id generation through `PipelineContext` if needed. _(from `2026-09-08-functional-pipeline-refactor`)
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

## Per-tag

### `architecture`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred _(from `2026-09-09-dead-code-purge-pr9`)_
- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment) _(from `2026-09-09-dead-code-purge-pr9`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` are not optional — they must be registered in every `runTarget`. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_
- `StageFailure` sealed interface now has 6 variants (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- `toDomainError()` has 6 branches (was 7) _(from `2026-09-09-dead-code-purge-pr9`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function) _(from `2026-09-09-dead-code-purge-pr9`)_

### `arrow`

- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_

### `bug-fix`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_

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

### `configuration`

- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_

### `conventions`

- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_
- `git log --oneline` is the project changelog — keep subjects informative. _(from `2026-09-08-conventional-commits-and-cleanup-prs`)_

### `database`

- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path _(from `2026-10-08-discover-test-migrations`)_

### `dead-code`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_

### `domain`

- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist) _(from `2026-09-09-dead-code-purge-pr19`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- **Positive**: Cleaner domain model, no dead code _(from `2026-09-09-dead-code-purge-pr19`)_
- **Positive**: Honest documentation (effect-recording vs pure) _(from `2026-09-09-dead-code-purge-pr19`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` are not optional — they must be registered in every `runTarget`. _(from `2026-09-08-functional-pipeline-refactor`)_

### `G10`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_

### `pipeline`

- A DB blip no longer fails the entire crawl. The save still fails the item _(from `2026-09-09-save-stage-retry-pr11`)_
- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction _(from `2026-09-09-arrow-either-pr20`)_
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism _(from `2026-09-09-concurrency-wiring-pr17`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`) _(from `2026-09-09-arrow-either-pr20`)_
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18) _(from `2026-09-09-concurrency-wiring-pr17`)_
- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`. _(from `2026-09-08-retry-semantics-pr8`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `PipelineRunner` code is unchanged (already correct) _(from `2026-09-09-arrow-either-pr20`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `retryDelayMs()` reduced from 19 lines to 11 lines _(from `2026-09-09-retry-backoff-collapse-pr9-5`)_
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` are not optional — they must be registered in every `runTarget`. _(from `2026-09-08-functional-pipeline-refactor`)_
- `Spider.concurrency` from `application.yml` now actually controls download concurrency _(from `2026-09-09-concurrency-wiring-pr17`)_

### `PR-11-5`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `refactoring`

- Adding a new stage (e.g. `dedup`) is now a mechanical extract step _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Each helper is independently testable _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- Pagination remains inline — defer extraction until a concrete need arises _(from `2026-09-09-pipeline-extraction-pr11-5`)_
- `Pipeline.run()` reduced from 113 lines to ~55 lines _(from `2026-09-09-pipeline-extraction-pr11-5`)_

### `scheduler`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_

### `spring`

- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock) _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true` _(from `2026-09-09-scheduler-wireup-pr18`)_
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig` _(from `2026-09-09-scheduler-wireup-pr18`)_

### `testing`

- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE) _(from `2026-09-09-test-module-boundary-pr10`)_
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests. _(from `2026-09-08-domain-coverage-pr4`)_
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred _(from `2026-09-09-test-module-boundary-pr10`)_
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share _(from `2026-10-08-discover-test-migrations`)_
- `tests:test` still runs integration tests (fake HTTP + SQLite) _(from `2026-09-09-test-module-boundary-pr10`)_
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same _(from `2026-10-08-discover-test-migrations`)_
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
