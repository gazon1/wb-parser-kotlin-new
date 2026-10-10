---
title: "Pipeline failure carries accumulated sides"
date: 2026-10-09
tags: [pipeline, either, side-effects]
---

## Context

When a batch member fails hard (e.g. network error, parse error), `Pipeline.run` returned
`Either.Left<DomainError>` before merging `batchSides` into `sides`. `PipelineRunner` then
called `Either.map` — which only executes on the `Right` branch — so every diagnostic emitted
by the successful tasks in that batch was silently discarded.

A single unrecoverable failure therefore erased all evidence of what the rest of the batch
had done before the failure occurred.

## Alternatives considered

1. **Accumulator parameter** — give `Pipeline.run` an explicit `MutableList<Side>` sink.
   Rejected: the problem is structural (wrong short-circuit point), not about parameter passing.
   The sink approach still requires fixing the `Either.Left` short-circuit.

2. **Accept the loss** — leave the behaviour as-is and document it.
   Rejected: knowingly wrong. Diagnostics lost on failure is a correctness bug, not a
   acceptable trade-off for simplicity.

3. **Sealed-third-Either** — introduce `PipelineFailure(error, sides)` as a third variant.
   Overkill: `Either` already has two branches; adding a third variant to `Either` itself
   is more invasive than extending the `Left` payload.

## Decision

`Either.Left` carries `Pair<DomainError, List<Side>>` instead of bare `DomainError`.

`Pipeline.run` return type changed from:
```kotlin
Either<DomainError, Pair<Crawled, List<Side>>>
```
to:
```kotlin
Either<Pair<DomainError, List<Side>>, Pair<Crawled, List<Side>>>
```

`PipelineRunner.run` replaced `.map { ... }` with `.fold`:
```kotlin
.fold(
    ifLeft = { (error, sides) ->
        interpreterRegistry.interpretAll(sides)  // ← now runs even on failure
        Either.Left(error)
    },
    ifRight = { (crawled, sides) ->
        interpreterRegistry.interpretAll(sides)
        Either.Right(crawled)
    },
)
```

`Pipeline.run` now passes `(error to sides)` in the Left branch — all sides accumulated up to
and including the batch where the failure occurred are preserved and delivered to the interpreter.

## Consequences

- **Always** interpret accumulated sides on failure — never lose diagnostic evidence
- `Pipeline.run` return type is a breaking change; all call sites that pattern-match on
  `Either.Left` must unpack the pair
- `PipelineFailure` third-Either variant remains available as a future option if more
  metadata needs to travel on failure
