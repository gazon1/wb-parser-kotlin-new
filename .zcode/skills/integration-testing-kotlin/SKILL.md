---
name: integration-testing-kotlin
description: Kotlin integration testing patterns — fake HTTP servers, in-memory databases, Kotest extensions, and end-to-end pipeline tests. Use when the user mentions "integration test", "fake server", "Ktor test", "in-memory DB", "Testcontainers", "SQLite for tests", or asks to write a test that spans HTTP + DB layers in a Kotlin project.
---

# Integration Testing in Kotlin — Fake HTTP + In-Memory DB

## When to use this skill

Use whenever you need to write an integration test in a Kotlin project that:
- Spans an HTTP layer (fetching pages) AND a persistence layer (writing to a DB).
- Uses **Kotest** as the test framework (the project's standard, confirmed by `kotest-runner-junit5:5.8.1` in all `build.gradle.kts` test deps).
- Needs a **fake HTTP server** instead of Testcontainers or real network.
- Needs an **in-memory SQLite** database instead of Testcontainers Postgres.

This skill does NOT cover: pure unit tests, Spring Boot slices (`@SpringBootTest`), or MockK usage (the project uses Fake-over-Mock convention per AGENTS.md rule #6).

## Core Patterns

### 1. Fake HTTP server — Ktor embedded (CIO engine)

**Why Ktor instead of MockWebServer?** The production stack already uses Ktor (`ktor-client-cio` in `infrastructure/build.gradle.kts:31`). Using `embeddedServer(CIO)` for tests keeps the engine consistent and requires no new dependency family.

```kotlin
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking

// Start on a free port (port = 0 tells the OS to pick one)
val server = embeddedServer(CIO, port = 0) {
    routing {
        get("/catalog") {
            call.respondText(
                """{"data":{"products":[{"id":1,"name":"Item 1","price":"49900"}]}}""",
                contentType = ContentType.Application.Json,
            )
        }
    }
}.start(wait = false)

// Discover the assigned port
val port = server.engine.resolvedConnectors().first().port
println("Server running on port $port")

// Stop in afterSpec / finally
server.stop(100, 500)
```

**Port discovery gotcha:** `embeddedServer(..., port = 0)` assigns a random free port. Always read it back from `resolvedConnectors()` after `start()`, never hardcode a port like `8080`.

### 2. Kotest extension for server lifecycle

Use a **Kotest `extension`** to manage server start/stop automatically:

```kotlin
import io.kotest.core.extensions.MountedExtension
import io.kotest.core.spec.Spec
import io.kotest.core.engine.ExperimentalKotestEngineApi
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO

@OptIn(ExperimentalKotestEngineApi::class)
class WbFixtureServerExtension(
    private val configure: CIOApplicationEngine.() -> Unit = {},
) : MountedExtension<Spec, CIOApplicationEngine>({ spec, _ ->
    val server = embeddedServer(CIO, port = 0, appConfig = {}, module = configure)
    server.start()
    spec.registerShutdownHook { server.stop(100, 500) }
    server
}) {
    val port: Int get() = instance.engine.resolvedConnectors().first().port
    fun baseUrl(): String = "http://localhost:$port"
}
```

Simpler alternative: use `beforeSpec` / `afterSpec` callbacks in a `FunSpec`:

```kotlin
class WbParserHappyPathTest : FunSpec({
    val server = embeddedServer(CIO, port = 0) { routing { get("/catalog") { ... } } }
    beforeSpec { server.start() }
    afterSpec { server.stop(100, 500) }

    val port = server.engine.resolvedConnectors().first().port
})
```

### 3. Per-path request counter

```kotlin
val requestCounts = mutableMapOf<String, Int>()

val server = embeddedServer(CIO, port = 0) {
    routing {
        get("/catalog") {
            requestCounts["/catalog"] = (requestCounts["/catalog"] ?: 0) + 1
            call.respondText(fixtureBody, contentType = ContentType.Application.Json)
        }
    }
}

// Later in assertions:
requestCounts["/catalog"] shouldBe 2
```

### 4. Route sequence for retry tests

```kotlin
val responses = mutableListOf(
    HttpStatusCode.InternalServerError to "",
    HttpStatusCode.OK to """{"data":{"products":[{"id":1,"name":"Item 1","price":"49900"}]}}""",
)
var callIndex = 0

val server = embeddedServer(CIO, port = 0) {
    routing {
        get("/catalog") {
            val (status, body) = responses[callIndex++]
            call.respondText(body, status)
        }
    }
}
```

### 5. Fixture JSON as resource files

Store fixture JSON in `src/test/resources/wb-fixtures/` and load at test time:

```kotlin
// file: src/test/resources/wb-fixtures/page-5-products.json
// {"data":{"products":[{"id":1,"name":"Item 1",...}]}}

val fixtureBody = ::class.java.classLoader
    .getResource("wb-fixtures/page-5-products.json")
    ?.readText()
    ?: throw IllegalStateException("Fixture not found")
```

This keeps test code clean and makes fixtures reusable across test files.

### 6. In-memory SQLite — connection gotcha

**Critical:** `jdbc:sqlite::memory:` creates a **new in-memory database per connection**. Two connections see two different databases. Fix: use `cache=shared`:

```
jdbc:sqlite:file::memory:?cache=shared
```

Or use a named in-memory file: `jdbc:sqlite:/tmp/test.db` (cleanup required).

**HikariCP note:** HikariCP is overkill for in-memory SQLite tests. For a single-connection test DB, use `org.sqlite.SQLiteDataSource` directly with `maximumPoolSize = 1` (or just a single raw `DriverManager.getConnection`). The project's `DatabaseHandle` wraps HikariCP + Exposed — for test DB just use raw JDBC.

```kotlin
import org.sqlite.SQLiteDataSource

class SqliteTestHandle(url: String = "jdbc:sqlite:file::memory:?cache=shared") : AutoCloseable {
    private val ds = SQLiteDataSource().apply {
        this.url = url
    }

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

### 7. Kotest assertions for DB rows

```kotlin
// Helper extension on SqliteTestHandle
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

// Usage in test:
db.shouldHaveItem(123L) {
    priceKopecks shouldBe 49900L
    title shouldBe "Item 1"
}
```

### 8. End-to-end test structure (per Kotest FunSpec)

```kotlin
class WbParserHappyPathTest : FunSpec({

    val server = embeddedServer(CIO, port = 0) {
        routing {
            get("/catalog") {
                val body = ::class.java.classLoader
                    .getResource("wb-fixtures/page-5-products.json")!!.readText()
                call.respondText(body, contentType = ContentType.Application.Json)
            }
        }
    }

    beforeSpec { server.start() }
    afterSpec { server.stop(100, 500) }

    val port = server.engine.resolvedConnectors().first().port
    val db = SqliteTestHandle()

    beforeTest {
        db.execute("CREATE TABLE scraped_items (...)")
    }
    afterTest { db.close() }

    test("parser pipeline writes scraped_items to sqlite") {
        // Arrange
        val downloader = KtorDownloader()
        val save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { items ->
            for (item in items) {
                db.update(
                    "INSERT OR REPLACE INTO scraped_items (id, product_id, title, price_kopecks, ...) VALUES (?, ?, ?, ?, ...)",
                    UUID.randomUUID().toString(), item.productId, item.name, item.priceKopecks,
                )
            }
            Step.Done(Unit)
        }

        // Act
        val pipeline = buildParserPipeline(
            downloader = downloader,
            parser = ::parseWbCatalog,
            save = save,
            targetId = 1L,
            stopAt = { pages, _ -> if (pages >= 1) Stop.MaxPagesReached else null },
            clock = fixedClockOf(2026, 9, 8),
            idGen = { "task-id" },
        )
        val runner = PipelineRunner(pipeline, SideInterpreterRegistry(
            log = LogTestInterpreter(collector),
            saveBatch = SaveBatchTestInterpreter(collector),
            scheduleRetry = ScheduleRetryTestInterpreter(collector),
        ))
        val result = runner.run(listOf(startTask))

        // Assert
        result.isRight() shouldBe true
        db.query("SELECT COUNT(*) FROM scraped_items") { it.getInt(1) }.first() shouldBe 5
    }
})
```

### 9. When NOT to use this skill

- **Pure unit tests** — use `FunSpec` + `runTest` with hand-crafted `Step` objects directly. No server, no DB needed.
- **Spring Boot context tests** — use `@SpringBootTest` with `@TestConfiguration` overrides. The project has no existing Spring test infrastructure so this pattern doesn't apply here.
- **Testcontainers Postgres** — use when the production schema must be exercised verbatim. The project's `tests/build.gradle.kts:39` declares `testcontainers:postgresql:1.20.4` but no tests currently use it. If Postgres fidelity is required, use it instead of SQLite.

## Anti-patterns

### BAD: hardcoded port
```kotlin
embeddedServer(CIO, port = 8080)  // BAD — port may be taken
```
**GOOD:** `embeddedServer(CIO, port = 0)` then read `resolvedConnectors().first().port`.

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
- `sqlite-from-postgres` — Postgres-to-SQLite dialect conversion

## Sources

- [Kotest documentation](https://kotest.io/)
- [Ktor docs — embedded server](https://ktor.io/docs/server-embedded.html)
- [SQLite JDBC wiki](https://github.com/xerial/sqlite-jdbc)
- [Arrow Either / Raise](https://arrow-kt.io/docs/)
