---
title: "Crawl jobs — heartbeat and crash reconciliation"
date: 2026-10-19
tags: [database, scheduling, reliability]
---

## Context

`crawl_jobs` has carried a `last_heartbeat_at` column and a `Crashed` status
since V1. Neither was ever used: nothing wrote the heartbeat, and nothing ever
moved a job out of `Running`.

The consequence is that a crawl killed by a container restart left its row in
`Running` forever. The admin API then reported jobs that had no process behind
them, and "is a crawl running right now?" had no answer — the only reliable
signal was the advisory lock, which lives in memory and says nothing about the
table.

The status is not a cosmetic problem either: `JobStatus` has no `Partial` value,
so a job that lost every target is recorded as `Failed` (see the comment in
`CrawlRunner.runUnsafe`). The table is careful about that distinction, and the
distinction was not being made for crashes at all.

## Idea

Use the schema the way it was designed: a running job refreshes a heartbeat, and
a job whose heartbeat has gone stale belongs to a process that is no longer
alive.

Alternatives considered and rejected:

- **Mark every `Running` job as crashed at startup.** Simple, and wrong: a second
  instance starting up next to a live crawl would mark that crawl's job as
  crashed, while the crawl kept running and later wrote `Completed` over it.
- **Reap by age alone (`started_at`).** A crawl that legitimately takes six
  hours would be reaped by a second instance.

## Decision

**The heartbeat is written.** `CrawlRunner.openJob` stamps
`last_heartbeat_at` when the job opens, and `withJobHeartbeat` refreshes it
every 30 seconds while the crawl runs. The ticker is cancelled as soon as the
crawl returns, so a finished job stops looking alive.

**Orphaned jobs are reconciled at startup.** `OrphanedJobReconciler` runs as an
`ApplicationRunner` and calls `reconcileOrphanedJobs`, which marks `Running` jobs
as `Crashed` when `COALESCE(last_heartbeat_at, started_at, created_at)` is older
than five minutes. The `COALESCE` matters: the heartbeat column existed from V1
and was never written, so every existing job has a null heartbeat and would
otherwise be invisible to the check.

**The reconciliation is safe next to a live crawl.** A job that is genuinely
being crawled keeps its heartbeat fresh, so it is never touched. Only a job that
has gone quiet for five minutes is marked — long enough that a slow crawl
between two heartbeats is not mistaken for a dead one, short enough that a
crashed crawl is noticed at the next startup rather than the one after that.

## Rationale

- The schema already declared the answer. The work was to make the code use it.
- The heartbeat is what makes the reconciliation safe. Without it, "is this job
  alive?" has no answer that distinguishes a dead process from a slow one.
- Reconciling at startup is when it matters: the moment the service comes back is
  the moment someone looks at the job table.

## Consequences

- **Always** write the heartbeat while a crawl runs. A job row that never
  heartbeats is indistinguishable from a job whose process died.
- `crawl_jobs.last_heartbeat_at` is now meaningful; queries that assumed it was
  always null should be revisited.
- Jobs left `Running` by a crash are now reported as `Crashed` with an
  explanatory `error_message`, instead of looking like a running crawl.
- The reconciliation runs on every boot. It touches only rows that have been
  quiet for more than five minutes, so a normal startup changes nothing.

## Addendum: "nothing to crawl" is not "a crawl that found nothing"

The scheduler ticks every 30 seconds and the freshness window is four hours,
so most ticks have no target to crawl. That case returned `Success(0, 0)`,
which the scheduler logged as `Crawl completed: 0 pages, 0 items` — a line
that reads like a broken crawler and, in a log full of them, hides the real
failures.

`RunResult` now has a `NothingDue` case for it: no target is active, or every
target is still inside its freshness window. No job row is opened for it,
exactly as before — the distinction is in what the scheduler says, not in what
the database gets.

- **Always** report "nothing to crawl" as `NothingDue`, never as a successful
  crawl with zero pages. A zero-page crawl and an idle scheduler are different
  facts.
