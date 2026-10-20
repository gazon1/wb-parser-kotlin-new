---
title: "The catalog's aggregates are cached in the process, and that shapes the topology"
date: 2026-10-20
tags: [catalog, api, cache, operations, deployment]
---

## Context

The catalog API serves two aggregates that do not scale for free: `/targets` groups
every observation per crawl target, and `/stats` counts the whole table. Both are full
passes over `scraped_items`, and the measurement behind the API's design says what
that costs — a `COUNT(*)` over the table is 27 ms of a 125 ms request at two hundred
thousand observations, and it grows with the table rather than with the page.

So both aggregates are cached, for ten minutes, in the crawler process.

That was the obvious decision at the time, and it is still the right one for a single
instance. What was *not* written down is what it means for the next one.

The cache is in the **process**. Not in Redis, not in the database, not in a shared
cache. The crawler holds it in a field, and it is gone when the process is gone.

That has three consequences, and each of them bites in a different place:

1. **Two instances answer differently.** An instance that started a minute ago has
   no cached aggregate and computes one; an instance that has been up for ten minutes
   answers with the value from before its last restart. The two answers are about
   the same data and they are not the same number. `/targets` reports an item count
   per target, and `/stats` reports the size of the catalog — both of which change
   as the crawler writes.
2. **A restart empties the cache and the first request pays for it.** The first
   caller after a deployment waits for a full pass over the table instead of a
   lookup. At the measured rate that is tens of milliseconds, not a problem — but it
   is a *cold* request, and it lands on whoever happened to be first.
3. **A scale-out cannot be a horizontal one.** Two instances behind a load balancer
   mean two caches, two sets of answers and two sets of memory. The catalog's
   aggregate values would depend on which instance answered.

The third consequence is the one that matters for the phase-5 topology work, and it
was the one that was missing from the documents.

## Decision

The aggregates are cached in the crawler process, and the documents say so — in the
ADR, in the operations runbook, and in the readiness runbook that the phase-5 work
will read.

The cache stays in the process for as long as the crawler is one instance. The day
there is more than one, the aggregates move to a shared cache — and the deployment
topology changes with them.

### What is recorded, and where

- **This ADR** records the property and its consequences: per-instance answers, a
  cold first request after a restart, and a limit of one instance.
- **`docs/operations/catalog-observability.md`** records the cache's TTL, its size
  bound, and what an operator sees when a value is stale.
- **`docs/operations/catalog-readiness-runbook.md`** records the phase-0 steps, and
  names this file as the thing to read before designing the phase-5 topology.

### What is explicitly *not* decided here

The phase-5 topology is not decided here. This decision constrains it — one instance
is the arrangement this cache supports — and the work of choosing the arrangement
belongs to that phase, with this file as its input.

## Consequences

- **Never** run two crawler instances behind one address while the aggregates are
  cached in the process. The two would answer with different numbers for the same
  question, and neither would be wrong.
- **Always** treat a value that is older than ten minutes as "as of ten minutes
  ago", not as "now". The storefront shows the count it was given; it does not
  recompute it, and it cannot.
- The API answers a request that arrives right after a restart with a value computed
  from the database at that moment, and the next one with a value computed before the
  restart. Both are correct answers to "what did the database say".
- When the topology grows to more than one instance, the aggregates move to a shared
  cache in the same change that adds the second instance. Doing them separately
  would leave a window in which the second instance answers from its own empty cache
  — the exact inconsistency this decision exists to prevent.
- The `totalCount` of a page of `/items` is **not** cached, and the reason is
  separate and worth stating here: it is 27 ms of a 125 ms request, and caching it
  would make the number the storefront shows "N products" wrong by up to ten
  minutes. A stale aggregate nobody looks at is cheap; a stale count on a page is a
  lie the storefront tells.
