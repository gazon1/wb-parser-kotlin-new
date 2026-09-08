---
title: "Collapse identical Retry back-off branches"
date: 2026-09-09
tags: [cleanup, pipeline]
---

## Context

In `Retry.kt`, the `retryDelayMs()` function had a 19-line `when (signal)` with 4 branches — all byte-identical:

```kotlin
val baseDelay = when (signal) {
    is Retry.ServerError -> signal.delayMs
        ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
    is Retry.RateLimited -> signal.delayMs
        ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
    is Retry.Antibot -> signal.delayMs
        ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
    is Retry.StaleContext -> signal.delayMs
        ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
}
```

All four variants share the same `delayMs: Long?` property and the same back-off formula. The `when` added no behaviour, only duplication.

## Decision

Collapsed to direct property access on the sealed interface:

```kotlin
val baseDelay = signal.delayMs
    ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
```

`signal` is typed as `Retry` (the sealed interface), and all subtypes expose `delayMs: Long?`. Kotlin resolves this correctly without a `when`.

The four `Retry` data classes remain in place — `ServerError`, `RateLimited`, `Antibot`, `StaleContext` are still distinct signal types for future per-signal behaviour and for test clarity. Removing them was out of scope.

## Consequences

- `retryDelayMs()` reduced from 19 lines to 11 lines
- **Never** write a `when` over a sealed interface when all branches are identical — access the common property directly
- `Retry.*` data classes kept for test expressiveness and future signal-specific behaviour
