# Refactor Without Fear — Behavior-Preserving Pipeline Extraction

## When to use this skill

Use when:
- A method is too long (>80 lines) and needs to be split
- You need to extract helper methods from a large function
- You want to refactor but don't have enough test coverage
- The user mentions "refactor this", "extract method", "split this function", or "reduce complexity"

## Core Principle

**Behavior-preserving refactor**: after each small step, the system behaves identically from the caller's perspective. The refactor is complete when all tests pass and nothing else changes.

```
Refactor step          │ Tests │ Behavior
───────────────────────┼───────┼─────────────────────────────
Extract method A       │  +N   │  unchanged
Inline old code        │  +N   │  unchanged
Rename variable        │  +N   │  unchanged
Move to helper class   │  +N   │  unchanged
```

If tests fail after a step → **revert**, don't patch forward.

## Workflow

### Step 0 — Safety net (characterisation tests)

Before touching the code:

1. **Read the existing tests** — they define expected behavior
2. **Run the full test suite** — must be green before starting
3. **If coverage is low**, write characterisation tests that record current behavior:
   ```kotlin
   // Characterisation test — documents what the code currently does
   test("run() returns correct stop reason when page is empty") {
       val pipeline = Pipeline(...)
       val result = pipeline.run(listOf(emptyPageTask))
       result.shouldBeRight()  // exact assertion doesn't matter — it documents current behavior
   }
   ```

### Step 1 — Identify natural seams

Look for:
- **Repetitive blocks** — same pattern appearing 3+ times → extract a helper
- **Single-responsibility violations** — one function doing download AND parse AND save
- **Comment boundaries** — "--- Download stage ---" comments are natural method boundaries
- **Variable scopes** — variables that "belong" to one section

### Step 2 — Extract in the smallest verifiable chunks

Extract one helper at a time. Each extraction should:
1. Be <20 lines
2. Have a clear input/output contract
3. Not change the outer function's behavior

Example — extracting save logic from `Pipeline.run()`:

**Before (inline in run()):**
```kotlin
when (val r = save(listOf(enriched))) {
    is Done<List<SavedItem>, Unit> -> {
        sides += r.sides()
        itemsSaved++
    }
    is Fail -> sides += Side.Log(LogLevel.ERROR, "Save stage failed: ${r.failure.message}")
    is Retry -> sides += Side.Log(LogLevel.WARN, "Save stage requested retry")
    is Cont -> { /* save stages never emit Cont */ }
}
```

**After (extracted helper):**
```kotlin
// Helper: returns 1 if saved, 0 otherwise; records sides
private fun saveAndRecord(item: SavedItem, sides: MutableList<Side>): Int {
    return when (val r = save(listOf(item))) {
        is Done<List<SavedItem>, Unit> -> {
            sides += r.sides()
            1
        }
        is Fail -> {
            sides += Side.Log(LogLevel.ERROR, "Save stage failed: ${r.failure.message}")
            0
        }
        is Retry -> {
            sides += Side.Log(LogLevel.WARN, "Save stage requested retry")
            0
        }
        is Cont -> { /* save stages never emit Cont */ 0 }
    }
}

// In run():
itemsSaved += saveAndRecord(enriched, sides)
```

**Key insight**: the helper returns an `Int` (count), not the saved item, to avoid changing `itemsSaved += 1` logic. The helper is `private` so Kotlin infers types precisely — no type friction.

### Step 3 — Verify after each extraction

```bash
./gradlew :domain:test --no-daemon
```

If it fails: **revert immediately** (`git checkout Pipeline.kt`), understand why, then try again with a different extraction strategy.

### Step 4 — Verify no behavioral change

After all extractions:
1. Run full test suite
2. Check git diff — only structural changes (method extraction), no logic changes
3. Check line counts — each helper ≤20 lines

## Common Pitfalls

### Pitfall 1: Extracting too early

```kotlin
// DON'T extract this 3-line block into a helper
val url = page.nextPageUrl
if (url != null) pending.add(Crawling(...))
```

Wait until the block is ≥10 lines OR appears 3+ times. Small helpers increase indirection without reducing complexity.

### Pitfall 2: Changing the API to accommodate the helper

```kotlin
// BAD — changing return type to accommodate the helper's design
private fun runSaveItem(item: ParsedItem): Either<Stop, SavedItem?>  // changes semantics!
```

Prefer extracting helpers that fit the existing contract. If the helper needs to change the contract, reconsider the extraction.

### Pitfall 3: Introducing new state

```kotlin
// BAD — helper creates new mutable state that the outer function doesn't track
private fun runPagination(task: Crawling): List<Crawling> {
    val newTasks = mutableListOf<Crawling>()  // new state — risky
    // ...
    return newTasks
}
```

Keep state in the outer function; helpers should be stateless transformations or state-modifying commands with clear side effects recorded in passed collections.

### Pitfall 4: Type friction with Kotlin's type system

When extracting helpers that cross suspend/non-suspend boundaries or Either/Result boundaries:

- If a helper needs to propagate a failure, **don't** return `Either` from a non-suspend helper called from a suspend function — the types may not unify cleanly.
- Instead, return a plain value or `Result<T>`, and handle the failure in the caller.

## Pipeline.run() Extraction Pattern

`Pipeline.run()` is a good candidate for extraction because:
1. It has distinct phases: download → parse → filter/enrich/save → pagination
2. Each phase has a natural boundary ("--- Download stage ---" comment)
3. The helpers (`runDownloadStage`, `runParseStage`) can be made private with precise type inference

The pattern used:
- **Helpers are `private`** — enables precise type inference
- **Return nullable types for flow control** — `null` means "continue the loop", non-null means "use the value"
- **Failure propagation via Result or Either** — handled at the call site in `run()`
- **Sides collected via `MutableList<Side>` parameter** — explicit mutation, no hidden state

## When NOT to refactor

- **Tests are red** — fix tests first
- **The code is clear as-is** — extracting a 50-line function into two 25-line functions adds indirection without clarity if the function is already well-structured
- **You're unsure about the behavior** — write characterisation tests first
- **The change is purely stylistic** with no readability gain — don't refactor

## Related Skills

- `pipelines` — understanding the Stage/Side/Interpreter model that Pipeline.run() operates in
- `kotlin-test-boundary` — where to place characterisation tests
- `dead-code-purge` — safe removal of extracted dead code

## Sources

- Martin Fowler — "Refactoring" (behaviour-preserving transformations)
- Kent Beck — "Make it work, make it right, make it fast" (in that order)
- Robert C. Martin — "Clean Code" (single responsibility, method length as a smell)
