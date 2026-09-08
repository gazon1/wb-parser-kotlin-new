---
title: "Dead code purge — PR 9 cluster C"
date: 2026-09-09
tags: [cleanup, architecture]
---

## Context

After PR 7-8 integration testing work, several dead code items were identified:

- `StageFailure.Stopped(reason: Stop?)` — only used in tests, never in production
- `DataSource.fetchTargetByUuid` + `DataSource.updateTargetLastScheduled` — zero callers
- `DataSource.fetchSavedItemByHash` — zero callers
- `DomainError.isStopped()` / `isDrop()` defaults — zero callers
- `applyCatalogJsonHeaders(response: Fetched): Fetched = response` — identity function, zero callers
- Empty `is Cont -> {}` branch in `Pipeline.run()` — `Step.Cont` never emitted by production stages

## Decision

**Removed in dependency order:**

1. `StageFailure.Stopped` data class (`domain/pipeline/StageFailure.kt:67-71`)
2. `toDomainError()` branch referencing `StageFailure.Stopped` (now just maps to `StoppedCrawling` only)
3. `import StoppedCrawling` from `StageFailure.kt` (unused after branch removal)
4. Two test cases in `StageFailureTest` that exercised `StageFailure.Stopped`
5. `fetchTargetByUuid` + `updateTargetLastScheduled` (`TargetQueries.kt`) — rewritten file to keep only `fetchActiveTargets`
6. `fetchSavedItemByHash` (`SavedItemQueries.kt`) — rewritten file to keep only `upsertSavedItems`
7. `applyCatalogJsonHeaders` (`WbCatalogInterceptors.kt`) — rewritten file to keep only typealiases
8. Empty `is Cont -> {}` branch — replaced with documented `is Cont -> { /* save stages never emit Cont */ }`
9. `isStopped()` / `isDrop()` defaults left in `DomainError` with explanatory comment — removing them AND their overrides requires removing 3 override declarations across subclasses; marked as future cleanup

## Consequences

- `StageFailure` sealed interface now has 6 variants (was 7)
- `toDomainError()` has 6 branches (was 7)
- `WbCatalogInterceptors.kt` reduced to 2 typealiases (was 3 — 1 function)
- `Pipeline.run()` save-stage `when` is still exhaustive (added explicit `Cont` comment)
- `DomainError` still has `isStopped()` / `isDrop()` defaults — zero callers confirmed, removal deferred

## Rules from this cleanup

- **Always** grep for callers before `git rm` — IDE "unused" warnings miss serialization, DI, reflection
- **Always** verify zero callers in tests too
- **Always** rebuild + run tests after every deletion
- **Never** delete a default interface method without checking all override sites
