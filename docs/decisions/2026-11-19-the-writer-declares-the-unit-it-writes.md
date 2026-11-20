---
title: "the writer declares the unit it writes, and the database refuses a write that does not"
date: 2026-11-19
tags: [database, crawler, migration]
---

## Context

`V17` changed the meaning of the cashback columns: `cashback_percent` holds the percentage WB
sends, and `cashback` holds the money that follows it. The old writer wrote the other unit —
it took the percentage for kopecks and stored it as rubles, so a product with a 3 % cashback
was stored as 0.03 ₽ and is read by the new column as 0.03 %.

Nothing stopped that writer. The rollout said the old crawler must not run after `V17`, and
the runbook said a restart of the old release must have its worker off — but both are rules an
operator has to remember, and the failure they prevent is silent: the rows are written, the
crawl succeeds, and the catalog answers with cashbacks that are wrong by a factor of a hundred.
The configuration did not enforce anything; `crawler.worker.enabled` defaults to `true` in the
worker profile, so a restart that forgot `CRAWLER_WORKER_ENABLED=false` writes the old unit.

## Idea

- (a) A schema-version check before the worker starts. — It protects the *new* release from a
  database it does not understand. The hole is the other way round: the release that writes the
  old unit is the one that has to be stopped, and it carries no such check.
- (b) A deployment-level prohibition on restarting the old worker. — It is the same rule in
  another place: still something to remember.
- (c) The database refuses a write that does not declare the unit it writes in.

## Decision

The writer declares the unit in the session it writes in, and the database refuses a write
that does not.

- The declaration is a session setting: `SET LOCAL crawler.cashback_unit = 'percent'`. It is
  part of the write path (`upsertSavedItems`), so it travels with the writer: the release
  that writes the old unit has no such line and therefore cannot write at all.
- `V19` installs the trigger that enforces it: a row written by a session that has not made
  the declaration is refused, with an error that names the declaration. The trigger is on
  INSERT and on UPDATE, because the writer upserts — a row that is already there is updated,
  and that is the same corruption one row at a time.
- The seed declares it too, in the file it applies: the seed writes into `scraped_items` the
  way the crawler does.

## Rationale

The declaration is a promise the writer makes, and the writer is the only one that knows which
unit it is writing. The alternatives cannot make it: the old release cannot be taught to
check its own schema version, and a prohibition that lives in a document is not a prohibition.

The trigger deliberately does **not** look at the values. It could ask whether the numbers look
like a percentage — an amount below a ruble, say — but that question has no right answer: a
product that costs 10 ₽ with a 1 % cashback is 0.10 ₽ of money, and a guard that refused it
would refuse a correct row. A value-based guard would also be wrong in the other direction: the
old writer's own amounts are not always below a ruble either, once the price is high enough.

The declaration is a session setting rather than a column because a column says what a row
holds, not what the writer meant. The writer that has to be stopped is the one that never
heard of the column: its INSERT does not mention it, so a column with a default takes the
default, and a column without one refuses every write — the new one's included.

## Consequences

- **Never** write into `scraped_items` without declaring the unit the write is in. A write
  path that does not declare it is refused by the database, at the first row.
- **Always** declare the unit in the session the write happens in — `SET LOCAL` inside the
  write transaction, so the declaration cannot outlive it.
- The old crawler cannot write after `V19` is applied: its writes are refused, loudly, at the
  first row. The rule "the old crawler does not run after `V17`" is now enforced by the
  database instead of by the operator's memory, and a restart of the old release with the
  worker on fails instead of corrupting the catalog.
- `V19` is applied in the same phase as `V17` and `V18`, while no writer runs
  (`docs/operations/catalog-index-rollout.md`): the one-shot instance applies them one after
  another, so there is no window in which the old writer can write into the converted column.
- The declaration is not a proof: a writer that declares the unit and then writes the other
  one is not caught. That is a bug in the writer, not a mismatch of releases.
- After `V19` there is no rollback of the *writer*: the old release cannot be started with its
  worker on, because the database refuses its rows. A rollback can still give back the old
  *reader* — the old release with the worker off and Flyway off, serving a catalog it does not
  fill. The rollback drill (`scripts/rollback-drill.sh`) therefore rehearses the reader, and its
  seed declares the unit the way the writer does.
