---
title: "Integration testing — SQLite + Java HttpServer"
date: 2026-09-08
tags: [testing, pipeline, architecture]
---

## Context

The parser pipeline (`DomainPipeline` + `PipelineRunner`) had no end-to-end integration test coverage. Existing tests are either pure domain unit tests or manual. A proper integration test requires:

- A fake HTTP server serving prepared WB catalog JSON fixtures
- A real pipeline parsing the response
- Persistence to a test database

Three test cases: happy path (5 products → 5 DB rows), empty page (`Stop.EmptyPage`), retry on HTTP 500.

## Idea

- **SQLite in-memory** for the test DB (avoids Postgres advisory locks and `crawl_jobs` rows that `CrawlRunner` depends on)
- **Java `com.sun.net.httpserver.HttpServer`** for the fake HTTP server (zero deps, avoids Ktor routing DSL complexity)
- **Bypass `CrawlRunner`**: build `DomainPipeline` directly via `buildParserPipeline()` factory with custom `save` lambda targeting SQLite

## Decision

Built three integration tests in `tests/src/test/kotlin/ru/wbparser/infra/integration/`:

| File | Scenario |
|---|---|
| `WbParserHappyPathTest.kt` | 5 products → `itemsSaved=5`, DB assertions |
| `WbParserEmptyPageTest.kt` | Empty page → `Stop.EmptyPage`, no rows |
| `WbParserRetryTest.kt` | HTTP 500 → retry → success, `server.requestCount("/catalog") == 2` |

Supporting infrastructure in `tests/src/test/kotlin/ru/wbparser/testing/`:
- `WbFixtureServer` — `com.sun.net.httpserver.HttpServer` with `route(path, status, body)` and `routeSequence(path, responses)`
- `SqliteTestHandle` — wraps `SQLiteDataSource(url = "jdbc:sqlite:file::memory:?cache=shared")`, applies `V1__sqlite_init.sql` migration

## Rationale

SQLite `cache=shared` is critical — without it, each JDBC connection sees a different in-memory database. The `cache=shared` URI parameter makes all connections share the same backing store.

Java `HttpServer` was chosen over Ktor `embeddedServer(CIO)` because the Ktor routing DSL in Kotlin 1.9.24 with Ktor 2.3.13 caused multiple type-resolution issues (`PipelineContext` vs `ApplicationCall`, `resolvedConnectors()` being `suspend`, import path mismatches).

## Bugs Found and Fixed

1. **`Pipeline.kt` — stop condition**: `pagesCrawled++` happened BEFORE `stopAt` was consulted, so `stopAt(pages=1)` fired immediately without processing page 1. Fixed: moved increment after the stop check. Also fixed pagination stop check to set `stopped = paginationStop` instead of letting the loop exit naturally.

2. **`WbItemMapping.kt` — parsePrice**: Prices from WB API are in kopeks (integer strings like `"49900"`), not rubles. Was unconditionally multiplying by 100. Fixed: if normalized string has no decimal point, treat as kopeks directly.

3. **`WbItemDto.kt` — FlexibleBoolSerializer**: `deserialize()` called `decodeString()` which throws on actual JSON booleans (`true`/`false`). Fixed: check if `JsonDecoder` is available, extract `JsonPrimitive`, handle both boolean and string forms.

4. **`PipelineFactory.kt` — download stage**: Was emitting `Step.Fail` for all network errors, bypassing retry. Fixed: emit `Step.Retry(Retry.ServerError(0))` so `stageWithRetry` handles back-off.

5. **`SqliteTestHandle` — query params**: `query(sql, mapper)` ignored bind parameters — the `?` placeholders were never filled. Fixed: `query(sql, vararg params, mapper)`.

## Consequences

- **Always** use `jdbc:sqlite:file::memory:?cache=shared` for in-memory SQLite in tests
- **Always** call `server.baseUrl()` inside the test lambda (not at class instantiation), because the port is assigned on `start()`
- **Always** emit `Step.Retry` from download stage for retryable errors — `Step.Fail` bypasses `stageWithRetry`
- **Never** use `Random.Default` in retry tests — use seeded `Random` for deterministic back-off
