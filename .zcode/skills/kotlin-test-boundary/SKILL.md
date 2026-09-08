---
name: kotlin-test-boundary
description: Kotlin test placement decision tree. Use when the user asks "where should this test live", "which module for unit tests", "domain vs infrastructure vs tests module", "pure Kotlin tests without Spring", or any question about which gradle module a test should belong to.
---

# Kotlin Test Boundary — Where Does a Test Live?

## When to use this skill

Use whenever you need to decide which module a test belongs in:
- Writing a new test and unsure where to place it
- Reviewing a PR that adds tests to the wrong module
- Moving existing tests between modules
- Adding a new dependency to a test file

---

## The Decision Tree

```
Is the code under test in `domain/`?
│
├── YES → Does the test compile WITHOUT adding new dependencies?
│         (only `domain` + `kotlin-stdlib` + `kotlinx-coroutines-core` + `arrow-core` on classpath)
│         ├── YES → `domain/src/test/kotlin/ru/wbparser/domain/`
│         │         Example: value class tests, sealed interface variants, parsers,
│         │                   retry policy, pipeline composition
│         └── NO  → `infrastructure/src/test/kotlin/ru/wbparser/infra/`
│                   Example: test that uses SQLiteDataSource, Ktor types
│
└── NO (code under test is in `infrastructure/` or `app/`)
    │
    ├── Does it need real Spring Boot context?
    │   ├── YES → `tests/src/test/kotlin/ru/wbparser/infra/integration/`
    │   │         Example: full pipeline with Spring beans, REST routes
    │   └── NO
    │       ├── Does it use Ktor client, SQLite, or Caffeine?
    │       │   YES → `infrastructure/src/test/kotlin/ru/wbparser/infra/`
    │       │         Example: KtorDownloader unit test, CatalogQueries with SQLite,
    │       │                   SideInterpreterRegistry test, PostgresAdvisoryLock test
    │       └── NO
    │           └── Any other case → `tests/src/test/kotlin/ru/wbparser/infra/integration/`
```

---

## The Razor

> **If a test compiles in `domain/src/test/` without adding new dependencies, it belongs there.**

Test it: try to move the test file to `domain/src/test/` and run `./gradlew :domain:test`. If it compiles and passes → done. If it fails due to missing imports → it needs a different module.

---

## Per-module Classpath

| Module | Classpath for tests | Common test deps |
|---|---|---|
| `domain/` | domain src + kotlin-stdlib + arrow-core + kotlinx-coroutines-core | kotest-runner-junit5, kotest-assertions-core |
| `infrastructure/` | infra src + domain classes + Ktor + SQLite + Caffeine + Exposed + HikariCP | kotest + raw JDBC (no Spring) |
| `tests/` | all of above + Spring Boot + app beans + Testcontainers | `@SpringBootTest`, `testcontainers:postgresql`, MockK |

---

## Project-specific Patterns

### Clock injection — `fixedClockOf`

Domain code uses `Clock` for time. Tests use `fixedClockOf()`:

```kotlin
// Domain test — in domain/src/test/kotlin/ru/wbparser/domain/
import ru.wbparser.testing.fixedClockOf

class CrawlUrlTest : FunSpec({
    test("stopAt uses wall clock") {
        val clock = fixedClockOf(2026, 9, 8, 12, 0, 0)
        // use clock in test...
    }
})
```

`fixedClockOf` lives in `domain/src/test/kotlin/ru/wbparser/testing/TestClock.kt`. Always import from `ru.wbparser.testing`.

### Fake-over-Mock — test interpreters

When testing pipeline integration, use `Fake*` test interpreters, not mocks:

```kotlin
// In tests/src/test/kotlin/ru/wbparser/testing/InMemoryAdapters.kt
class FakeSideInterpreterRegistry : SideInterpreterRegistry(
    log = LogTestInterpreter(collector),
    saveBatch = SaveBatchTestInterpreter(collector),
    scheduleRetry = ScheduleRetryTestInterpreter(collector),
    // NOT MockK — use real implementations or fakes
)
```

Never use `MockK` (`io.mockk:mockk`) in this project per AGENTS.md rule.

### SQLite for repository tests

When testing DB queries, use `SqliteTestHandle` (in-memory):

```kotlin
// infrastructure/src/test/kotlin/.../RepositoryTest.kt
class SavedItemQueriesTest : FunSpec({
    val db = SqliteTestHandle()
    afterSpec { db.close() }
    beforeTest { db.clear() }

    test("upsertSavedItems round-trips") {
        db.query("SELECT COUNT(*) FROM scraped_items") { it.getInt(1) }.first() shouldBe 0
    }
})
```

### Fake HTTP server — Java `com.sun.net.httpserver`

For integration tests that need a fake HTTP server, use `WbFixtureServer`:

```kotlin
// tests/src/test/kotlin/ru/wbparser/infra/integration/WbParserRetryTest.kt
val server = WbFixtureServer()
beforeSpec { server.start() }
afterSpec { server.stop() }

server.routeSequence("/catalog", listOf(
    500 to "",
    200 to fixtureBody,
))
```

**Not** Ktor `embeddedServer` — `WbFixtureServer` uses `com.sun.net.httpserver.HttpServer`.

---

## Characterisation Tests for Behaviour-Preserving Refactors

When refactoring a large function (≥50 lines), write characterisation tests **first** to
establish a safety net. A characterisation test captures the **current behaviour** of the
function — even when that behaviour seems wrong — so you can refactor with confidence.

### Workflow

1. **Write characterisation tests** in `domain/src/test/kotlin/ru/wbparser/domain/` using the
   real function. These tests document what the function does today.
2. **Run tests** — they should pass against the current code.
3. **Extract helper functions** one at a time. Each extract → run tests → commit.
4. If a test breaks after extraction: you introduced a behaviour change. Fix the refactor, not the test.

### Example: `Pipeline.run()` refactor

Before refactoring `Pipeline.run()` (113 lines), characterisation tests covered:
- Stop conditions (MaxPages, MaxDepth, EmptyPage, ManualStop)
- Pagination (self-links, nextPageUrl, stopAt interaction)
- Item processing (filter → enrich → save flow)
- Retry semantics (exhaustion, back-off, retry-exhausted mapping)

After extraction into `runDownloadStage` + `runParseStage` + `processItems` helpers,
all 194 characterisation tests passed without modification.

### Rules

- **Never refactor without characterisation tests** for functions ≥ 30 lines
- **One extract per commit** — build → test → commit after each helper
- **Keep helper signatures narrow** — pass only what each helper needs
- **Keep helpers ≤ 20 lines** — if a helper grows beyond 20 lines, it likely needs its own extraction

## Common Mistakes

### BAD: Adding Spring dep to domain test
```kotlin
// domain/src/test/kotlin/.../MyTest.kt ❌
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
// This forces the entire Spring context to load — defeats the purpose of domain tests
```
**GOOD**: Move to `tests/` module, or refactor the code so it doesn't need Spring.

### BAD: Using MockK instead of Fake
```kotlin
// infrastructure/src/test/kotlin/.../MyTest.kt ❌
import io.mockk.every
// AGENTS.md forbids MockK — use FakeSavedItemRepository instead
```
**GOOD**: Use a mutable-list fake or in-memory SQLite.

### BAD: Putting pure domain test in `tests/`
```kotlin
// tests/src/test/kotlin/ru/wbparser/domain/RetryPolicyTest.kt ❌
// Should be: domain/src/test/kotlin/ru/wbparser/domain/RetryPolicyTest.kt
```
A test that only uses domain classes and compiles without new deps belongs in `domain/`.

### BAD: Importing `ru.wbparser.testing.*` from domain test
```kotlin
// domain/src/test/kotlin/.../MyTest.kt ❌
// ru.wbparser.testing lives in the `tests/` module — domain cannot see it
import ru.wbparser.testing.SqliteTestHandle  // NO!
```
**GOOD**: Extract domain utilities (like `fixedClockOf`) into `domain/src/test/kotlin/ru/wbparser/testing/`.

---

## wb-parser-kotlin Test Inventory (after PR 11)

### `domain/src/test/` — pure domain unit tests (194 tests after PR 11)
- `BusinessRulesTest`, `CashbackTest`, `ClockDomainTest`, `CrawlHttpStatusCodeTest`
- `CrawlUrlDomainTest`, `DomainErrorClassifyTest`, `ItemDtoMappingTest`
- `JobStatusTest`, `PagedResultTest`, `PipelineTest`
- `PipelineSaveOneRetryTest` (PR 11 — G10 retry coverage)
- `PresetTest`, `PriceTest`, `RetryDatabaseTest` (PR 11)
- `RetryPolicyTest`, `SavedItemFactoryTest` (PR 11)
- `StageFailureDatabaseMappingTest` (PR 11)
- `StageFailureTest`, `StageTest`, `StopExhaustiveTest` (PR 11)
- `WbUrlTest`
- Utility: `TestClock.kt` (`fixedClockOf`)

### `infrastructure/src/test/` — infra adapters (empty — Kotlin version conflict)
The `infrastructure/` module has a pre-existing Kotlin stdlib 2.2.21 vs compiler 1.9.24
conflict. Infra adapter tests live in `tests/` module instead:
- `tests/src/test/kotlin/ru/wbparser/infra/SideInterpreterRegistryTest.kt`
- `tests/src/test/kotlin/ru/wbparser/infra/LogInterpreterTest.kt`

### `tests/src/test/` — full integration tests (26 tests)
- `WbParserHappyPathTest`, `WbParserEmptyPageTest`, `WbParserRetryTest`
- `PipelineRunnerTest`, `WbParserPaginationTest`, `WbParserRetryExhaustionTest`
- `InMemoryAdapters.kt` (TestSideCollector, Fake*Interpreter implementations)
- `NoRetryKtorDownloader.kt`, `WbFixtureServer.kt`, `SqliteTestHandle.kt`

---

## Related Skills

- `integration-testing-kotlin` — fake HTTP servers, in-memory DB patterns
- `dead-code-purge` — safe removal of code that no test covers
- `pipelines` — Stage/Side/Interpreter patterns and anti-patterns

## Sources

- J.B. Rainsberger — "Integration Tests Are a Scam" (https://www.jbrains.ca/permanent/integration-tests-are-a-scam)
- Gary Bernhardt — "Boundaries" (pure core / imperative shell)
- Kotlin/kotlin.test documentation
