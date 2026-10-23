---
title: "The test asks the clock a different question"
date: 2026-11-08
tags: [testing, reliability, browser]
---

## Context

A CI run of an unrelated pull request failed on a test that had nothing to do with the
change: `BrowserPoolJanitorTest > the tick closes a browser that has been idle for too
long`.

The idle rule is measured against the wall clock, in two places:

- the pool records the moment of the last use — `state.markUsed(System.currentTimeMillis())`;
- the janitor's tick asks what time it is now — `idleSweeper.closeIdle(System.currentTimeMillis(), idleTimeoutMs)`.

and the rule is `now - lastUsed >= idleTimeoutMs`. The test sets the timeout to **one
millisecond** and sweeps immediately after the borrow.

On a machine that is not busy, the borrow and the sweep land in the same millisecond:
zero milliseconds have passed, the browser is not idle yet, nothing is closed, and the
test fails. Measured on this repository, in a tight loop of two thousand rounds,
**1966 rounds missed the line** — the test failed 98 % of the time. It had been
passing locally only because the machine was loaded, and it failed on the CI runner,
which is fast and idle.

So the test was not flaky. It asserted the speed of the machine.

## Idea

1. **Sleep in the test.** Rejected: the wait is a guess about the machine — long enough
   to be safe on a fast runner, short enough to keep the suite quick — and the test
   still measures the wall clock, so the guess becomes part of the test's contract.
2. **Change the rule so that the check is not clock-sensitive** — `>` instead of `>=`,
   or a comparison against the duration rather than the instant. Rejected: this changes
   production behaviour to suit a test, and the race survives it: after zero
   milliseconds the browser still has not been idle for a millisecond.
3. **Let the janitor ask the clock a question the test chooses.** Chosen.

## Decision

**The janitor asks the clock what time it is; a test asks it a different question.**

`BrowserPoolJanitor` reads the time through an internal seam, `clock`, exactly as
`AdsPowerPreflightRunner` reads the profile cache directory through `profileCacheDir`:
a seam, not a knob — the deployment does not set it. The test sets it to an hour
later, which is the idiom the pool's own lifecycle test already uses when it calls
`closeIdle(now = System.currentTimeMillis() + 3_600_000L, …)` directly.

The rule did not change. The production behaviour is the same on every machine; only
the question the test asks changed.

## Rationale

The browser module already answered this question correctly: `closeIdle` takes `now` as
a parameter, and the test passes the time it wants to ask about. The app-module janitor
hid the same question behind `System.currentTimeMillis()`, and the test inherited the
wall clock with it.

The seam puts the question where it can be asked. It is the same shape as the seam the
repository already uses twice — a value that only a test changes, documented as such
where it is declared.

Why not the sleep: a sleep is a guess, and a guess about a machine is exactly what
made this test fail in the first place. The seam makes the test's answer the same on a
laptop and on a runner.

## Consequences

- **Always** give a test that measures against the clock a way to choose what time it
  is. The rule stays real; the question becomes the test's.
- **Never** let a test's result depend on how fast the machine is. A test that fails on
  a fast runner and passes on a loaded one is measuring the runner.
- **Always** read a CI failure in a test unrelated to the change as a finding about the
  test. It cost a red run; the next one would have cost the same.
- **Never** change a production rule to make a test pass. The rule is the behaviour;
  the test is the question.
