---
title: "Extract Coordinator class from Pipeline.run"
date: 2026-10-17
tags: [pipeline, architecture, pr-13]
---

## Context

`Pipeline.run` (~250 lines) mixed three unrelated responsibilities:

1. **Deduplication** — `seenFingerprints: MutableSet<CrawlingFingerprint>` held the URL+depth set across the whole run
2. **Coordinator-level retry tracking** — `MutableMap<String, Int> taskAttempts` tracked per-task attempt counts
3. **Batch rotation** — pulling tasks from `pending` into `batch` for concurrent execution

The `run` method was hard to unit-test in isolation for deduplication edge cases and retry semantics.

## Idea

Extract a `Coordinator` class that owns the deduplication fingerprint set and the task-attempt map. `Pipeline.run` becomes a thin orchestrator that delegates to `Coordinator.tryClaim`, `Coordinator.scheduleRetry`, and `Coordinator.attemptCount`.

Alternatives considered:
- Keep everything in `Pipeline` — the mix of concerns was the original problem
- Pass the set/map as parameters to `run` — callers shouldn't need to know these internals

## Decision

Create `Coordinator` in `domain/pipeline/Coordinator.kt`:

- `seenFingerprints: MutableSet<CrawlingFingerprint>` — private, mutated only by `Coordinator`
- `taskAttempts: MutableMap<String, Int>` — private, mutated only by `Coordinator`
- `tryClaim(Crawling): TryClaimResult` — returns `Claimed(task)` or `Skipped(task, log)`
- `attemptCount(taskId): Int` — read-only access
- `resetForRetry(task)` — removes fingerprint + resets attempt count
- `scheduleRetry(TaskOutcome.Retryable): Boolean` — performs back-off wait and returns false when exhausted
- `isClean()` — for test assertions

`TaskOutcome` sealed class moved from inner-private in `Pipeline` to top-level in `Coordinator.kt` so it can be referenced by both.

`CrawlingFingerprint` data class also moved to `Coordinator.kt`.

`Pipeline.run` now accepts `(tasks, concurrency)` only — `seenFingerprints` parameter removed from the public API.

## Rationale

- `Pipeline.run` is now purely about stage orchestration — the loop structure is clean
- `Coordinator` has a single, focused responsibility — easy to verify independently
- No API changes for callers — the `seenFingerprints` parameter was never part of any public contract

## Consequences

- `Pipeline.run` signature simplified: removed `seenFingerprints` parameter
- `Coordinator` is the sole owner of deduplication and retry attempt state
- `TaskOutcome` and `CrawlingFingerprint` are now in `Coordinator.kt` instead of `Pipeline.kt`
