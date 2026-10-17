---
title: "Stage data class + StageDSL for readable stage construction"
date: 2026-10-17
tags: [pipeline, architecture, pr-13]
---

## Context

`Stage<I, O>` was a typealias for `suspend (I) -> Step<I, O>`. This was clean but had two limitations:

1. **No metadata** — a lambda has no name or description, making pipeline debugging harder (stages appear as "unnamed-lambda #42" in stack traces)
2. **Construction ceremony** — a named stage required a multi-line block with explicit `Step.Done(...)` wrapping

## Idea

Replace the typealias with a data class:

```kotlin
data class Stage<I, O>(
    val info: StageInfo,
    val run: suspend (I) -> Step<I, O>,
) {
    constructor(run: suspend (I) -> Step<I, O>) : this(StageInfo("unnamed"), run)
}
```

The secondary constructor preserves backward compatibility with all existing lambda constructions.

Add a DSL builder `stage(name, description) { input -> ... }` that provides `done()`, `fail()`, `retry()`, `doneIf()` helpers.

## Decision

- `Stage` is now a `data class` with `info: StageInfo` and `run: suspend (I) -> Step<I, O>`
- `StageInfo(name, description)` carries metadata for observability
- `stage<I, O>("name", "description") { input -> ... }` DSL with typed builder helpers
- `stageOf<I, O>(f)` still works for pure transformations
- `andThen` composes two `Stage` instances, building a compound `StageInfo` name
- `WbCatalogStage` converted to hold `val stage: Stage<Crawling, ParsedPage>` instead of extending `Stage`
- `PipelineFactory` wraps all stage lambdas in `Stage { }`

## Consequences

- `Stage` is no longer a typealias — `Stage(...)` construction required in a few places
- `.invoke()` calls become `.run.invoke()` (the function is now a property named `run`)
- `WbCatalogStage` now exposes `.stage` property instead of being a `Stage`
- Existing tests that construct `Pipeline` directly with lambdas continue to work via the secondary constructor
- New `stage()` DSL makes stage bodies read like domain language
