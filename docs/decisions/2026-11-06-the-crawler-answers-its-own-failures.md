---
title: "The crawler answers its own failures"
date: 2026-11-06
tags: [coverage, testing, domain]
---

## Context

The merged coverage report — the one the gate measures, and the one that includes the
integration module's suites — showed the crawler's own failure paths as the largest
hole left: 24 uncovered lines in `PipelinePageProcessor` and 9 in `CrawlRunner`. The
catalog, which looked like the biggest hole from a module report, was covered: the
integration module exercises it against a real PostgreSQL, and a module's own report
cannot see that.

Two things were wrong with the hole.

The first was the hole itself. The paths where a stage *fails*, asks for a retry it
cannot have, asks to continue, or throws were never exercised. The save stage is the
only stage that talks to the outside world, and a database that goes away mid-page
raises an exception rather than answering — so the path that turns an exception into a
retryable failure was the path nobody had ever run.

The second was worse than the hole: the failure was **invisible**. A filter stage that
failed was turned into a drop with the reason `Filtered` — the same reason a filter uses
when it decides to drop an item on purpose. The operator's log said "Item dropped
[Filtered]" and could not tell a rule at work from a fault. `Step.sides()` returns an
empty list for anything that is not `Done`, so the failure's own message was dropped on
the floor, and the two repairs — fix the rule, fix the stage — looked identical.

## Idea

1. **Cover the paths and change nothing.** The drop is counted either way, so the
   counters do not move. Rejected: the test would cement "a failed filter looks like a
   decided drop" as the contract, and the next reader would take it for the design.
2. **Give the failure its own drop reason** (`Dropped.StageFailed`), so the counter
   separates faults from rules. Rejected for now: the drop reason set is read by the
   operator's dashboards, and a new label is a permanent addition to a public metric
   for a fault that should be rare enough to be an alarm, not a share.
3. **Say what happened, next to the drop.** A log line with the stage, the item and the
   failure's own message; the drop stays a drop. Chosen.

## Decision

**A stage that cannot do what it was asked states the reason.**

`PipelinePageProcessor` now logs, for every filter and enrich answer that is not a
`Done`: the stage, the product, and what the stage said — its failure message when it
failed, and the fact that it asked for a retry or to continue when it did. The item is
still dropped, and the drop is still counted; the difference is that the log now says
which of the two happened.

**The failure paths are tested where they live.**

- The stage's own paths — filter and enrich answering `Fail`, `Retry` and `Cont`, the
  save stage throwing, the save stage asking to continue — are tested in
  `PipelinePageProcessorTest`, which drives the processor directly. The seam already
  existed; the paths were simply never run.
- The runner's paths are tested against a real PostgreSQL in `CrawlRunnerPostgresTest`:
  another session holding the advisory lock (the run says "another instance is
  running" and opens no job row), a cancelled crawl (the job row says `Cancelled`, not
  `Failed`), and a group that answered (the ledger learns when the address last
  answered).

**Two paths are written down, not closed.**

- The runner's `catch (t: Throwable)` — the one that closes the job as `Failed` when
  something the runner did not anticipate happens. Everything between the job row
  opening and the job row closing is protected: a stage failure becomes a
  `StageFailure`, a database error is caught and retried, a hook that throws is
  ignored. What is left is a database that fails *between* two of the runner's own
  writes. Closing it needs a fault injected into the database in the middle of a run,
  and the honest way to inject that fault is to stop the database — which would take
  the rest of the suite's container with it.
- The warning that says the ledger could not be written. It is now covered by a group
  id the ledger's column cannot hold: the crawl carries on and the run says so.

## Rationale

The rule the repository already follows — a caught error must reach a `StageFailure` or
a log — was broken in the one place where the error was *not* caught: the `when` over
the stage's answer. `Fail` was turned into `null`, and `null` into a drop, and the
message went with it. The change is three lines in each of two branches, and it turns
the drop from "the item was not saved" into "the item was not saved, and here is why".

`Step.sides()` returning an empty list for a non-`Done` is not the bug and was not
changed: the sides of a stage are its *effects*, and a failure has none. What the
failure has is a message, and the message belongs in a log.

Why not a drop reason: the drop's reason is a fact about the data ("this product is
out of stock", "this category is blacklisted"), and it is counted per reason. A stage
failure is a fact about the run, and the run already has a channel for facts about the
run: the log, and the error that ends the job.

Why the runner's paths are tested against PostgreSQL and not against a double: the
advisory lock belongs to a session, the job row is closed with an `UPDATE` that has a
`CHECK` constraint on its status, and the ledger is written with a `GREATEST` that
cannot be reproduced by a stand-in. A double would test the double.

## Consequences

- **Always** say why a stage's answer became a drop. A drop without a reason is
  indistinguishable from a rule, and the two are repaired differently.
- **Never** let a stage failure and a decided drop look the same to the operator.
- **Always** test the path where a stage throws. The stage that talks to the outside
  world throws instead of answering, and the exception has to become a failure the
  retry loop understands.
- **Always** test a runner's paths against the database it writes to, when the path is
  about what the database holds.
- **Never** call a drop of a failed stage a `Filtered` drop without saying that the
  stage failed — the reason set is read by the dashboards, and the message is read by
  the operator.
