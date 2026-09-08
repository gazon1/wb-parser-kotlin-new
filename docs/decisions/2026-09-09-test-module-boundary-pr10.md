---
title: "Test module boundary — where tests live"
date: 2026-09-09
tags: [testing, architecture]
---

## Context

After PR 9 dead-code purge, 7 pure-Kotlin test classes lived in `tests/src/test/kotlin/ru/wbparser/domain/`. The ADR `2026-09-08-domain-coverage-pr4` already mandated that "New code in `domain/pipeline/**` **MUST** include a unit test in `domain/src/test/kotlin/`" — but this rule was never actioned: all domain tests were in the `tests/` module, which has Spring Boot + Ktor transitive dependencies.

Running domain tests through the `tests/` module means:
- Tests that only test pure domain logic require a full Spring context or Ktor stack to compile
- Domain module is tested only through integration tests (expensive, slow)
- The ADR PR 4 rule is violated in practice

## Decision

**Moved 7 pure-Kotlin tests** from `tests/src/test/kotlin/ru/wbparser/domain/` to `domain/src/test/kotlin/ru/wbparser/domain/`:

| Test class | Why in domain |
|---|---|
| `BusinessRulesTest` | Pure domain logic, no infra deps |
| `ItemDtoMappingTest` | Pure parsing, no infra deps |
| `PipelineTest` | Pure pipeline composition, no infra deps |
| `RetryPolicyTest` | Pure retry formula, no infra deps |
| `StageFailureTest` | Pure domain errors, no infra deps |
| `StageTest` | Pure stage combinators, no infra deps |
| `WbUrlTest` | Pure URL utilities, no infra deps |

**Extracted `fixedClockOf`** from `tests/src/test/kotlin/ru/wbparser/testing/InMemoryAdapters.kt` to `domain/src/test/kotlin/ru/wbparser/testing/TestClock.kt` — domain tests need this utility but cannot import from `tests/` module.

**Added dedup changes** (PR 10 scope):
- `ResultSet.toSavedItem` — removed private duplicate in `SavedItemQueries.kt:81-117` (now imports `ru.wbparser.infra.catalog.toSavedItem`)
- `RetryPolicy` defaults — removed from `crawl.kt:61-65` and `PipelineFactory.kt:45-49`, uses `RetryPolicy()` companion defaults

## Test placement rule

```
tests/              — Spring Boot, Ktor, real HTTP, real DB (integration)
infrastructure/test — Ktor client, SQLite in-memory, Caffeine cache (unit+integration)
domain/test        — pure Kotlin: value classes, sealed interfaces, parsers, retry, pipeline
```

**The razor**: if a test compiles in `domain/src/test/` without adding a new dependency → it belongs there.

## Consequences

- `domain:test` now runs ~50 pure domain unit tests (previously NO-SOURCE)
- `tests:test` still runs integration tests (fake HTTP + SQLite)
- `isStopped()` / `isDrop()` defaults in `DomainError` still present — zero callers confirmed, removal deferred
