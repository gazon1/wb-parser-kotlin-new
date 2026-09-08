# Pipelines — Railway-Oriented Kotlin DSL

## When to use this skill

Use when:
- Building a crawl/pipeline system in Kotlin (WebParser, scraper, ETL)
- Composing stages: download → parse → validate → enrich → save
- Needing retry, backoff, stop-conditions, rate-limiting inside a pipeline
- Writing pure-functional pipeline logic that must be testable without mocks
- Designing a DSL for a pipeline runner

## Core Patterns

### 1. Railway-Oriented Programming with Arrow Either

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

// Good: explicit errors as Left, never exceptions for expected failures
fun parse(body: String): Either<ParseError, ParsedPage> = either {
    val json = JSON.parseToJsonElement(body).unsafeCast<JsonObject>()
    val items = json["products"]?.jsonArray
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { dto ->
            ensureNotNull(dto["id"]?.jsonPrimitive?.content) {
                ParseError("missing id", body)
            }
            dto.toItem()
        } ?: emptyList()
    ParsedPage(items)
}

// Good: bind() propagates errors automatically
fun downloadAndParse(url: String): Either<DomainError, ParsedItem> = either {
    val fetched = download(url).bind()        // Left propagates up
    val page    = parse(fetched.body).bind()  // Left propagates up
    page.items.first()
}
```

### 2. Pure Core / Imperative Shell

```
┌──────────────────────────────────────────────┐
│  DOMAIN  (pure — no side effects)            │
│  Pipeline, Stage<I,O>, Step<I,O>, RetryPolicy │
│  BusinessRules, Stop, Side effects as DATA   │
└──────────────────────────────────────────────┘
                    │
                    │ interpreted by
                    ▼
┌──────────────────────────────────────────────┐
│  INFRA   (impure — interprets Side effects)   │
│  KtorDownloader, PostgresRepo, Clock,        │
│  AdvisoryLock, RateLimiter                   │
└──────────────────────────────────────────────┘
```

**Rule**: Domain has ZERO imports from `kotlinx.coroutines`, `io.ktor`, `java.sql`.

### 3. Step<I, O> — three outcomes of a stage

```kotlin
// Step = what a stage returns: continue, done, or retry
sealed interface Step<I, out O> {
    // Continue crawling with the same input (e.g. redirect)
    data class Cont<I, O>(val input: I) : Step<I, O>
    // Stage produced output
    data class Done<I, O>(val output: O, val sides: List<Side> = emptyList()) : Step<I, O>
    // Retry with a signal (backoff, rate-limit, etc.)
    data class Retry<I, O>(val signal: RetrySignal) : Step<I, O>
}

// Stage = single transformation
typealias Stage<I, O> = (I) -> Step<I, O>

// Pipeline = composition of stages
data class Pipeline<I, O>(
    val stages: List<Stage<I, *>>,
    val terminal: Stage<I, O>,
)
```

### 4. Side effects as data (not as language feature)

```kotlin
// Side = a data class representing an effect to be interpreted later
sealed interface Side {
    data class Fetch(val url: String, val deadline: Instant) : Side
    data class SaveBatch(val items: List<SavedItem>) : Side
    data class Log(val level: LogLevel, val msg: String) : Side
    data class Metric(val name: String, val value: Long) : Side
    data class JobEvent(val op: JobOp) : Side
    data class ScheduleRetry(val url: String, val afterMs: Long) : Side
}

// Interpreter runs Side effects
interface Interpreter<S : Side> {
    suspend fun handle(side: S): Unit
}
```

### 5. Time abstraction — never use Instant.now() directly

```kotlin
interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

data class FixedClock(private val fixed: Instant) : Clock {
    override fun now(): Instant = fixed
}

// In domain: Clock is a constructor parameter, never accessed directly
fun parseWithTime(body: String, clock: Clock): Either<ParseError, ParsedPage> = either {
    ParsedPage(items, parsedAt = clock.now())
}
```

### 6. Retry with backoff as pure function

```kotlin
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 120_000L,
)

sealed interface RetrySignal {
    data class ServerError(val attempt: Int, val code: Int?) : RetrySignal
    data class RateLimited(val retryAfterMs: Long) : RetrySignal
    data class Antibot(val waitMs: Long = 30_000L) : RetrySignal
}

// Pure: no suspend, no delay
fun retryDelayMs(
    attempt: Int,
    signal: RetrySignal,
    policy: RetryPolicy,
    random: Random = Random.Default,
): Long {
    val base = when (signal) {
        is RetrySignal.ServerError -> policy.baseDelayMs * (1 shl attempt)
        is RetrySignal.RateLimited -> signal.retryAfterMs
        is RetrySignal.Antibot -> signal.waitMs
    }
    val capped = base.coerceAtMost(policy.maxDelayMs)
    val jitter = random.nextLong(capped / 10)
    return (capped + jitter).coerceAtMost(policy.maxDelayMs)
}

fun shouldRetry(attempt: Int, policy: RetryPolicy): Boolean =
    attempt < policy.maxAttempts
```

### 7. Stop conditions as data

```kotlin
sealed interface Stop {
    data object MaxPagesReached : Stop
    data class MaxDepthReached(val limit: Int) : Stop
    data object EmptyPage : Stop
    data object ManualStop : Stop
    data class StoppedBecause(val reason: String) : Stop
}

// Compose stops with AND logic
fun stopsAll(vararg conditions: (Int, Int) -> Stop?): (Int, Int) -> Stop? =
    { page, depth -> conditions.mapNotNull { it(page, depth) }.firstOrNull() }

fun stopAfterMaxPages(limit: Int): (Int, Int) -> Stop? =
    { page, _ -> if (page >= limit) Stop.MaxPagesReached else null }
```

### 8. DSL with @DslMarker — single marker, single block

```kotlin
@DslMarker
annotation class CrawlDsl

@CrawlDsl
class CrawlBuilder {
    private val _stages = mutableListOf<StageConfig<*, *>>()
    private var _retry: RetryPolicy = RetryPolicy()
    private var _stop: (Int, Int) -> Stop? = { _, _ -> null }

    fun download(block: DownloadBuilder.() -> Unit) {
        _stages += DownloadBuilder().apply(block).build()
    }
    fun retry(policy: RetryPolicy) { _retry = policy }
    fun stopAfter(fn: (Int, Int) -> Stop?) { _stop = fn }

    fun build(): CrawlSpec = CrawlSpec(_stages.toList(), _retry, _stop)
}

// Usage — single block, no nesting
val crawler = crawl {
    download { withKtor(timeout = 30.seconds) }
    parse    { parseWbCatalog() }
    filter   { dropIfInvalid(BusinessRules()) }
    enrich   { it.toSavedItem() }
    save     { batch(100) }
    retry    { maxAttempts = 5; baseDelay = 1.seconds }
    stopAfter { page, depth -> stopAfterMaxPages(100)(page, depth) }
}
```

### 9. Testing pure pipeline logic — no mocks needed

```kotlin
class PipelineTest : FunSpec({
    test("pipeline stops after max pages") {
        val clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
        val policy = RetryPolicy(maxAttempts = 3)

        // Synthetic stages — no mocks, no network
        val download: Stage<String, String> = { input ->
            Step.Done("page content for $input")
        }
        val parse: Stage<String, List<String>> = { content ->
            Step.Done(content.split(",").filter { it.isNotBlank() })
        }

        val pipeline = Pipeline(listOf(download), parse)
        val result = pipeline.run(clock, listOf("url1", "url2"), policy)

        result.shouldBeRight()
    }
})
```

### 10. Anti-patterns to avoid

#### BAD: exceptions for expected failures
```kotlin
// BAD — exception for network error
fun download(url: String) = try {
    HttpClient.get(url)
} catch (e: Exception) {
    throw NetworkException(url)  // exceptions leak
}

// GOOD — Either for expected failures
fun download(url: String): Either<NetworkError, Fetched> = either {
    val response = HttpClient.get(url).getOrElse {
        raise(NetworkError("Connection failed: ${it.message}", url))
    }
    Fetched(response.body, response.status)
}
```

#### BAD: Instant.now() in domain model
```kotlin
// BAD — side effect hidden in data class
data class ParsedPage(
    val items: List<ParsedItem>,
    val parsedAt: Instant = Instant.now(),  // hidden clock dependency!
)

// GOOD — clock is explicit
data class ParsedPage(
    val items: List<ParsedItem>,
    val parsedAt: Instant,  // passed from outside
)
fun parse(body: String, clock: Clock): Either<ParseError, ParsedPage> = either {
    ParsedPage(items, clock.now())
}
```

#### BAD: mutable state in pipeline stage
```kotlin
// BAD — mutable counter inside stage
class CountingStage : Stage<String, String> {
    var count = 0
    override fun invoke(input: String): Step<String, String> {
        count++  // mutation — not thread-safe, not testable
        return Step.Done(input.uppercase())
    }
}

// GOOD — state passed through pipeline environment
data class PipelineEnv(val clock: Clock, val stats: MutableStats)
typealias StatefulStage<I, O> = (I, PipelineEnv) -> Step<I, O>
```

#### BAD: flatMapMerge over stages with async inside
```kotlin
// BAD — complex async inside flatMapMerge
taskFlow.flatMapMerge(3) { task ->
    flow {
        val result = retryDownload(task)  // suspend inside, complex to test
        emit(result)
    }
}

// GOOD — pure stage, async in runner only
val stage: Stage<Crawling, Fetched> = { task ->
    download(task).fold(
        ifLeft = { Step.Retry(RetrySignal.ServerError(0, null)) },
        ifRight = { Step.Done(it) }
    )
}
// Runner handles the suspend
```

## Related Skills

- `kotlin-stdlib` — Kotlin standard library conventions
- `arrow-core` — Arrow Either, Raise, computations

## Sources

- Scott Wlaschin — "Domain Modeling Made Functional" (https://fsharpforfunandprofit.com/)
- John Carmack — "Inherit the Earth" (code readability over cleverness)
- Kotlin coding conventions — https://kotlinlang.org/docs/coding-conventions.html
- Arrow 1.2.x docs — https://arrow-kt.io/docs/
- ZIO pattern — `ZIO[R, E, A]` environment / error / success
