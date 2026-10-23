---
title: "The reconciliation covers every profile AdsPower knows and is retried until it is confirmed"
date: 2026-10-23
tags: [browser, adspower, lifecycle, operations]
---

## Context

The crawler was killed — SIGKILL, an OOM, a fallen JVM — and never got to close its
browsers: the AdsPower runtime is a container of its own, and the browsers keep
running. The next run finds them and inherits their session.

The reconciliation that stops them had two holes, and both of them were ways of not
knowing:

1. **The scope was the current configuration.** It looked at the profiles the
   egress groups name. A profile that was taken out of the configuration leaves its
   browser behind too, and a reconciliation that only looks at the configuration
   cannot see it: the crawler does not even know the profile exists. The browser
   stays open for ever.
2. **It ran once.** The AdsPower runtime is a separate container, and the order in
   which the two come up is not the crawler's to decide. When the crawler starts
   first, the reconciliation is skipped — and the browsers of the previous run stay
   open for the whole run. "The reconciliation did not run" was silently read as
   "there was nothing to do".

The port already knew how to list every profile AdsPower has
(`AdsPowerApi.listProfileProxies()`), and the port had been given a sharper contract
in the same week: `checkActiveV2` answers `null` for exactly one thing — "the
profile is not running" — and raises for a Local API that could not be reached.

## Alternatives considered

1. **Keep the scope at the configured profiles and retry the startup.** Rejected: it
   fixes the second hole and leaves the first. A profile removed from the
   configuration is precisely the one nobody remembers, and its browser is precisely
   the one nobody closes.
2. **Ask the operator to close the leftovers by hand.** Rejected: the guarantee the
   crawler claims is "the browser is always closed", and a manual step is not that.
3. **Sweep AdsPower periodically for ever.** Rejected: a browser that appears later
   belongs to *this* run, and a periodic sweep would close the crawler's own work.
   The reconciliation is about the browsers of an *earlier* run, and that is a
   question with an answer, not a permanent state.
4. **Reconcile every profile AdsPower knows, and retry until it is confirmed
   (chosen).**

## Decision

The scope of the reconciliation is the union of the pool's own profiles and every
profile AdsPower lists. It stops the browsers that are running and do not belong to
this run.

A profile of the pool is stopped under the profile's permit: the pool knows whether
this process started the browser and whether it has borrowed the profile, and the
permit makes the check and the stop atomic against the crawl's own borrowing. A
profile the pool does not manage is stopped directly — nobody here will ever start
it, so there is nothing to race with.

The reconciliation is retried until it is confirmed. "Confirmed" means every profile
it knows about has either been closed or reported as a failure; after that there is
nothing left to look for, and the retries stop. The retry is a tick
(`crawler.spider.reconcile-browsers-interval-ms`), not a policy.

The ownership assumption is explicit: **the crawler owns the AdsPower runtime it
talks to.** In the deployment the runtime is a container of the crawler's own, so
every profile in it is the crawler's to close. A deployment that shares one runtime
between two crawlers turns the reconciliation off
(`crawler.spider.reconcile-browsers-on-start`).

## Rationale

The scope and the retry are the same decision seen from two sides: *know* and *ask
until you know*. The first version of the reconciliation asked a question it could
not answer ("is a browser of a profile we no longer have running?") and accepted
the answer it got (nothing to do). The second version asks a question it can answer
and repeats it until the answer arrives.

The retry stops at the confirmation rather than running for ever, because the two
directions are different. A browser of an earlier run is a leftover, and a leftover
is closed. A browser of this run is the work in progress, and closing it would be
the crawler stopping itself.

The pool's permit is what makes the "do not touch our own" rule safe rather than
hopeful: the check and the stop happen under the same permit the crawl borrows
under, so a profile that is in use is in use for the whole of the check, not only
for its first half.

## Consequences

- **Always** reconcile every profile AdsPower knows, not only the configured ones.
  A profile removed from the configuration is the one that leaks.
- **Always** retry the reconciliation until it is confirmed. A skipped reconciliation
  is a browser nobody looked for.
- **Never** let the reconciliation close a browser of this run. The permit, the
  `startedByPool` fact and the "has been borrowed" fact are the three answers to
  "is it ours".
- **Never** fail the startup because the reconciliation could not run. The AdsPower
  runtime may legitimately come up later; that is a fact to report, not a reason to
  refuse to start.
- **Never** share one AdsPower runtime between two crawlers with the reconciliation
  on. The second one would stop the first one's browsers.
