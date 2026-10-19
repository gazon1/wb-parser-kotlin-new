---
title: "The table definitions are compared with the schema the migrations create"
date: 2026-10-19
tags: [database, schema, test, migrations]
---

## Context

`ExposedTables.kt` is the only description of the database the application has.
The Flyway migrations are the database. Nothing compared them.

The two had already drifted once — `price_kopecks` was renamed in a migration, and
the only way it was noticed was a manual probe, run by hand, remembered by
whoever ran it last. A column renamed in a migration, a type widened, a column
made nullable: the write path keeps compiling, because the model did not change,
and fails at runtime, on the column it names.

The model was not a faithful description of the schema. It declared `text` for a
`TEXT[]` column, `text` for a `JSONB` column, a not-null `data` for a nullable
one, and — the expensive one — a naive `timestamp` for three `TIMESTAMPTZ`
columns. The naive timestamp was not a cosmetic difference: an instant written
through it became local wall time, which PostgreSQL read back as UTC, so every
stored timestamp was shifted by the machine's offset.

## Idea

1. **Write the expected schema out by hand in the test.** Rejected: that is a
   third description to keep in sync, and it encodes the same mistake as the
   model it is supposed to check.
2. **Compare the model against `information_schema` through a hand-written type
   mapping.** Rejected: the mapping is where a difference goes to hide. A type
   the mapping forgot is a type nobody compares.
3. **Let the model build its own schema, and compare the two.** Chosen.

## Decision

`SchemaDriftTest` (in the `tests` module) creates the Exposed tables in a
scratch schema of the same PostgreSQL container the migrations were applied to,
and compares the two schemas column by column: name, type, nullability.

The type is compared through PostgreSQL's own `format_type`, so both sides of
the comparison are the database's words — `character varying(255)` on one side
and `text` on the other are visibly different, with no mapping table in between.

The migrated schema is the contract: it is what production runs. When the two
disagree, the model is wrong and is corrected to match. The six differences the
gate found on its first run were all in the model, and the model was fixed:

- `crawl_targets.allowed_domains` is `TEXT[]` — a list of domains, not a string.
- `crawl_targets.parsing_rules` is `JSONB` — raw JSON, held as a JSON element.
- `scraped_items.data` is nullable, because V1 declares it so.
- `scraped_items.scraped_at`, `crawl_targets.created_at` and
  `crawl_targets.updated_at` are `TIMESTAMPTZ`, so the model uses
  `timestampWithTimeZone` and writes a real instant.

One table was missing from the model altogether: `crawl_jobs`. The crawler wrote
it with hand-written SQL — `INSERT INTO crawl_jobs …`, `setTimestamp`, string
literals for the status — so the gate had nothing to compare against it and the
table was the one place where a drift could not be caught. `crawl_jobs` is now
described by `CrawlJobsTable`, and the write path goes through Exposed like
every other table. Two things came with that: the timestamps are written as
`OffsetDateTime` in UTC instead of `setTimestamp` in the JVM's zone (the same
shift the naive timestamp column caused, one level up), and the status values
are the ones the model declares instead of strings spelled out at the call site.

## Rationale

Letting the model create the schema removes the second description entirely:
there is one description, and the database is the other side of the comparison.
The gate therefore cannot disagree with the model about what the model means —
it can only say that the model and the database mean different things.

The direction of the fix is not arbitrary. The schema is deployed: production
runs it, and changing it would mean a migration and a data migration for a
description that was never used. The model is the thing that is wrong.

The gate runs where the truth is: a real PostgreSQL with the real migrations
applied. A test that compared a mock would have compared the model with itself.

## Consequences

- **Always** change the model and the migration in the same commit when a column
  changes. The gate is what makes the order irrelevant — but only if both
  change.
- **Never** change the schema to match the model. The model is the description;
  the schema is the contract that production runs.
- **Always** write instants through a zoned column type. A naive timestamp in a
  `TIMESTAMPTZ` column is a silent shift by the machine's offset, and the test
  suite cannot see it on a UTC host.
- **Never** write a table with hand-written SQL while the model describes the
  rest of the schema. A table without a description is a table the drift gate
  cannot see, which makes it the one place a drift hides.
- The gate compares columns — names, types, nullability. It does not compare
  defaults, indexes or constraints: the unique index on
  `(target_id, content_hash)` and the `now()` defaults are described in the
  migrations and read by the code, and a comparison of them would be a second
  specification to maintain.
