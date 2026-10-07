---
title: "Test schemas are discovered, never listed — and refuse to guess"
date: 2026-10-08
tags: [testing, architecture, database]
---

## Context

`SavedItemsPersistencePostgresTest` — the suite that exists specifically to prove the
crawler's write path works against the crawler's own migrations — ended with:

```kotlin
listOf("V1__init.sql", "V2__catalog_columns.sql").forEach { name -> ... }
```

A hardcoded list. When a V3 lands, the container keeps the V1+V2 schema, every test still
passes, and production runs V3. Nothing fails, because "migration not applied" is
indistinguishable from "migration applied and changed nothing".

This is not a hypothetical for *this* crawler. `V2__catalog_columns.sql` drops the unique
constraint `uq_scraped_items_content_hash` and adds four columns that `upsertSavedItems`
writes **by name**. Every statement in the write path is coupled to the migration set, so
the set has to come from where it lives.

The storefront hit the identical defect the same day, in
`wb-shop/tests/global-setup.ts`. Two repositories, one contract, one mistake — which is
what prompted writing this down rather than fixing the second occurrence silently.

Discovering the migrations then turned up two non-obvious obstacles:

1. The `tests` module depends on `:app`, and Gradle hands it the app's **jar**, not its
   exploded resources directory. `ClassLoader.getResource("db/migration")` therefore
   returns a `jar:` URL and `File(uri).list()` returns nothing.
2. `ClassLoader.getResourceAsStream("/db/migration/V1__init.sql")` returns **null**,
   because the classloader variant treats the leading slash as part of the name.
   `Class.getResourceAsStream` keeps it. A `checkNotNull` here is the only thing that
   caught it — the alternative was a suite applying nothing.

## Idea

- **(a)** Keep the list and update it by hand. Cheapest, and the exact defect.
- **(b)** Point at the crawler's Flyway location and let Flyway own the ordering.
  Most correct, but it drags the runtime into the test fixture for no benefit — the tests
  apply plain SQL over JDBC already.
- **(c)** Discover `V*.sql` from the classpath, ordered by the parsed Flyway version, and
  fail loudly on anything unreadable.

## Decision

Migration discovery lives in `tests/src/test/kotlin/ru/wbparser/testing/PostgresFixture.kt`
as `Migrations.discover()`. It walks the classpath location, accepts both the `file:` and
`jar:` layouts, filters `V<digits>__*.sql`, sorts by the parsed version as an **integer**,
and `check`s that the result is non-empty.

Every failure mode raises rather than returning an empty list:

- no `db/migration` on the classpath → the app module is not on the test runtime classpath
- a protocol other than `file:`/`jar:` → refuse, instead of reporting "no migrations"
- a discovered name that cannot be read → refuse
- an empty result → a suite that applied nothing would assert against an empty database

## Rationale

(b) is the right answer if the ordering ever becomes subtle — checksummed migrations,
out-of-order delivery, baselines. It is not yet: two files, applied in version order,
over JDBC. (c) removes the failure mode at the cost of a dozen lines that will be deleted
if Flyway ever earns its place here.

Sorting by the parsed integer rather than by string is not defensive decoration.
`V10__x.sql` sorts before `V2__x.sql` lexicographically and would be applied out of order,
which for this schema means a `RENAME COLUMN` landing before its `CREATE`.

The refusals exist because a discovery helper that returns empty on an unreadable layout
is worse than no helper: it restores the original defect with extra steps.

## Consequences

- **Always** discover test schemas from the classpath; **Never** enumerate migration
  filenames in a test fixture. An unapplied migration cannot be asserted on, so the only
  defence is not keeping a list to forget.
- **Never** weaken `Migrations` to tolerate a layout it does not recognise. A suite that
  quietly applies nothing is the defect this file exists to prevent.
- **Always** read migration bodies with `Class.getResourceAsStream`, not the classloader —
  the leading slash difference fails silently as `null`.
- `TargetQueriesPostgresTest` and `SavedItemsPersistencePostgresTest` share
  `PostgresFixture`, and both call `db.clear()` from `beforeTest`. The SQLite suites
  already did; the Postgres ones did not, so their results depended on declaration order.
- The executed-test floor in `config/gates/test-runs-floor.txt` is raised to 44 in the same
  commit. Verified by control: removing `TargetQueriesPostgresTest` leaves Gradle reporting
  `BUILD SUCCESSFUL` while the floor gate exits 1 with "ran 31 tests, floor is 44".
- Verified by drift probe: a simulated `V3` renaming `price_kopecks` fails 5 of 5 write-path
  tests and leaves `TargetQueries` green. Under the old list it would have failed none.