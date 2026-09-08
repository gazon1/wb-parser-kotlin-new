---
title: "Retry signal must be Step.Retry, not Step.Fail"
date: 2026-09-08
tags: [pipeline, bug-fix]
---

## Context

The pipeline download stage (`buildParserPipeline` in `PipelineFactory.kt`) was emitting
`Step.Fail(StageFailure.Network(...))` for HTTP errors. Meanwhile, `stageWithRetry`
only retries on `Step.Retry` — `Step.Fail` always terminates the stage immediately.

This meant HTTP 500/429/connection errors were never retried by the pipeline,
even when `RetryPolicy` was configured with `maxAttempts > 1`.

Additionally, the `ScheduleRetryInterpreter` and `SaveBatchInterpreter` in
`Interpreters.kt` were dead code: the actual retry delay is handled inline by
`stageWithRetry` (via `delay()`), and the actual save is handled directly by
`CrawlRunner`. Having live implementations of these interpreters that perform
real side-effects (blocking `delay()`, database writes) while being registered
but never triggered by the pipeline was a correctness hazard.

## Idea

1. Change the download stage to emit `Step.Retry(Retry.ServerError(0))` on HTTP errors,
   letting `stageWithRetry` handle the retry loop correctly.
2. Replace the dead-but-live interpreters with `NoOp*` variants that document
   why they exist (logging only for `ScheduleRetry`, no-op for `SaveBatch`).
3. The real retry delay (`delay()`) lives **only** inside `stageWithRetry`, not in an interpreter.
4. The real save lives **only** in `CrawlRunner.runTarget`, not in an interpreter.

## Decision

- Download stage emits `Step.Retry(Retry.ServerError)` for network/HTTP errors.
- `ScheduleRetryInterpreter` → `NoOpScheduleRetryInterpreter`: emits a trace log
  for observability; actual delay is handled by `stageWithRetry`.
- `SaveBatchInterpreter` → `NoOpSaveBatchInterpreter`: no-op, since production
  save is handled directly by `CrawlRunner`.
- `NoRetryKtorDownloader` added for integration tests: disables Ktor CIO engine's
  internal retry so the pipeline-level retry mechanism is properly exercised.

## Rationale

- `stageWithRetry` is the single retry policy enforcer. Any `Step.Fail` bypasses it.
- Moving the delay into `stageWithRetry` keeps retry policy in one place.
- `SaveBatch` side-effect was never wired into the pipeline (no `saveBatch` call
  in `Pipeline.run`), making the original interpreter dead code.
- Ktor CIO engine retries connection errors internally; `NoRetryKtorDownloader`
  is needed for tests that verify pipeline-level retry.

## Consequences

- HTTP errors (500/429/connection) are now correctly retried up to `maxAttempts`.
- **Never** emit `Step.Fail` for a condition that should be retried — use
  `Step.Retry(Retry.*)`.
- **Never** register a live side-effect interpreter that the pipeline never calls.
  Use `NoOp*` variants that document why they exist.
- **Always** use `NoRetryKtorDownloader` in integration tests that verify retry.
