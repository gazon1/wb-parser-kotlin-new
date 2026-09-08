---
title: "Functional pipeline refactor — Stage/Side/Interpreter registry"
date: 2026-09-08
tags: [pipeline, architecture, domain]
---

## Context

The original `crawl()` function was imperative: it directly invoked HTTP calls, DB writes, and retry logic in a single tangled suspend function. Any testing required a real HTTP server and database. Business rules (when to retry, how to extract items) were mixed with side effects (logging, sleeping, writing to DB).

The two commits (`e1259e8`, `21a511e`) replaced this with a declarative `Pipeline` model.

## Idea

Split the pipeline into three orthogonal layers:

- **`Stage`** — pure functions `(PipelineContext) -> StageResult`. Each stage declares what side effects it needs via `emits(Side)`. No network, no DB, no `delay`.
- **`Side`** — sealed interface enumerating all side-effect types: `HttpRequest`, `SaveBatch`, `ScheduleRetry`, `Log`.
- **`Interpreter`** — executes a `Side` against the real environment (HTTP client, DB, scheduler, logger). A `SideInterpreterRegistry` holds all registered interpreters.

`Pipeline.run(target, ctx)` traverses stages, emits side-effect requests, resolves them through the registry, and feeds results back into the next stage. The pipeline itself is testable in pure Kotlin with a fake registry.

## Decision

Adopted the three-layer model. Key files:

```
domain/pipeline/
  Stage.kt           — Stage, StageResult (Done / Fail / Retry / Emit)
  Side.kt            — Side sealed interface + subclasses
  Pipeline.kt        — Pipeline.run() declarative engine
  Interpreter.kt     — SideInterpreterRegistry, Interpreter

domain/pipeline/stages/
  HttpFetchStage.kt
  ParseCatalogStage.kt
  SaveBatchStage.kt

domain/pipeline/interpreters/
  HttpInterpreter.kt
  SaveBatchInterpreter.kt
  ScheduleRetryInterpreter.kt
  LogInterpreter.kt
```

## Rationale

- **Testability**: `Pipeline.run()` with a `FakeSideInterpreterRegistry` tests all business rules without mocks or testcontainers.
- **Parallelism**: stages declare their side-effect needs, so the engine can theoretically batch independent I/O.
- **Extensibility**: new `Side` variants and their interpreters are added without touching existing stages.
- **Retry clarity**: `ScheduleRetry` is a first-class `Side`, not scattered `runCatching` blocks.

## Consequences

- **Always** use `SideInterpreterRegistry` to register all side-effect handlers before calling `Pipeline.run()`.
- **Never** emit a `Side` from a stage without a corresponding interpreter registered — the pipeline will throw at runtime.
- **Never** call `delay()` or `UUID.randomUUID()` inside a `Stage` — inject timing/id generation through `PipelineContext` if needed.
- New code in `domain/pipeline/**` **MUST** be accompanied by a pure-Kotlin test in `domain/src/test/kotlin/` using a fake registry.
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` are not optional — they must be registered in every `runTarget`.

## Links

- Commit `e1259e8` — initial functional refactor
- Commit `21a511e` — complete declarative pipeline
- `crawl.kt` — the old imperative entry point (still present as reference, to be removed)
