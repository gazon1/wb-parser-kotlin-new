---
title: "Save stage retry — G10: transient DB blip must not fail the entire crawl"
date: 2026-09-09
tags: [pipeline, bug-fix, G10]
---

## Context

The `Pipeline.run()` method uses `stageWithRetry` for the **download** and **parse**
stages — both can emit `Step.Retry` on transient failures (5xx, rate limits).
The retry loop applies exponential back-off until `maxAttempts` is exhausted.

The **save** stage was inconsistent: `saveOne` returned `0` (item dropped) on any
`Step.Retry` without retrying. A single transient DB write error (network blip,
temporary connection pool exhaustion) would fail the entire crawl — even though
download and parse stages are retried identically.

This inconsistency was introduced during the original Stage/Side/Interpreter
refactor (ADR-2026-09-08) and is classified as **G10** (runtime bug).

## Idea

The fix is a retry loop inside `saveOne` that mirrors `stageWithRetry`:

1. Add `Retry.Database` signal for transient database errors.
2. Add `StageFailure.Database(isRetryable = true)` for the same.
3. Map `StageFailure.Database` → `NetworkError(isRetryable = true)` in `toDomainError()`.
4. `saveOne` loops on `Retry` until success, exhaustion, or a non-retryable failure.
5. On exhaustion, emit `Side.Log(ERROR, "Save stage retry exhausted")`.

## Decision

### `Retry.kt` — new signal

```kotlin
data class Database(
    override val delayMs: Long? = null,
) : Retry
```

### `StageFailure.kt` — new failure type

```kotlin
data class Database(
    override val message: String,
    override val url: String? = null,
) : StageFailure {
    override val isRetryable: Boolean = true
}
```

`toDomainError()` maps `StageFailure.Database` → `NetworkError(message, null, url)` (retryable).

### `Pipeline.saveOne` — retry loop

`saveOne` now mirrors `stageWithRetry`:
- Loops while the save stage emits `Retry`
- Applies `retryDelayMs` back-off between attempts
- Returns `0` (item not saved) when retries are exhausted, after emitting `Side.Log(ERROR)`
- Returns `1` on success
- Returns `0` immediately on `Step.Fail` (non-retryable)

## Rationale

DB writes are the same class of failure as HTTP downloads — they are I/O operations
against mutable external state, subject to transient blips (connection pool exhaustion,
temporary network partition, primary-replica lag). Retrying them with back-off is the
standard pattern.

The asymmetry (download/parse retried, save not retried) was an oversight, not an
intentional design choice. The existing `stageWithRetry` pattern handles this correctly
for download and parse; `saveOne` now follows the same pattern.

## Consequences

- **Always** wrap external-state stages (DB, HTTP, file I/O) in a retry loop.
  Use `stageWithRetry(save, retryPolicy)` or a manual loop inside `saveOne`.
- **Always** use `Retry.Database` (or the appropriate `Retry.*` variant) for transient storage errors — not `Step.Fail`.
- A DB blip no longer fails the entire crawl. The save still fails the item
  (returns 0), but the crawl continues.

## Test coverage added

- `PipelineSaveOneRetryTest` — 6 tests: success, retry-succeeds, retry-exhausted,
  Retry.Database signal, Retry.ServerError signal, Step.Fail immediately.
- `RetryDatabaseTest` — 4 tests for `Retry.Database` back-off behavior.
- `StageFailureDatabaseMappingTest` — 4 tests for `StageFailure.Database`
  and its `toDomainError()` mapping.
