# Kotlin Dead Parameter Detection

## When to use this skill

Use when:
- A function has a parameter that appears unused (parameter named but never referenced in body)
- `@Suppress("UNUSED_PARAMETER")` is present and you're deciding whether to keep or remove it
- Refactoring a function signature and need to know if a parameter can be safely removed
- Auditing code for dead code after a refactor

## Decision tree: `@Suppress("UNUSED_PARAMETER")`

```
Is the parameter actually used inside the function body?
│
├── YES → Remove @Suppress (it's a false positive from linter)
│
├── NO (parameter is genuinely unused)
│   │
│   ├── Is the parameter required by an interface or abstract method signature?
│   │   ├── YES → Rename to _ (underscore) to make it explicit
│   │   │         Example: override suspend fun onResult(result: Unit) { }
│   │   │         Rewrite: override suspend fun onResult(_: Unit) { }
│   │   └── NO
│   │       │
│   │       ├── Is the parameter there for API symmetry or future use?
│   │       │   ├── YES → Keep the parameter, keep @Suppress, add KDoc explaining why
│   │       │   │         Example: fun processItems(page, task, sides) where task
│   │       │   │         is for future enrichment (PR 19: was dead, removed)
│   │       │   └── NO → DELETE the parameter and update all call sites
│   │       │
│   │       └── Is the function part of a public API that must preserve signatures?
│   │           YES → Rename to `_` (underscore), do NOT delete
│   │           NO → DELETE parameter + update call sites
```

## When to use `_` (underscore) instead of deleting

Use `_` when the parameter is required by an external contract you cannot change:

```kotlin
// CORRECT — interface requires this parameter, rename to _ to show it's unused
class MyListener : EventListener {
    override suspend fun onEvent(data: Event) {  // data required by interface
        log.info("Event received")              // data never used
    }
}

// Better — use _ to show intentional non-use
override suspend fun onEvent(_: Event) {
    log.info("Event received")
}
```

## When to DELETE a parameter

Delete when:
1. No callers use the parameter (confirmed by grep across entire codebase)
2. No interface or abstract method requires it
3. No external API contract requires it

## `@Suppress` decision guide

| Situation | Action |
|---|---|
| Parameter is used | Remove `@Suppress` |
| Parameter required by interface, truly unused | Rename to `_` |
| Parameter for debugging/future use, documented | Keep `@Suppress` with KDoc |
| Parameter dead, zero callers | **DELETE parameter**, remove `@Suppress` |

**Never leave `@Suppress("UNUSED_PARAMETER")` "just in case"** — it's technical debt that misleads future readers.

## Case study: `processItems` in `Pipeline.kt`

```kotlin
// BEFORE (PR 19) — task never used
@Suppress("UNUSED_PARAMETER")
private suspend fun processItems(
    page: ParsedPage,
    task: Crawling,      // ← never used in body
    sides: MutableList<Side>,
): Int { ... }

// grep callers: processItems(done.page, done.task, sides)
// grep body: task. never referenced

// DECISION: DELETE task parameter + update call sites
// AFTER (PR 19)
private suspend fun processItems(
    page: ParsedPage,
    sides: MutableList<Side>,
): Int { ... }
```

## Testing after parameter removal

Always run the full test suite after removing a parameter:

```bash
./gradlew domain:test infrastructure:test
```

If tests fail, the parameter may have been used indirectly (via reflection, serialization, etc.) or a caller still passes it.

## Related Skills

- `dead-code-purge` — safe removal workflow for larger dead code units
- `pipelines` — `processItems` case study
