---
name: dead-code-purge
description: Safely remove dead code from a Kotlin codebase. Use when the user mentions "delete dead code", "git rm unused", "purge dead", "remove unused function", "find uncalled method", or when refactoring to identify and eliminate unreachable code.
---

# Dead Code Purge — Safe Removal Checklist

## When to use this skill

Use whenever you need to delete code that appears unused:
- Removing a function, class, or interface
- Purging an entire dead module or subsystem
- Cleaning up legacy abstractions after a refactor
- Any time you consider `git rm` or `delete` for something that might have hidden callers

## Core Principle

> **Never `git rm` without grep first.** A symbol having zero callers in the current branch doesn't mean it's truly dead — it may be called through reflection, serialization frameworks, DI containers, or in code generated at compile time.

---

## The Checklist

### Step 1 — Identify candidates

```bash
# Find all top-level declarations in a file (classes, functions, properties)
grep -n "^fun \|^class \|^object \|^data class \|^interface \|^val \|^var " file.kt

# Find potentially unused classes/functions (look for "internal" or "private")
grep -rn "class\|fun" domain/src/main --include="*.kt" | grep -v "^.*:.*//" | wc -l
```

### Step 2 — Verify zero callers (required before ANY deletion)

```bash
# Simple function name — case-insensitive grep across all .kt files
grep -rn "fetchSavedItemByHash" --include="*.kt" .

# Constructor call — look for "ClassName("
grep -rn "StageFailure.Stopped\|TargetQueries\|WbCatalogInterceptors" --include="*.kt" .

# Type used as return type or parameter — look for "TypeName"
grep -rn "StopReason\|Stop.NoNextPage\|DomainError.isStopped" --include="*.kt" .
```

**Rule**: If grep returns ANY result outside the **definition itself**, you CANNOT delete. Investigate the caller first.

### Step 3 — Check indirect callers

Some callers are not textual — verify these too:

```bash
# Serialization (kotlinx.serialization / Jackson) — class must exist at runtime
grep -rn "@Serializable\|@JsonProperty\|@SerializedName" --include="*.kt" .

# Spring / Koin DI — registered by interface or class name string
grep -rn "@Bean\|@Service\|@Repository\|@Component\|single\|bind" --include="*.kt" .

# Enum or sealed interface — all variants referenced
grep -rn "sealed interface\|enum class" domain/src/main --include="*.kt"

# Test-only callers (grep excludes tests when you only grep main)
# BUT: always check tests too when removing a public/internal API
```

### Step 4 — Check inheritance / overrides

```bash
# Interface implementation
grep -rn ": InterfaceName\|implements\|: DomainError" --include="*.kt" .

# Override methods
grep -rn "override fun\|override val" --include="*.kt" .
```

### Step 5 — Remove in dependency order

If removing multiple things, delete in this order (downstream → upstream):

1. **Callers** (functions that use the target)
2. **Sub-types** (data classes / sealed variants that implement/extend the target)
3. **The target itself** (the function/class/interface)
4. **Imports** (remove unused imports after deletion)

```kotlin
// Example dependency order for removal:
// BEFORE:
//   StageFailure.Stopped (data class) ← called by StageFailureTest (test)
//   toDomainError() (when branch) ← calls StageFailure.Stopped
//   StopReason (sealed interface) ← referenced by StageFailure.Stopped
//
// REMOVE IN ORDER:
//   1. toDomainError() branch referencing StageFailure.Stopped
//   2. StageFailure.Stopped data class
//   3. Any test cases for StageFailure.Stopped
//   4. StopReason if no other callers remain
```

### Step 6 — Build verification (MANDATORY after every deletion)

```bash
# After every single file deletion or symbol removal:
./gradlew :domain:compileKotlin :infrastructure:compileKotlin :app:compileKotlin --no-daemon

# If compilation fails — you missed a caller. Restore and re-check.
```

### Step 7 — Run tests

```bash
./gradlew :domain:test :infrastructure:test :tests:test --no-daemon
```

---

## Anti-Patterns

### BAD: Deleting based on IDE "unused" warning alone
```kotlin
// IDE says "unused" — but kotlinx.serialization NEEDS this class at runtime
@Serializable  // IDE marks as unused but it's required for JSON parsing
data class WbItemDto(...)
```
**GOOD:** Always verify with grep AND build.

### BAD: Deleting a interface without checking implementations
```bash
# Found "interface StopReason" — trying to delete it
# BUT: StageFailure.Stopped(val reason: StopReason) IMPLEMENTS it
```
**GOOD:** Check inheritance hierarchy before deletion.

### BAD: Deleting a function used only in tests
```bash
# grep on main/ shows zero results
# grep on tests/ shows 5 results
# The function IS used — just in tests
```
**GOOD:** Always grep across `tests/` too, unless you specifically want to remove the test code.

### BAD: Deleting a default method implementation
```kotlin
// isStopped(): Boolean = false  — looks unused
// BUT: StoppedCrawling() overrides it = true
```
**GOOD:** Check which subclasses override a default before removing the default.

---

## Decision Tree — "Can I delete this?"

```
Is there a caller in main source?
├── YES → CANNOT delete. Investigate caller.
└── NO (zero callers in main)
    ├── Is it a public/API type? (serialization, DI, reflection)
    │   ├── YES → CANNOT delete without deeper investigation.
    │   └── NO
    └── Are there callers in tests?
        ├── YES → CANNOT delete without removing tests too.
        └── NO
            └── Is it part of a dependency chain?
                ├── YES → Delete downstream first (callers, then the target).
                └── NO → SAFE TO DELETE.
                         Run build + tests immediately after.
```

---

## wb-parser-kotlin Specific Patterns

### Removing a StageFailure variant
1. Remove the variant from `StageFailure.kt`
2. Remove the `when` branch in `toDomainError()` that handles it
3. Update any test that constructs that variant
4. Remove unused imports (e.g., `StoppedCrawling` if Stopped was the only caller)

### Removing a database query function
1. Verify zero callers: `grep -rn "fetchSavedItemByHash" --include="*.kt" .`
2. If it's in a file with other functions — rewrite the file, keeping the live functions
3. If it's the only function — delete the entire file
4. Verify `build.gradle.kts` dependencies don't expect it

### Removing a Side variant
1. Check `SideInterpreterRegistry.dispatch()` — remove the `is Side.X ->` branch
2. Check `Interpreter.kt` — remove the corresponding parameter from `SideInterpreterRegistry`
3. Check all `NoOp*Interpreter` implementations
4. Check all test interpreters in `InMemoryAdapters.kt`
5. Run build — if `Side.X` is still referenced somewhere, compilation fails

---

## Related Skills

- `kotlin-test-boundary` — deciding where tests should live after deletion
- `refactor-without-fear` — behavior-preserving refactors that don't require deletion
- `pipelines` — Side/Stage/Step removal order ( Side → Interpreter → Registry)

## Sources

- IDE "unused symbol" inspections are compile-time only — they miss reflection, serialization, DI
- Martin Fowler "Refactoring" — "Before you delete a function, make sure it's truly dead"
- Kotlin serialization requires all `serializable` classes to be present at runtime
