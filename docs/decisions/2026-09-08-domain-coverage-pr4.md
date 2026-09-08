---
title: "Domain coverage — Retry/StageFailure/Pipeline EmptyPage tests"
date: 2026-09-08
tags: [testing, domain, pipeline]
---

## Context

PR 4 (`32cb634`) added domain-level unit tests for the pipeline layer, specifically covering:

- `Retry` signal generation and backoff delay
- `StageFailure` sealed class variants (`HttpError`, `Antibot`, `StaleContext`, `RateLimited`)
- `Pipeline` behavior on empty page (no items extracted)

The existing tests in `tests/` are integration tests (Testcontainers + real DB). The domain module had no unit tests at all.

## Decision

Added `domain/src/test/kotlin/` test sources covering the three pipeline failure modes:

| File | What it tests |
|---|---|
| `RetryTest.kt` | Backoff delay calculation per `StageFailure` type |
| `StageFailureTest.kt` | `StageFailure` sealed class decomposition |
| `PipelineEmptyPageTest.kt` | `Pipeline` completes without emitting items when page is empty |

## Rationale

- **Domain rules belong in the domain module** — no framework, no DB, no testcontainers.
- Pure Kotlin tests run in <1 s, no Docker needed, no flakiness.
- `FakeSideInterpreterRegistry` is the only test double needed — consistent with the `fake over mock` convention.
- Pipeline failure modes (HTTP errors, bot detection, stale context) are the most business-critical paths — they should never regress silently.

## Consequences

- New code in `domain/pipeline/**` **MUST** include a unit test in `domain/src/test/kotlin/` that uses `FakeSideInterpreterRegistry`.
- **Never** skip domain unit tests for "quick fixes" — the <1 s execution time makes it free to run.
- Integration tests in `tests/` still cover the full stack; they complement, not replace, domain tests.

## Links

- Commit `32cb634` — `test: PR 4 domain coverage — Retry Antibot/StaleContext, StageFailure, Pipeline EmptyPage`
