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

- **`Stage`** — a `data class` holding a `run: suspend (I) -> Step<I, O>` function. Stages are composable; they receive input and return `Step`. No network, no DB, no `delay` inside the `run` lambda.
- **`Side`** — sealed interface enumerating all side-effect types: `HttpRequest`, `SaveBatch`, `ScheduleRetry`, `Log`.
- **`Interpreter`** — executes a `Side` against the real environment (HTTP client, DB, scheduler, logger). A `SideInterpreterRegistry` holds all registered interpreters.

`Pipeline.run(target, ctx)` traverses stages, emits side-effect requests, resolves them through the registry, and feeds results back into the next stage. The pipeline itself is testable in pure Kotlin with a fake registry.

## Decision

Adopted the three-layer model. Key files:

```
domain/pipeline/
  Stage.kt           — Stage data class, StageInfo, StageBuilder DSL
  Step.kt            — Step sealed interface (Done / Fail / Retry / Cont)
  Side.kt            — Side sealed interface + subclasses
  Pipeline.kt        — Pipeline.run() declarative engine + Coordinator
  Interpreter.kt     — SideInterpreterRegistry, Interpreter

infra/pipeline/
  Interpreters.kt    — NoOp* production interpreters, LogInterpreter, MicrometerMetricInterpreter
  PipelineRunner.kt  — Bridge: runs Pipeline and interprets accumulated Sides
  PipelineFactory.kt — Builds a concrete Pipeline from downloader/parser/saver functions
```

## Rationale

- **Testability**: `Pipeline.run()` with a `FakeSideInterpreterRegistry` tests all business rules without mocks or testcontainers.
- **Parallelism**: stages declare their side-effect needs, so the engine can theoretically batch independent I/O.
- **Extensibility**: new `Side` variants and their interpreters are added without touching existing stages.
- **Retry clarity**: `ScheduleRetry` is a first-class `Side`, not scattered `runCatching` blocks.

## Consequences

- **Always** use `SideInterpreterRegistry` to register all side-effect handlers before calling `Pipeline.run()`.
- **Never** emit a `Side` from a stage without a corresponding interpreter registered — the pipeline will throw at runtime.
- **Never** call `delay()` or `UUID.randomUUID()` inside a `Stage` body — Stage functions must remain referentially transparent. The `delayFn` (for retry back-off) and `idGen` (for pagination task IDs) live in the `Pipeline` coordinator and are injected via the `Pipeline` constructor; they are called by the coordinator, never by a Stage.
- New code in `domain/pipeline/**` **MUST** be accompanied by a pure-Kotlin test in `domain/src/test/kotlin/` using a fake registry.
- `SaveBatchInterpreter` and `ScheduleRetryInterpreter` must be registered (satisfying the `SideInterpreterRegistry` API), but production uses `NoOp*` variants — the actual save path goes through `CrawlRunner.runTarget` directly, and the actual retry delay is handled inline by the coordinator loop.

## Links

- Commit `e1259e8` — initial functional refactor
- Commit `21a511e` — complete declarative pipeline
