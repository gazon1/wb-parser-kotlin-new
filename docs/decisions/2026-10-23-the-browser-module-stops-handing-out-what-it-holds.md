---
title: "The browser module stops handing out what it holds"
date: 2026-10-23
tags: [architecture, boundaries, browser]
---

## Context

Three things were wrong with the browser module's boundary, and they all had the
same shape: the module kept what it held and gave it away anyway.

**The AdsPower client was a tap.** `BrowserPool.adsPowerApi` was public, and three
consumers reached through the pool for it: the reconciler inside the module, the
egress probe in the crawler, and the pre-flight check in the application. The
reason was real — one process talks to one AdsPower runtime, and a second client
would resolve a different port and could disagree with the pool about whether
AdsPower is up. But the way it was solved was wrong: the client was *owned* by the
pool and *handed out* by it, so the crawler, which has nothing to do with profiles
and pages, learned to navigate a pool to reach a runtime.

**The reconciler held the whole pool.** `BrowserReconciler` needed three things
about a profile: is it ours, is it running, may it be stopped. It held the entire
`BrowserPool` to get them, and with it every decision the pool makes — rotation,
permits, sessions, the idle sweep, the exit sweep.

**The pool decided things that were not its business.** `BrowserPool` was 478 lines
and held four decisions: which profile to hand out and when; which browsers may be
closed for being idle; what happens to the browsers when the process is gone; and
whose browser a given profile's is. The first is the pool's. The other three are
not — they are policies about the machine, and the ADR "the big classes are split
by decision" had been saying so since the class was 300 lines long.

And one live bug sat on top of all of it: the reconciliation retried until every
profile had been closed *or reported as a failure*. A profile that AdsPower
**refused** to close was reported as a failure, so the retry never stopped — every
minute, the same question, the same answer, for the life of the process.

## Idea

- (1) A refusal and a failure are different facts, and they get different answers.
- (2) The AdsPower client is created once by the deployment and injected into
  everyone who needs it.
- (3) The reconciler holds a port with three methods, not the pool.
- (4) The idle rule and the exit rule are their own classes.

## Decision

**A refusal is permanent; a failure is not.** Two things can go wrong while
reconciling, and they mean opposite things:

- The runtime could not be reached, or could not answer for a profile. The
  question is still open, the reconciliation is not confirmed, and repeating it may
  answer it.
- The runtime answered and did not close the browser. The question is closed: the
  browser stays open, and no number of retries changes that.

`ReconcileOutcome` therefore carries two maps — `failures` (transient) and
`refused` (permanent) — and answers one question of its own: `isConfirmed`. The
decision lives in the browser module, where the meaning of "the runtime answered
no" is defined; the application asks the outcome whether it is confirmed and stops
its schedule when it is. A browser that is shutting down asynchronously is not a
refusal: the profile is asked once more whether it is still there, and a browser
that has gone needs no stop.

**The client is injected.** `AdsPowerClients.create()` makes the client;
`BrowserConfig` declares it as a bean and hands it to the pool, the pre-flight
check, the egress probe and the reconciliation. `BrowserPool.adsPowerApi` is gone
from the public API — the pool keeps its client and does not give it away. The
client travels into the pool inside the configuration it already reads, so the
pool cannot be given one client while its configuration names another.

The two things the crawler needs — the pool and the client — travel together as
`BrowserAccess`. They are one thing in practice: one process, one AdsPower runtime,
one client, and a holder makes "the *same* client" explicit instead of leaving it
to two constructors that happen to agree.

**The reconciler holds a port.** `BrowserLifecycle` is three members: the managed
profile ids, whether a profile is one of ours, and what to do with a leftover. The
pool implements it; the reconciler does not know the pool is behind it.

**The pool holds rules, not decisions.** `BrowserIdleSweeper` decides what "idle"
means and which idle browser may be closed; `BrowserShutdown` decides what happens
to the browsers when the process is gone, and it is the only decision with no
second chance. Both are the pool's collaborators and are published as their own
beans, so the application's janitor depends on the rules rather than on the pool.

## Rationale

A refusal treated as "not yet confirmed" is not a slow path — it is a loop that
never ends, and it hides the fact it should be reporting: a browser is open and
nobody closed it. Splitting the two makes the loop finite without making the
reconciliation weaker: the transient case, which is the one worth retrying, still
retries.

Injecting the client removes the tap without splitting the client in two. The
reason the pool handed it out was to keep *one* client in the process — and
injection keeps exactly that, while making the sharing explicit instead of
accidental.

The port makes the reconciler's need legible: three questions, three methods. The
reconciler no longer depends on the rotation, the permits or the sweep.

The split is by decision, as the ADR has been saying. The pool becomes smaller and
its remaining decision — which profile to hand out and when — is the one it is
actually for.

## Consequences

- **Never** treat a refusal as a reason to retry the same question.
- **Always** say what a retry could change: the answer is "nothing", so stop.
- **Always** create the AdsPower client once, in the deployment, and inject it.
- **Never** reach through a collaborator to what it holds.
- **MUST** split a class by the decisions it holds, not by its size.
- **Always** ask a collaborator for a port, not for the object behind it.
