---
title: "Dead code purge — DomainError isStopped/isDrop + Pipeline.processItems task parameter"
date: 2026-09-09
tags: [dead-code, domain, code-quality]
---

## Context

Static analysis and code review identified several units of dead code with zero callers:

1. `DomainError.isStopped()` default implementation (line 14) — overrides in `DepthExceededError`, `StoppedCrawling`
2. `DomainError.isDrop()` default implementation (line 15) — override in `DropItemError`
3. `Pipeline.processItems(page, task, sides)` — `task` parameter never used in body
4. `@Suppress("UNUSED_PARAMETER")` on `processItems` — a lint suppression for dead code

## Decision

Remove all five units:

**`DomainError.kt`:**
- Delete `fun isStopped(): Boolean = false` (line 14)
- Delete `fun isDrop(): Boolean = false` (line 15)
- Delete `override fun isStopped()` from `DepthExceededError` (line 51)
- Delete `override fun isStopped()` from `StoppedCrawling` (line 70)
- Delete `override fun isDrop()` from `DropItemError` (line 88)

**`Pipeline.kt`:**
- Remove `task: Crawling` parameter from `processItems`
- Remove `@Suppress("UNUSED_PARAMETER")`
- Update call site from `processItems(done.page, done.task, sides)` to `processItems(done.page, sides)`

**`Side.kt`:**
- Update KDoc "pure functions" claim to "effect-recording" — stages produce `Side` data values but call suspend functions (download, save) that are interpreted externally. Not truly pure, but effect-recording.

## Rationale

- Zero callers confirmed by grep across entire codebase
- `@Suppress` on dead parameter is misleading — implies the parameter is intentionally unused "for now"
- The misleading KDoc claim about "pure" was contributing to confusion in code reviews

## Consequences

- **Positive**: Cleaner domain model, no dead code
- **Positive**: Honest documentation (effect-recording vs pure)
- **Breaking**: If any external code calls `DomainError.isStopped()` or `isDrop()`, it will break (no external callers exist)

## Test impact

All existing tests pass — no functional change. `PipelineConcurrencyTest` added in PR 17 already exercises `processItems` via `pipeline.run()`.
