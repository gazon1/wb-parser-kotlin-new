---
title: "Arrow Either.map side-effect analysis — PR 20 conclusion"
date: 2026-09-09
tags: [arrow, pipeline, code-quality]
---

## Context

`PipelineRunner.run()` used `pipeline.run(tasks).map { (crawled, sides) -> interpretAll(sides); crawled }`.
Initial plan classified this as an anti-pattern warranting PR 20.

## Analysis

Arrow `onRight` has signature:
```kotlin
fun <L, R> Either<L, R>.onRight(action: (R) -> Unit): Either<L, R>
```

It **preserves the original Either type** — it only performs a side effect without changing the Right type.
`PipelineRunner.run()` needs to transform `Either<DomainError, Pair<Crawled, List<Side>>>` into `Either<DomainError, Crawled>`.
Using `onRight` alone cannot achieve this — we'd still have `Pair<Crawled, List<Side>>`.

The `map { side-effect; transformedValue }` pattern is **not** an anti-pattern when:
1. A side effect is performed (interpretAll)
2. AND the Right value is transformed into a different type (Pair → Crawled)

The lambda's last expression (`crawled`) is the transformed value — not `Unit`.

## Decision

**No code change required.** The original pattern is correct. Documenting the finding in this ADR and the `arrow-either-anti-patterns` skill to prevent future misclassification.

Updated `PipelineRunner.kt` KDoc with an explicit note explaining why `map` is used here (not an anti-pattern).

Updated `arrow-either-anti-patterns` skill with a section clarifying:
- `onRight { side-effect }` — when the last expression is `Unit` (pure side effect, no type change)
- `map { side-effect; value }` — **correct** when the last expression is a transformed value

## Consequences

- `PipelineRunner` code is unchanged (already correct)
- `arrow-either-anti-patterns` skill now has accurate guidance with the `map { side-effect; value }` distinction
- Future reviews will correctly identify true `map` anti-patterns (last expression = `Unit`)
