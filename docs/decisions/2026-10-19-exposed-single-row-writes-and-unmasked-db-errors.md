---
title: "Exposed writes are one statement per row, and database errors are never masked"
date: 2026-10-19
tags: [database, reliability, debugging]
---

## Context

Adding the `category_mappings` write path broke the whole crawler: every crawl
finished with `items=0`, and `scraped_items` stayed empty. Three layers of the
problem had to be unwound before the real cause was visible.

1. **The write was a multi-row batch.** `upsertCategoryMappings` built one
   `BatchUpsertStatement` and called `set(...)` + `addBatch()` once per category.
   Exposed 0.55's `BaseBatchInsertStatement.addBatch()` appends the *live* value
   map to the batch and clears it afterwards, so the batch ends with one **empty**
   row. That row is bound to the statement's placeholders positionally: its
   `level` (an int) lands in the `id` (a uuid) slot. Postgres rejected the whole
   statement with `column "id" is of type uuid but expression is of type integer`.

2. **One failed statement rolled back the items too.** `upsertSavedItems` writes
   the items and the category rows in a single transaction. The category statement
   was the last one in it, so its rejection rolled back the items that had
   already been written. The symptom was "the crawler saves nothing", which
   pointed at the item path rather than at the category path.

3. **The real error was never printed.** Exposed logs a failed statement by
   expanding its placeholders (`StatementContext.expandArgs`). Expanding the short
   row exhausted the argument iterator, so the logger threw
   `NoSuchElementException` *instead of* reporting the `SQLException`. The stack
   trace that reached the log said `NoSuchElementException` and pointed at
   Exposed's logging code — the actual database error existed only in the
   Postgres server log.

The third point is the one that cost the most: the failure was reported as an
error in a place where the error was not. Nothing in the application's own output
could distinguish "the insert was rejected" from "the logger crashed".

## Idea

- Write the category rows the same way the item rows are written: one statement
  per row, so a batch never carries a row with fewer columns than the statement.
- Keep the failed statement's stack trace in the log, and stop Exposed's own SQL
  logging from standing between the failure and the report.
- Pin the behaviour with a test that writes through production code and asserts
  on the database.

## Decision

**One statement per row for every write in this codebase.** `upsertCategoryMappings`
creates a `BatchUpsertStatement` per distinct category and executes it
immediately, mirroring `upsertOneItem`. The `where` clause repeats the conflict
key (`target_id`, `catalog_id`) instead of `Op.TRUE`, because a per-row statement
can carry a per-row condition — and a condition with no parameters keeps the
placeholder count and the argument count equal.

**The save stage keeps the stack trace.** `Pipeline.saveBatch` logs the failure
with `log.error(e) { ... }` before turning it into a `StageFailure.Database`. The
failure message in the database is unchanged; the stack trace goes to the log,
where it belongs. A `StageFailure` message says *what* failed, and only a stack
trace says *where*.

**Exposed's SQL logging is off by default.** `logging.level.Exposed` is
`${EXPOSED_SQL_LOG_LEVEL:INFO}`. At DEBUG, Exposed prints one line per row —
the crawl produced tens of thousands of them — and, worse, its expansion of a
failed statement is what threw and hid the database error. The level can be
raised per environment when a specific query needs inspecting.

**The behaviour is a test, not a comment.** `CategoryMappingsPostgresTest` writes
through `upsertSavedItems` against a real PostgreSQL and asserts on the rows:
a single item, a batch of sixty distinct categories, several items sharing one
category, a category re-observed with a different parent, and an item without a
category.

## Rationale

The multi-row batch was faster in theory and broken in practice. The failure
mode was the worst kind: a rejected statement that took the whole transaction
with it, reported through a logger that crashed before it could say why. One
statement per row costs one round trip per row — but the item path already pays
one round trip per item, so the write path's shape does not change; only its
row count does.

The alternative — a hand-written `INSERT ... ON CONFLICT` — was rejected: this
file's contract is that every statement is expressed with the Exposed DSL, and
raw SQL would be a second dialect to keep in step with the migrations.

Disabling Exposed's SQL logging is not only about noise. It removes the code
path that stood between a database failure and its report.

## Consequences

- **Always** write through one statement per row. A multi-row
  `BatchUpsertStatement` built with `set(...)` + `addBatch()` is rejected by
  Postgres in this schema.
- **Never** let a logging path stand between a failure and its report. If a
  failure cannot be explained from the application's own output, that is a defect
  in the reporting path, not a detail to work around.
- **Always** log the exception, not only its message, at the point where the
  exception is turned into a domain error.
- The item path writes one statement per item, and the category path one per
  distinct category. A future change that batches these needs the empty-row
  behaviour of `addBatch()` handled explicitly — and a test that would fail if
  it were not.
- `EXPOSED_SQL_LOG_LEVEL=DEBUG` re-enables the per-statement log for a
  debugging session. The crawl's log volume grows by two orders of magnitude
  while it is on.
