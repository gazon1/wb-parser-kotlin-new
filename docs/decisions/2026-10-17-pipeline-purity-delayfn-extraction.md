---
title: "Extract delayFn from async dispatcher into coordinator-level retry loop"
date: 2026-10-17
tags: [pipeline, architecture, concurrency]
---

## Context

`stageWithRetry()` currently calls `delayFn()` **inside** an `async {}` block (via `runDownloadStage` / `runParseStage`). This means the dispatcher thread blocks during the back-off delay — wasting a thread pool slot for the entire back-off duration.

Additionally, `stageWithRetry` is not referentially transparent because of the inline `delayFn` call, making it impossible to unit-test retry/back-off behaviour without a real async clock.

## Idea

Move the retry loop entirely out of `stageWithRetry` and into the coordinator level (`Pipeline.run()`). The `stageWithRetry` becomes a pure one-shot function that returns `null` on success/failure or a `Wait` signal on retryable condition. The coordinator handles all back-off delays sequentially, outside any `async {}` block.

Alternatives considered:
- **Pass `delayFn` as parameter to `stageWithRetry`**: still calls suspending `delayFn` inside `async {}`, problem unchanged.
- **`Step.Wait` at stage level**: adds `Wait` to the public `Step` sealed interface, which is meant to describe stage output, not coordinator decisions.

## Decision

1. Add `StageDecision.Wait(attempt: Int, signal: Retry, backoffMs: Long)` — a new private variant inside `Pipeline.StageDecision` that carries the retry signal and pre-computed back-off.

2. Replace `stageWithRetry` with `attemptStageOnce(input, stage): Step<I,O>?` — pure, non-suspend, returns `null` when the stage returned `Retry` (meaning "retryable, please retry"). The null serves as the "retry needed" signal.

3. Rewrite `runDownloadStage` and `runParseStage` to use a **coordinator-level retry loop**:
   ```
   var attempt = 0
   while (true) {
     val result = attemptStageOnce(task, download)
     when (result) {
       is Done -> return StageDecision.Done(result.output)
       is Fail -> return StageDecision.Fail(result.failure)
       null -> {  // Retry
         val backoffMs = retryDelayMs(attempt, signal, retryPolicy)
         if (!shouldRetry(attempt + 1, retryPolicy)) return Fail(RetryExhausted)
         attempt++
         delayFn(backoffMs.milliseconds)  // ← now in coordinator, not in async
         // loop continues
       }
       is Cont -> return StageDecision.Cont
     }
   }
   ```

4. Remove `stageWithRetry` entirely. Remove `delayFn` from `Pipeline` constructor — the coordinator calls `delay` (or a test fake) directly.

## Consequences

- **Always** handle back-off delays at the coordinator level (inside `Pipeline.run()` or `processItems`) — never inside `async {}`.
- `Pipeline` no longer has `delayFn` as constructor parameter — `run()` uses `kotlinx.coroutines.delay` directly.
- Dispatcher threads are never blocked by retry back-off.
- Retry logic in `runDownloadStage` / `runParseStage` / `saveBatch` is fully deterministic and testable with fake clocks.
- `stageWithRetry` (deleted) is replaced by an inline retry loop in each coordinator — slightly more code but explicit and correct.
