## Problem

Проект использовал Kotlin 1.9.24, что не позволяло использовать Arrow 2.x и modern Exposed features.

## What was done

### Kotlin upgrade to 2.4.21
- `gradle/libs.versions.toml`: Kotlin 1.9.24 → 2.4.21
- All plugins updated to 2.4.21 (detekt, ktlint, serialization)
- `kotlin-stdlib` override pins removed (Kotlin 2.x manages automatically)

### Dependency upgrades
| Component | Before | After |
|---|---|---|
| Kotlin | 1.9.24 | **2.4.21** |
| Arrow | 1.2.1 | **2.2.3** |
| Kotest | 5.8.1 | **6.2.4** |
| Coroutines | 1.7.3 | **1.10.2** |

### Bugs fixed (upgrade side-effects)
- `SavedItemQueries.kt`: Added `Database.connect(this)` before `transaction {}` — Exposed transaction manager was uninitialized with raw `DataSource`
- `SavedItemQueries.kt`: Added `statement.addBatch()` after all column assignments — `BaseBatchInsertStatement.data` stayed empty without it
- `ExposedTables.kt`: Changed `data: Column<String> = text("data")` → `jsonb<SavedItem>` — PostgreSQL `jsonb` column rejects `text` type
- `domain/build.gradle.kts`: Added `compileOnly("org.jetbrains.exposed:exposed-json:0.55.0")` for `jsonb` import
- `TargetQueries.kt`: Same `Database.connect(this)` fix for `fetchActiveTargets()` and `firstActiveTargetId()`
- `ExposedTables.kt`: Removed unused `encodeToString` import (ktlint violation)

### Verification
- `check.sh` — **ALL CHECKS PASSED** ✅ (8/8 gates)
- `tests:test` — **68 tests, 0 failures** ✅

## References
- Arrow 2.x requires Kotlin 2.0+ (official compatibility matrix)
- `exposed-json` 0.55.0 pulls `kotlinx-serialization-json:1.7.1` which requires Kotlin 2.0+
