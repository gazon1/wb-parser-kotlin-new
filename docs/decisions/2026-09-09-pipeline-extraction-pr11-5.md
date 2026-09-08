---
title: "PR 11.5 — Pipeline.run() extraction — behaviour-preserving refactor"
date: 2026-09-09
tags: [refactoring, pipeline, PR-11-5]
---

## Context

`Pipeline.run()` was 113 lines of inline code with three structurally identical
`stageWithRetry` blocks (download, parse) and one item-processing loop.
The function was functionally correct but hard to read and impossible to modify safely:
any change to one stage required finding the identical pattern in the other two.

## Decision

Extracted three helper functions from `Pipeline.run()` using the
**characterisation-tests-first** workflow:

1. `runDownloadStage(task, sides): StageDecision<Fetched>` — 15 lines
   - Wraps `stageWithRetry(task, download)`
   - Returns `StageDecision.*` so the caller handles retry/cont/fail
   - Caller re-adds task to `pending` on retry

2. `runParseStage(task, fetched, sides): StageDecision<ParsedPage>` — 15 lines
   - Mirrors `runDownloadStage` structure for the parse stage
   - Caller re-adds `task` (not `fetched`) on retry — original task preserved

3. `processItems(page, task, sides): Int` — 18 lines, `suspend`
   - Filter → enrich → save for each item in the page
   - Returns `Int` (items saved count) — caller accumulates into `itemsSaved`
   - Calls `saveOne` which is suspend (contains retry delay)

`StageDecision` is a private sealed class inside `Pipeline`:

```kotlin
private sealed class StageDecision<out O> {
    class Done<O>(val output: O) : StageDecision<O>()
    class Retry(val task: Crawling) : StageDecision<Nothing>()
    class Fail(val error: DomainError) : StageDecision<Nothing>()
    class Cont : StageDecision<Nothing>()
}
```

**Not extracted** (deliberately):
- Pagination loop — tightly coupled to `pending`/`stopped`/`lastPageWasEmpty` state;
  extracting would require passing 4 mutable refs, adding complexity without benefit.

## Rationale

- **One concept per function** — each helper does exactly one thing
- **`StageDecision` keeps `run()` readable** — `when (decision)` replaces 6-line `when` blocks
- **Helper boundaries match stage boundaries** — download/parse/items/pagination are natural seams
- **No behaviour change** — 194 characterisation tests all pass without modification
- **`suspend` on `processItems`** — required because `saveOne` contains `delay()`

## Consequences

- `Pipeline.run()` reduced from 113 lines to ~55 lines
- Each helper is independently testable
- Adding a new stage (e.g. `dedup`) is now a mechanical extract step
- Pagination remains inline — defer extraction until a concrete need arises

## Workflow used

1. Write characterisation tests first (existing `PipelineTest` + `PipelineSaveOneRetryTest`)
2. Extract one helper → build → test → commit (3 iterations, each ≤30 lines diff)
3. All tests green at each step

## Related

- `kotlin-test-boundary/SKILL.md` — "Characterisation Tests for Behaviour-Preserving Refactors" section
