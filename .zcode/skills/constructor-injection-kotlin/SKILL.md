# Constructor Injection in Kotlin — Why Field-Init Is an Anti-Pattern

## When to use this skill

Use when:
- A class creates a database connection, HTTP client, or other resource in a field initializer
- The user mentions "field-init", "singleton in class", "hard-coded connection", "global state"
- Refactoring to make a class testable
- Setting up Spring `@Bean` definitions

## The Problem: Field-Init Hides Dependencies

```kotlin
// BAD — connection created at field-init, hidden from caller
class CrawlRunner(
    private val downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    private val parser: (Fetched) -> ParsedPage,
) {
    private val db = connect()  // hidden dependency — caller can't inject test DB
    private val advisoryLock = PostgresAdvisoryLock(db.ds)
}
```

**Problems:**
1. **Untestable in isolation** — you can't pass a test database without modifying global state
2. **Eager resource allocation** — `connect()` runs even if the class is never used
3. **No control over credentials** — password defaults like `"postgres"` are embedded in the function
4. **Implicit contract** — callers don't know `CrawlRunner` requires a DB until runtime

## The Solution: Constructor Injection

```kotlin
// GOOD — dependency explicit in constructor
class CrawlRunner(
    private val db: DatabaseHandle,  // injected, not created internally
    private val downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    private val parser: (Fetched) -> ParsedPage,
) {
    private val advisoryLock = PostgresAdvisoryLock(db.ds)
}
```

**Benefits:**
1. **Testable** — pass a test `DatabaseHandle` in tests
2. **Explicit contract** — all dependencies visible at construction site
3. **Deferred creation** — caller controls when and how resources are created
4. **Configuration-friendly** — credentials come from config, not hardcoded defaults

## Spring Integration

When using Spring, provide the dependency as a `@Bean`:

```kotlin
@Configuration
class DatabaseConfig {

    @Bean
    fun databaseHandle(properties: CrawlProperties): DatabaseHandle {
        return connect(
            url = properties.database.url,
            user = properties.database.username,
            password = properties.database.password,
            poolSize = properties.database.poolSize,
        )
    }
}

@Configuration
class CrawlRunnerConfig(
    private val db: DatabaseHandle,  // injected by Spring
) {
    @Bean
    fun crawlRunner(downloader: ..., parser: ...): CrawlRunner {
        return CrawlRunner(
            db = db,
            downloader = downloader,
            parser = parser,
        )
    }
}
```

## Anti-Pattern: Default Parameter Values for Credentials

```kotlin
// BAD — defaults for credentials are a security risk
fun connect(
    url: String = "jdbc:postgresql://localhost:5432/wbparser",
    user: String = "postgres",
    password: String = "postgres",  // default password in source!
): DatabaseHandle

// GOOD — no defaults for credentials; require explicit configuration
fun connect(
    url: String,
    user: String,
    password: String,
    poolSize: Int = 10,  // pool size is safe to default
): DatabaseHandle
```

**Rule**: Credentials (url, user, password) should **never** have default values in `connect()`. Rely on `application.yml` + `@ConfigurationProperties` to provide production-safe defaults.

## When to Use Field-Init (OK)

Field-init is fine when:
- The dependency is a **pure value object** with no external resource
- The class **always** needs this value and it's **never** mocked in tests
- The dependency is a **singleton** that is truly global (e.g., `LogManager`)

```kotlin
// OK — immutable value object, no external resource
class ItemParser(private val json: Json = Json { ignoreUnknownKeys = true })

// OK — logger is truly global and not worth injecting
class MyService {
    private val log = LoggerFactory.getLogger(MyService::class.java)
}
```

## Kotlin-Specific Notes

### Use `by lazy` for Expensive Non-Critical Dependencies

```kotlin
// If you must defer creation and injection is not an option:
class CrawlRunner(...) {
    private val lock: PostgresAdvisoryLock by lazy { PostgresAdvisoryLock(db.ds) }
}
```

But prefer constructor injection — `by lazy` still hides the dependency from the caller.

### Primary Constructor Properties

```kotlin
// All properties in primary constructor = proper DI
class CrawlRunner(
    private val db: DatabaseHandle,
    private val downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    private val parser: (Fetched) -> ParsedPage,
    private val maxPagesPerCatalog: Int = 10,
    private val maxDepth: Int = 2,
    private val clock: Clock = SystemClock,
)
```

Avoid secondary constructors or `init` blocks that create dependencies.

## Related Skills

- `pipelines` — how `CrawlRunner` coordinates `DomainPipeline` + `SideInterpreterRegistry`
- `integration-testing-kotlin` — how to set up a test `DatabaseHandle` (use `SqliteTestHandle`)

## Sources

- Mark Seemann — "Dependency Injection in .NET" (constructor injection patterns)
- Mark Seemann — "Code That Fits in Your Head" (explicit dependencies, global state)
- Spring Framework documentation — @Bean and constructor injection
