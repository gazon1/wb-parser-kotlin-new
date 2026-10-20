---
title: "The registry is total by construction, and a side nobody emits is not a side"
date: 2026-10-20
tags: [pipeline, architecture, domain, types]
---

## Context

`SideInterpreterRegistry` held three nullable interpreters — `jobEvent`,
`acquireAdvisoryLock` and `drop` — and answered a side nobody interpreted with
`?: throw IllegalStateException("No interpreter for Side.…")`.

The shape said two contradictory things at once. The parameter said "this effect
may be absent"; the throw said "this effect must never be absent". A caller
that read the constructor saw an optional dependency, and a caller that
forgot to pass one found out in production, in the middle of a crawl, at the
moment the effect arrived. The compiler could have said it at the call site and
did not.

Two of those three interpreters were for effects that no stage has ever
emitted. `Side.JobEvent` was written for "job state transitions", and the
interpreter that served it only logged — the job rows are written by
`CrawlRunner` through `openJob`/`closeJob`, not through the pipeline.
`Side.AcquireAdvisoryLock` existed so the registry had one more slot to fill;
the lock is taken by `CrawlRunner` before the pipeline runs, and the
interpreter that served it only wrote a trace line. Both survived a previous
refactor as the kind of scaffolding that looks deliberate and is not:
`Side.SaveBatch` and `Side.ScheduleRetry` had already been removed for exactly
this reason, and their removal left the two behind.

The ADR that governs the registry's defaults — "anti-bot refusals are
measured" — records the reason `Side.Log` and `Side.Metric` have no-op
defaults: the domain emits both on its own, so a registry that forgot to
register a logger would fail the moment something went wrong, which is the
worst moment to fail. That reasoning is sound, and it applies to a log and a
counter. It does not apply to a dropped item: a drop is a fact about the
data, and the place that learns about it decides where it goes.

## Idea

1. **Keep the nullable interpreters and throw a better message.** Rejected: it
   keeps the failure in production. A message that explains the problem does
   not make the problem happen at the right time.
2. **Make every interpreter non-null and keep the `when` exhaustive.** Chosen.
   The registry then holds a handler for every side, and a side without a
   handler cannot be written down.
3. **Delete the two effects nobody emits.** Chosen, together with (2): with
   them gone, the registry has exactly three sides and three interpreters, and
   every one of them is a real effect of the crawler.

## Decision

**`SideInterpreterRegistry` is total by construction.** Every interpreter
parameter is non-null. `Side.Drop` has no default at all — the caller states
where a dropped item goes, and `NoOpDropInterpreter` is how it says "not
here". `Side.Log` and `Side.Metric` keep their no-op defaults, for the reason
the anti-bot ADR gives.

**The dispatch is an exhaustive `when` over the sealed `Side`.** Adding a
variant to `Side` breaks the build until the registry learns to interpret it.
The compiler is now the thing that enforces the rule the old ADR could only
state as a warning: "never emit a `Side` from a stage without a corresponding
interpreter registered".

**`Side.JobEvent` and `Side.AcquireAdvisoryLock` are gone**, together with
`JobOp`, `JobEventInterpreter` and `NoOpAdvisoryLockInterpreter`. No stage
emitted them, and the effect of a dropped row is a row that is never written —
the interpreter that served `Side.JobEvent` only logged what `CrawlRunner`
had already written by another road.

**The guarantee is stated as a test.** `SideInterpreterRegistryTest` reads the
registry's constructor by reflection and asserts that its parameters cover
every sealed subclass of `Side` and that none of them is nullable. The
compiler already enforces this; the test says so out loud, so that a nullable
parameter cannot come back unnoticed.

## Rationale

The registry is a lookup table whose keys are a closed set. A closed set with
optional values is a table that can miss, and a table that can miss is a
runtime failure with a stack trace instead of a compile error with a caret.
Kotlin's sealed hierarchies and exhaustive `when` already give the compiler
the ability to check this; the nullable parameters were the only thing
standing in the way.

The distinction between the defaults that stayed and the one that went is the
distinction the anti-bot ADR already draws, applied consistently. A log and a
counter are diagnostics: the crawl is still correct without them, so the
registry answers them rather than making every caller decide. A drop is a
fact about the data: a caller that does not say where it goes has chosen to
lose it, and making that choice explicit at the constructor is the whole
point. `NoOpDropInterpreter` keeps the "not here" answer available — it just
has to be said.

Deleting the two dead effects is the same decision seen from the other side.
A side that nobody emits is a promise the type system makes to its readers:
"there is an effect of this kind, and it is handled". `Side.JobEvent` made
that promise and broke it — the only thing that ever happened was a log line
about a row that another component had already written. `Side.AcquireAdvisoryLock`
made it and broke it the same way. Every registry that had to name an
interpreter for them was paying for a promise nobody kept.

## Consequences

- **Always** give every `Side` variant a non-null interpreter. The registry
  refuses to be constructed without one, and the `when` refuses to compile
  without a branch.
- **Never** make a side's interpreter nullable to avoid touching a call site.
  The call site is where the decision belongs.
- **Never** add a `Side` variant that no stage emits. If the effect is not
  real, it is not a side; if it is real, it needs a stage and an interpreter.
- **Always** keep the no-op defaults for `Side.Log` and `Side.Metric`. A
  missing logger must not take the crawl down.
- The `Side` hierarchy now has three variants. A fourth one is a new effect
  of the crawler, and it arrives with a stage, an interpreter and a test.
