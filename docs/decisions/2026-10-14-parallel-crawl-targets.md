---
title: "Crawl targets run in parallel; the browser pool is what actually bounds it"
date: 2026-10-14
tags: [infrastructure, concurrency, browser, performance]
---

## Context

`CrawlRunner` looped over due targets sequentially, so a pool of five AdsPower Chrome profiles
was used as one. On a 132-target run the browser work was the whole wall clock: 113 s for
13 200 items, of which ~29 s was paying the anti-bot warm-up five times, one profile at a time.

Parallelising targets is not a one-line change. Three things had to be true first, and each was
found by reading the code rather than by the profiler.

### Pool exhaustion was reported as an anti-bot ban

`BrowserPool.borrowPage()` used `tryLock()` with no wait and threw
`BrowserPoolExhaustedException` the instant every profile was busy — which is exactly what
happens the moment more targets run at once than there are profiles. `BrowserDownloader` mapped
that exception to `NetworkError(isAntibot = true)`.

So the obvious implementation of "parallelise" would have produced a crawl that recorded a
false anti-bot ban for every request that lost the race, and the retry loop would have retried
them as though WB were refusing them. `borrowPage` now waits on the profile lock with a bounded
timeout, and pool exhaustion is no longer labelled `isAntibot` — it is a capacity condition on
our side, and saying otherwise puts a false cause in `crawl_errors`.

### Shared counters would have lost counts

`totalPages`, `totalItems` and `freshnessState` were plain `var`s mutated in the loop. Under
concurrency two targets finishing between a read and a write each lose a count, and `crawl_jobs`
under-reports a crawl that actually succeeded — the failure mode being invisible because the
numbers are simply small.

Totals are now folded from the values `runTargets` returns, on the coroutine that owns the job
row. `Freshness` is an immutable value, so the fold cannot race at all.

### Exposed registration was a global mutation on the write path

`upsertSavedItems` called `Database.connect(this)` on every write, with a comment claiming it
was idempotent. It is not: each call registers the datasource in Exposed's global `datasources`
map. Harmless with one thread, a data race with five. Registration is now done once per
DataSource behind a weak-keyed, double-checked cache (`exposedOnce()`).

## Decision

`CrawlRunner` runs up to `crawler.spider.target-concurrency` targets at a time behind a
semaphore, defaulting to the number of configured browser profiles. Results are aggregated
after `awaitAll`. `BrowserPool.borrowPage` waits for a slot with a timeout.

Failure semantics are deliberately unchanged: a target that returns a domain error is recorded
and the crawl continues; a target that *throws* still aborts the run, because
`coroutineScope` propagates it and closes the job as Failed. Silently shortening a crawl
because one catalog threw would be worse than failing loudly.

## Rationale

The measurement is the part worth keeping, because it did **not** deliver the projected 5x.

| run | wall clock | per page |
|---|---|---|
| sequential | 113 s | 0.86 s |
| 5 targets in parallel | **61.7 s** | **0.47 s** |

That is 1.83x, not 5x, and the reason is in the log rather than in the scheduler: every one of
the five profiles resolved to `ws://localhost:36931`, one browser, one anti-bot session wearing
five names. Only one AdsPower profile was actually open.

The cause was in the discovery fallback. `scanForCdpUrl()` returned the *first* Chrome it found
on any port, for every profile — so five profiles over five browsers would still have all
collapsed onto the lowest-numbered one. Discovery now enumerates every reachable DevTools
endpoint, assigns them to profiles by index, and **warns loudly when browsers < profiles**. The
collapse was previously silent, which is why a 1.83x result looked like a scheduling problem
rather than a configuration one.

Warm-up did parallelise as intended: all five profiles connect and navigate cold within ~6 s
instead of ~29 s.

## Consequences

- **Always** open as many AdsPower profiles as `target-concurrency`, or lower the setting. The
  pool queues beyond the browser count rather than failing, so an over-large value looks like it
  is working while delivering one session's worth of throughput.
- **Never** set `target-concurrency` from a guess about how many browsers are open. The pool
  warns when the two disagree; that warning is the diagnostic, not noise.
- **Always** keep `target-concurrency` low enough to leave connections for the save path. Each
  in-flight target holds a pooled connection while it waits for a browser slot.
- **Never** read a pool-exhaustion error as evidence that WB blocked us. It means no slot freed
  in time.
- SERIALIZABLE upserts under concurrency rely on the save-stage retry loop for `40001`. No
  serialization failures were observed in these runs, so the isolation level was left alone
  rather than changed on theory.
- `BrowserPool` and `BrowserDownloader` remain without unit tests — they need a real browser.
  The new concurrency logic was put where it *is* testable: `CrawlRunner`'s bound is verified by
  a peak-concurrency probe against a real PostgreSQL, including that `target-concurrency = 1`
  still measures exactly 1, so the probe cannot pass on a runner that ignored the setting.