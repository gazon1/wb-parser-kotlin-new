---
title: "The lock timeout bounds the wait, not the work"
date: 2026-10-19
tags: [concurrency, runtime, correctness]
---

## Context

`PostgresAdvisoryLock.withLock` looked like this:

```kotlin
withTimeout(lockTimeoutMs) {
    takeLock()
    block()
}
```

The timeout covered the *whole* call — the wait for the lock and the work done
under it. With the default timeout of ten minutes, any crawl that ran longer
than ten minutes was cancelled mid-flight, and the cancellation was reported as
`LockUnavailable("Lock acquisition timed out")`.

The message was the only symptom, and it pointed at the wrong thing: the lock
had been taken instantly. The timeout fired on the crawl that followed it. So a
crawler whose runtime is bounded by its lock-wait timeout is a crawler that stops
itself halfway through, and it reports "another instance is running" for it.

Nothing caught it, because the timeout is ten minutes by default and a crawl
usually finishes well inside that — the failure only appears on a slow day, in
production, with no test running longer than the timeout.

The configuration the timeout comes from documents the opposite contract:
`crawler.lock.timeout-minutes` is documented as "0 means no timeout (wait
forever)" — a statement about *waiting for the lock*, not about how long a crawl
may take.

## Idea

1. **Fail fast when the lock is busy.** The instance gives up immediately and
   reports "another instance is running". Simple, but it contradicts the
   documented contract of the timeout, and it turns every scheduled tick that
   overlaps a running crawl into a skipped run.
2. **Wait for the lock, bounded by the timeout; the work is never bounded.**
   The timeout becomes what the configuration says it is. Chosen.
3. **Drop the timeout entirely.** Then the wait is unbounded and the
   configuration setting disappears. Rejected: the setting exists, and a crawl
   that waits forever for a lock held by a dead instance is worse than one that
   gives up with a clear reason.

## Decision

`withLock` takes the lock first, and runs the block outside any timeout:

- The wait for the lock is bounded by `lockTimeoutMs`. A timeout of zero waits
  forever, which is what the configuration documents.
- The block runs to completion, however long it takes. A crawl is not cancelled
  by the lock-wait budget.
- The wait is a poll: `pg_try_advisory_lock` answers immediately, so the
  instance asks again every 500 ms until it gets the lock or the timeout runs
  out.
- The lock is taken and released on the *same* connection — a PostgreSQL
  advisory lock belongs to the session, so it cannot be taken on one connection
  and released on another.
- The reason is carried to the caller: `RunResult.AlreadyRunning` carries the
  message, and `ScheduledCrawler` logs it. Before, "the lock was busy" and "we
  gave up waiting" looked identical from the outside.

## Rationale

The timeout and the work are two different things, and one config value was
being used for both. The lock is the mutual-exclusion mechanism; the crawl is
the work the lock exists to protect. A timeout that kills the protected work is
not a timeout on the lock.

Polling rather than blocking is deliberate: `pg_try_advisory_lock` does not
block, and a blocking variant (`pg_advisory_lock`) would tie up a pooled
connection for the whole wait — one connection per waiting instance, for a
resource that is not doing anything.

## Consequences

- **Never** wrap the body of a lock-scoped block in the acquisition timeout. A
  timeout that bounds the wait must not bound the work.
- **Always** state which one a timeout bounds. "Lock acquisition timed out" and
  "the crawl took too long" are different facts, and the second one is not the
  lock's business.
- **Never** treat a timeout of zero as "no waiting". Zero means wait forever,
  and that is the documented contract of `crawler.lock.timeout-minutes`.
- **Always** take and release an advisory lock on the same connection.
- **Always** carry the reason a run was skipped to the operator: a scheduled
  crawl that quietly did nothing is indistinguishable from one that had nothing
  to do.
