---
title: "Job timestamps are written as offsets, not as naive local times"
date: 2026-10-20
tags: [database, pipeline]
---

## Context

`crawl_jobs.started_at`, `completed_at` and `last_heartbeat_at` are `TIMESTAMPTZ`.
The heartbeat that tells "this job is still alive" from "this job was left behind
by a dead process" was first written through `java.sql.Timestamp`, which carries
no offset. Postgres reads such a value in the session time zone, so what landed
in the column was the JVM's wall clock interpreted as UTC — on a host in UTC+3
every job looked three hours older than it was.

The rebase onto `origin/main` changed the neighbourhood: `crawl_jobs` moved to
Exposed (`CrawlJobsTable`), and every other column of that table is written
through the DSL.

## Idea

- Write every `crawl_jobs` timestamp through Exposed's `timestampWithTimeZone`
  columns, which carry an `OffsetDateTime` from the call site to the wire.
- Keep the reconciliation in the same dialect — the codebase has one way of
  expressing SQL, and a second one is a second thing to keep in step.

## Decision

**`CrawlJobQueries` writes through the Exposed DSL.** The heartbeat update and the
reconciliation both use `CrawlJobsTable` and `OffsetDateTime.now(ZoneOffset.UTC)`.

The effective timestamp of a job is `COALESCE(last_heartbeat_at, started_at,
created_at)`. The DSL has no `COALESCE` helper that preserves the null semantics
of a `TIMESTAMPTZ` column, so the condition is spelled out as three branches: the
heartbeat, else the start time, else the creation time.

## Rationale

A heartbeat that is three hours off makes its one question unanswerable. A job
that started three hours ago looks stale the moment it starts, and a job that
started just now looks stale on the next check — the reconciler then marks live
crawls as crashed, which is the one thing it must never do. Comparing "how old
is this heartbeat" with a duration needs one time base, and the offset-carrying
column is the only path in this codebase that keeps it.

## Consequences

- **Always** write `crawl_jobs` timestamps through Exposed with an `OffsetDateTime`.
- **Never** use `java.sql.Timestamp` for a `TIMESTAMPTZ` column: it is a naive
  local time, and Postgres interprets it in the session time zone.
- The reconciliation stays a single statement over all stale rows, and its result
  does not depend on the time zone of the machine that runs it.
- A job with no heartbeat and no start time falls back to its creation time — the
  third branch — so a row that was never touched still ages out.
