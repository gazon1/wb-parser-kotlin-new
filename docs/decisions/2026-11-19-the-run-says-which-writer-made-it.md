---
title: "the run says which writer made it, so the cutover gate does not depend on a moment in the operator's head"
date: 2026-11-19
tags: [database, crawler, migration, operations]
---

## Context

The cutover gate asks one question: "has the **new** crawler been around the circle?". It used to
answer it from `crawl_target_runs` — the crawler's own record of what it did — together with a
timestamp the operator supplied: the moment the new crawler was started.

That timestamp was the only thing in the check that was not in the database, and it was the whole
discriminator. The runs of the old crawler and the runs of the new crawler are the same rows in
the same table: nothing in a run says which release wrote it. So a moment typed too early lets
the old crawler's runs satisfy the check, and the API is cut over to a catalog the new crawler
never filled — the mistake a visitor sees.

## Idea

- (a) Ask the operator to type a correct moment. — The moment is not the thing that is wrong;
  the check has no way to tell the two crawlers apart, so the operator's answer is the only
  evidence. A moment typed early is enough to pass the gate wrongly.
- (b) Record the release that made the run. — It identifies the writer, but it is more than the
  catalog depends on, and a release id that the operator has to keep in step with the image is
  another fact to maintain.
- (c) Record the unit the writer wrote in. — The unit is the fact the catalog depends on: the
  release that writes percentages and the release that does not are exactly the two the cutover
  has to tell apart.

## Decision

A run carries the unit its writer wrote in (`crawl_target_runs.writer_unit`), and the cutover gate
is a question about the runs, not about a timestamp.

- The writer declares the unit before it writes (`SET LOCAL crawler.cashback_unit = ...`) and
  records the same unit in the run. Both come from one constant, so the declaration and the
  record cannot disagree.
- The release that writes the old unit has never heard of the declaration (`V19`), so its runs
  leave the column empty. It says nothing about its writer — which is what it must say.
- The gate is the `NOT EXISTS` query of the rollout runbook: a target is covered when it has a
  successful run whose `writer_unit` is the unit the catalog holds. The timestamp stays in the
  check, but only as the **freshness** bound: a run older than the moment the new crawler started
  does not count. A moment typed too early can no longer let an old run satisfy the check.
- `crawl_targets.last_crawled_at` is not part of the gate. It moves for a target that *any*
  crawler read, so it cannot tell the new crawler from the old one; it is kept as the display of
  how far the crawler has come.

## Rationale

The gate's job is to be answered by the database. Everything else it needs — whether the run
succeeded, which target it was about, when it finished — is already in the row. The one fact
missing was which writer produced it, and that fact is exactly the unit the writer declares to
be allowed to write at all.

The unit beats a release id here for a plain reason: the writer already has to declare it, so
the record costs nothing to keep true. A release id would be a second fact about the writer,
maintained next to the image, and the two would drift.

The column is empty for the old writer rather than filled with a sentinel. A sentinel ("unknown")
would be a claim the run cannot support, and the gate would have to know the sentinel as well as
the unit — two values where one does.

## Consequences

- **Always** record the unit in the run. The writer declares it before the write, and the run
  carries the same constant.
- **Never** let the cutover gate rest on a timestamp alone. The timestamp bounds the freshness;
  the run says whose run it is.
- **Never** use `last_crawled_at` as the gate. It moves for a target any crawler read.
- The gate is one SQL query, and it lives with the procedure that uses it
  (`docs/operations/catalog-index-rollout.md`). A gate that exists only as prose is not a gate.
- `V20` is applied in the same phase as `V17`, `V18` and `V19`, while no writer runs: the column
  has to exist before the new writer records into it.
- The column says which **unit** the writer wrote in, not which release, version or build it
  came from. A release that wrote percentages is indistinguishable from another release that
  wrote percentages — which is the distinction the cutover does not need.
