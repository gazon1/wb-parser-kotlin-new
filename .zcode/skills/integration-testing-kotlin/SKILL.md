---
name: integration-testing-kotlin
description: Kotlin integration testing patterns — fake HTTP servers, in-memory databases, Kotest extensions, and end-to-end pipeline tests. Use when the user mentions "integration test", "fake server", "in-memory DB", "Testcontainers", "SQLite for tests", or asks to write a test that spans HTTP + DB layers in a Kotlin project.
---

# Integration Testing in Kotlin — Fake HTTP + In-Memory DB

## When to use this skill

Use whenever you need to write an integration test in this project that:
- Spans an HTTP layer (fetching pages) AND a persistence layer (writing to a DB).
- Uses **Kotest** as the test framework.
- Needs a **fake HTTP server** instead of Testcontainers or real network.
- Needs an **in-memory SQLite** database instead of Testcontainers Postgres.

This skill does NOT cover: pure unit tests (see `kotlin-test-boundary`), or MockK usage (the project uses Fake-over-Mock convention per AGENTS.md).

## Core Patterns

### 1. Fake HTTP server — WbFixtureServer (com.sun.net.httpserver)

The project provides `ru.wbparser.testing.WbFixtureServer` — a `com.sun.net.httpserver.HttpServer`-based fixture server. Use this, not Ktor embedded, not MockWebServer.

```kotlin
import ru.wbparser.testing.WbFixtureServer

class WbParserPaginationTest : FunSpec({

    val server = WbFixtureServer()
    beforeSpec { server.start() }
    afterSpec { server.stop() }

    // Fixed route: same response every time
    server.route("/catalog", 200, """{"data":{"products":[]}}""")

    // Sequence route: consume one response per request (for retry tests)
    server.routeSequence("/catalog", listOf(
        500 to "",                              // first call fails
        200 to """{"data":{"products":[...]}}"""  // second call succeeds
    ))

    val baseUrl = server.baseUrl()  // "http://localhost:12345"

    // Assert request counts
    server.requestCount("/catalog") shouldBe 2
})
```

**WbFixtureServer** lives at `tests/src/test/kotlin/ru/wbparser/testing/WbFixtureServer.kt`. It supports:
- `route(path, status, body)` — fixed response per path
- `routeSequence(path, responses)` — consume one response per request (for retry/pagination tests)
- `requestCount(path)` — number of requests received
- `baseUrl()` — base URL with assigned port

### 2. Route sequence for retry tests

```kotlin
// Simulate: first request returns 429 (rate limited), second succeeds
server.routeSequence("/catalog", listOf(
    429 to """{"error":"Rate limit"}""",
    200 to """{"data":{"products":[{"id":1,"name":"Item","price":"49900"}]}}""",
))
```

### 3. Per-path request counter

```kotlin
server.route("/catalog", 200, fixtureBody)

// Later in assertions:
server.requestCount("/catalog") shouldBe 3
```

### 4. Fixture JSON as resource files

Store fixture JSON in `src/test/resources/wb-fixtures/` and load at test time:

```kotlin
// file: src/test/resources/wb-fixtures/page-5-products.json
// {"data":{"products":[{"id":1,"name":"Item 1",...}]}}

val fixtureBody = ::class.java.classLoader
    .getResource("wb-fixtures/page-5-products.json")
    ?.readText()
    ?: throw IllegalStateException("Fixture not found")
```

### 5. In-memory SQLite — connection gotcha

**Critical:** `jdbc:sqlite::memory:` creates a **new in-memory database per connection**. Two connections see two different databases. Fix: use `cache=shared`:

```
jdbc:sqlite:file::memory:?cache=shared
```

Or use a named in-memory file: `jdbc:sqlite:/tmp/test.db` (cleanup required).

```kotlin
import org.sqlite.SQLiteDataSource

class SqliteTestHandle(url: String = "jdbc:sqlite:file::memory:?cache=shared") : AutoCloseable {
    private val ds = SQLiteDataSource().apply { this.url = url }

    fun execute(sql: String) {
        ds.connection.use { it.createStatement().use { it.execute(sql) } }
    }

    fun <T> query(sql: String, mapper: (ResultSet) -> T): List<T> {
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) mapper(rs) else null }.toList()
                }
            }
        }
    }

    fun update(sql: String, vararg params: Any?): Int {
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, v -> ps.setObject(idx + 1, v) }
                ps.executeUpdate()
            }
        }
    }

    override fun close() { ds.close() }
}
```

### 6. Kotest assertions for DB rows

```kotlin
fun SqliteTestHandle.shouldHaveItem(productId: Long, assertions: SavedItemRow.() -> Unit) {
    val items = query("SELECT * FROM scraped_items WHERE product_id = ?", productId) {
        SavedItemRow(
            productId = it.getLong("product_id"),
            title = it.getString("title"),
            priceKopecks = it.getLong("price_kopecks"),
            brand = it.getString("brand"),
        )
    }
    val item = items.firstOrNull() ?: throw AssertionError("No item with product_id=$productId")
    item.assertions()
}

// Usage:
db.shouldHaveItem(123L) {
    priceKopecks shouldBe 49900L
    title shouldBe "Item 1"
}
```

### 7. End-to-end pipeline test structure

```kotlin
class WbParserHappyPathTest : FunSpec({

    val server = WbFixtureServer()
    beforeSpec { server.start() }
    afterSpec { server.stop() }

    val db = SqliteTestHandle()

    beforeTest {
        db.execute("CREATE TABLE IF NOT EXISTS scraped_items (...)")
    }
    afterTest {
        db.execute("DELETE FROM scraped_items")
    }

    test("parser pipeline writes items to sqlite") {
        val fixtureBody = ::class.java.classLoader
            .getResource("wb-fixtures/page-5-products.json")!!.readText()
        server.route("/catalog", 200, fixtureBody)

        val clock = fixedClockOf(2026, 9, 8)
        val idGen = { "task-id" }
        val policy = RetryPolicy()

        val pipeline = Pipeline(
            download = WbDownloader("$baseUrl/catalog"),
            parse = WbCatalogParser(),
            filter = BusinessRules()::dropIfInvalid,
            enrich = { Step.Done(it.toSavedItem()) },
            save = FakeSaveBatch(db),
            retryPolicy = policy,
            stopAt = stopAfterPages(1),
            clock = clock,
            idGen = idGen,
        )

        val runner = PipelineRunner(pipeline, SideInterpreterRegistry(
            log = LogTestInterpreter(logs),
            saveBatch = SaveBatchTestInterpreter(savedItems),
        ))

        val startTask = Crawling(id = "start", url = WbUrl.of("$baseUrl/catalog"), depth = 0, targetId = 1L)
        val result = runner.run(listOf(startTask))

        result.isRight() shouldBe true
        db.query("SELECT COUNT(*) FROM scraped_items") { it.getInt(1) }.first() shouldBe 5
    }
})
```

### 8. When NOT to use this skill

- **Pure unit tests** — use `FunSpec` in `domain/src/test/` with hand-crafted `Step` objects directly. No server, no DB needed. See `kotlin-test-boundary`.
- **Testcontainers Postgres** — use when production schema fidelity is required. The project's `tests/build.gradle.kts:39` declares `testcontainers:postgresql:1.20.4` but no tests currently use it.

## Anti-patterns

### BAD: hardcoded port

```kotlin
HttpServer.create(InetSocketAddress(8080), 0)  // BAD — port may be taken
```
**GOOD:** `HttpServer.create(InetSocketAddress(0), 0)` then read `.address.port` after `start()`.

### BAD: `jdbc:sqlite::memory:` without `cache=shared`

```kotlin
val ds = SQLiteDataSource(url = "jdbc:sqlite::memory:")  // Each connection = new DB!
```
**GOOD:** `jdbc:sqlite:file::memory:?cache=shared`.

### BAD: MockK for database

```kotlin
every { repo.save(any()) } returns item  // BAD — mocks leak into prod
```
**GOOD:** Use a real in-memory DB (`SqliteTestHandle`) or a `FakeSavedItemRepository` with mutable list.

### BAD: `delay()` inside a pipeline stage

```kotlin
val download = { task: Crawling ->
    delay(100)  // BAD — side effect inside pure stage
    Step.Done(downloader.download(task))
}
```
**GOOD:** `delay()` lives only in the runner's retry loop (`stageWithRetry`), not in stage definitions.

## Related Skills

- `pipelines` — pipeline composition, Step/Side/Interpreter patterns
- `kotlin-test-boundary` — where to place tests (domain vs. infrastructure vs. tests module)
- `sqlite-from-postgres` — Postgres-to-SQLite dialect conversion

## Sources

- [Kotest documentation](https://kotest.io/)
- [com.sun.net.httpserver Javadoc](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)
- [SQLite JDBC wiki](https://github.com/xerial/sqlite-jdbc)
- [Arrow Either / Raise](https://arrow-kt.io/docs/)
