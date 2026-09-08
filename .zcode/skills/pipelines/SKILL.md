# Pipelines — Stage/Side/Interpreter Pattern

## When to use this skill

Use when:
- Building a crawl/pipeline system in Kotlin (WebParser, scraper, ETL)
- Composing stages: download → parse → filter → enrich → save
- Needing retry, backoff, stop-conditions inside a pipeline
- Writing pure-functional pipeline logic that must be testable without mocks
- Deciding where a side effect should live (data vs. language feature)

## Core Patterns

### 1. Pure Core / Imperative Shell

```
┌──────────────────────────────────────────────┐
│  DOMAIN  (pure — no side effects)             │
│  Pipeline, Stage<I,O>, Step<I,O>, RetryPolicy │
│  BusinessRules, Stop, Side effects as DATA    │
└──────────────────────────────────────────────┘
                    │
                    │ interpreted by
                    ▼
┌──────────────────────────────────────────────┐
│  INFRA   (impure — interprets Side effects)   │
│  KtorDownloader, PostgresRepo, Clock,         │
│  AdvisoryLock, RateLimiter                   │
└──────────────────────────────────────────────┘
```

**Rule**: Domain has ZERO imports from `kotlinx.coroutines`, `io.ktor`, `java.sql`.

### 2. Step<I, O> — four outcomes of a stage

```kotlin
sealed interface Step<in I, out O> {
    // Stage produced output; sides are collected for later interpretation
    data class Done<I, O>(
        val output: O,
        val sides: List<Side> = emptyList(),
    ) : Step<I, O>

    // Continue with the same input (e.g. redirect follows)
    data class Cont<I, O>(val input: I) : Step<I, O>

    // Retryable condition detected; retry via ScheduleRetry side effect
    data class Retry<I, O>(val signal: Retry) : Step<I, O>

    // Unrecoverable failure; propagates as DomainError
    data class Fail<I, O>(val failure: StageFailure) : Step<I, O>
}
```

### 3. Stage = suspend function typealias

```kotlin
typealias Stage<I, O> = suspend (I) -> Step<I, O>
```

**Why `suspend`?** Parsers may need to perform async work (e.g. look up cached data). Keeping Stage suspend avoids API breakage when async is needed.

### 4. Pipeline = five stages + orchestration

```kotlin
data class Pipeline(
    val download:  Stage<Crawling, Fetched>,
    val parse:     Stage<Fetched, ParsedPage>,
    val filter:    Stage<ParsedItem, ParsedItem?>,
    val enrich:    Stage<ParsedItem, SavedItem>,
    val save:      Stage<List<SavedItem>, Unit>,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val stopAt:    (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    val clock: Clock,
    val idGen: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    suspend fun run(tasks: List<Crawling>): Either<DomainError, Pair<Crawled, List<Side>>>
}
```

Each stage is a pure function — given the same input it returns the same output. All IO (HTTP, DB, clock) is expressed as `Side` values and returned alongside the result.

### 5. Side effects as data (not as language feature)

```kotlin
sealed interface Side {
    data class Fetch(...)        // HTTP GET
    data class SaveBatch(...)    // DB write
    data class Log(...)          // logging
    data class Metric(...)       // metrics
    data class JobEvent(...)     // job state transitions
    // Note: ScheduleRetry was removed in PR 8 (NoOp interpreter always returned)
    // Retry is now handled by re-adding the task to the pending queue directly.
}
```

Every `Side` variant must have an interpreter registered in `SideInterpreterRegistry` before use. Unregistered sides are silent no-ops — **Always register all Side variants**.

### 6. NoOp* convention for optional side effects

If a side effect is optional (e.g., metrics may be disabled), provide a `NoOp*` stub that is registered by default:

```kotlin
class NoOpMetricInterpreter : MetricInterpreter {
    override suspend fun handle(side: Side.Metric) { /* no-op */ }
}
```

**Convention**: `NoOp*` classes are registered in the default registry so the pipeline is always runnable without wiring up every optional component.

### 7. Time abstraction — never use Instant.now() directly

```kotlin
interface Clock {
    fun now(): Instant
}

data class FixedClock(private val fixed: Instant) : Clock {
    override fun now(): Instant = fixed
}

// In domain: Clock is a constructor parameter
fun parseWithTime(body: String, clock: Clock): Either<ParseError, ParsedPage> = either {
    ParsedPage(items, parsedAt = clock.now())
}
```

### 8. Retry with backoff as pure function

```kotlin
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 120_000L,
)

// Pure: no suspend, no delay
fun retryDelayMs(attempt: Int, signal: Retry, policy: RetryPolicy): Long {
    val baseDelay = signal.delayMs  // nullable; falls back to exponential backoff
        ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
    return baseDelay.coerceAtMost(policy.maxDelayMs)
}

fun shouldRetry(attempt: Int, policy: RetryPolicy): Boolean =
    attempt < policy.maxAttempts
```

### 9. Stop conditions as data

```kotlin
sealed interface Stop {
    data object ManualStop : Stop
    data object EmptyPage : Stop
    data object NoNextPage : Stop
    data object MaxPagesReached : Stop
    data class MaxDepthReached(val limit: Int, val current: Int) : Stop
    data class StoppedBecause(val reason: String) : Stop
}

fun stopAfterPages(limit: Int): (pages: Int, depth: Int) -> Stop? =
    { page, _ -> if (page >= limit) Stop.MaxPagesReached else null }

fun stopAfterDepth(limit: Int): (pages: Int, depth: Int) -> Stop? =
    { _, depth -> if (depth >= limit) Stop.MaxDepthReached(limit, depth) else null }

fun stopsAll(vararg predicates: (pages: Int, depth: Int) -> Stop?): (pages: Int, depth: Int) -> Stop? =
    { page, depth -> predicates.mapNotNull { it(page, depth) }.firstOrNull() }
```

### 10. Testing pure pipeline logic — no mocks needed

```kotlin
class PipelineTest : FunSpec({
    test("pipeline stops after max pages") {
        val clock = fixedClockOf(2026, 1, 1)
        val policy = RetryPolicy(maxAttempts = 3)

        val download: Stage<Crawling, Fetched> = { task ->
            Step.Done(Fetched("page content for ${task.url}", 200))
        }
        val parse: Stage<Fetched, ParsedPage> = { fetched ->
            Step.Done(ParsedPage(items = emptyList(), nextPageUrl = null))
        }

        val pipeline = Pipeline(
            download = download,
            parse = parse,
            filter = { Step.Done(it) },
            enrich = { Step.Done(it.toSavedItem()) },
            save = { Step.Done(Unit) },
            retryPolicy = policy,
            stopAt = stopAfterPages(1),
            clock = clock,
        )

        val result = pipeline.run(listOf(Crawling(id = "1", url = url, depth = 0, targetId = 1L)))
        result.shouldBeRight()
    }
})
```

## Anti-patterns

### BAD: Use Fail for retryable errors

```kotlin
// BAD — Fail is for unrecoverable errors only
override suspend fun invoke(input: Fetched): Step<Fetched, ParsedPage> {
    return if (networkError) {
        Step.Fail(StageFailure.NetworkError(...))  // WRONG: network errors are retryable!
    } else {
        Step.Done(parsedPage)
    }
}

// GOOD — Retry for retryable, Fail for unrecoverable
override suspend fun invoke(input: Fetched): Step<Fetched, ParsedPage> {
    return when {
        networkError -> Step.Retry(Retry.NetworkError(...))
        parseError is FatalParseError -> Step.Fail(StageFailure.ParseError(...))
        else -> Step.Done(parsedPage)
    }
}
```

**Rule**: Never emit `Step.Fail` for conditions that `RetryPolicy` can recover from. Use `Retry` and let `stageWithRetry` apply backoff.

### BAD: ScheduleRetry side effect in new code

`Side.ScheduleRetry` is interpreted as a no-op by `NoOpScheduleRetryInterpreter`. New code should **never emit `Side.ScheduleRetry`** — retry is handled by re-adding the task to the pending queue directly in `Pipeline.run()`.

### BAD: Instant.now() in domain model

```kotlin
// BAD — hidden clock dependency
data class ParsedPage(
    val items: List<ParsedItem>,
    val parsedAt: Instant = Instant.now(),  // side effect in data class
)

// GOOD — clock is explicit
data class ParsedPage(
    val items: List<ParsedItem>,
    val parsedAt: Instant,  // passed from outside
)
```

### BAD: Mutable state in stage

```kotlin
// BAD — mutable counter inside stage
override suspend fun invoke(input: String): Step<String, String> {
    count++  // mutation — not thread-safe, not testable
    return Step.Done(input.uppercase())
}

// GOOD — state via Side effects interpreted externally
override suspend fun invoke(input: String): Step<String, String> =
    Step.Done(input.uppercase(), sides = listOf(Side.Metric("processed", 1)))
```

## Related Skills

- `kotlin-test-boundary` — where to place tests (domain vs. infrastructure vs. tests module)
- `dead-code-purge` — how to safely remove unused code
- `integration-testing-kotlin` — fake HTTP server setup for integration tests

## Sources

- Scott Wlaschin — "Domain Modeling Made Functional" (https://fsharpforfunandprofit.com/)
- Gary Bernhardt — "Boundaries" (https://www.destroyallsoftware.com/talks/boundaries)
- Mark Seemann — "Dependency Injection in Scala" (constructor injection patterns)
- Arrow 1.2.x docs — https://arrow-kt.io/docs/
