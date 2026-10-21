---
title: "A schedule that does not run is a failure, and a CI failure should be about the code"
date: 2026-10-20
tags: [ci, reliability, schedule, observability, gate]
---

## Context

The nightly run reports its own failures: when the pipeline fails, the
`nightly-alert` job opens an issue. That covers the case where the run
happened and something broke.

It does not cover the case where the run never happened. GitHub disables
scheduled workflows in a repository with no activity for 60 days, delays
them in a busy hour, and skips a cron whose repository went quiet. A
schedule that has stopped is not a failing workflow: there is no failure to
report, there is no run, and the badge stays green because "the workflow did
not run" is not "the workflow failed". The repository quietly stops being
checked, and the guarantee the nightly was added for — "the tests run on
their own, so a week of drift is caught" — is gone without a word.

There is a second, smaller problem, and it was found the hard way. The
`lint` job of PR #69 failed with a Gradle resolution error: `kotlin-bom:2.4.0`
and three other artifacts "could not be found" on Maven Central. Every one of
them is on Maven Central — the same URL returns 200 from this machine. The
job had no Gradle cache, so six jobs started at once, pulled the whole
dependency graph from the same registry at the same moment, and one of them
lost the race. Re-running the job passed.

A CI failure that is about the network is a failure about nothing. It
interrupts a person for a reason that has nothing to do with the change
they are making, and it teaches them that red means "sometimes", which is
the same as teaching them that red means nothing.

## Idea

1. **Report the absence from the nightly itself.** Impossible: the nightly
   is the thing that is missing. The reporter has to be something else.
2. **A watchdog workflow that checks whether the nightly is still running.**
   It reads the nightly's own run history and complains when the last
   success is too old. Chosen.
3. **Accept that "the schedule did not run" is not a failure.** Rejected: the
   whole point of the nightly was to be checked without a human, and this
   is the one way it stops being checked.

## Decision

**A watchdog watches the nightly** (`.github/workflows/watchdog.yml`). Once a
week it asks the API when the nightly last *succeeded* — a scheduled run, not
a manual one, because a person pressing the button says nothing about
whether the schedule works — and opens an issue when that was more than 48
hours ago, or when the nightly has never succeeded at all. One open issue is
enough: a watchdog that opens a new issue every week about the same thing
teaches people to ignore the label, which is the same mistake the nightly
made with its own alert.

**The watchdog is checked by the gate.** `scripts/ci/check-ci-workflows.py`
fails when no workflow watches the nightly, and fails when a workflow watches
a workflow file that does not exist. A watchdog that watches a name nobody
publishes reports nothing — in exactly the way it was written to prevent —
and that failure is invisible unless something looks for it.

**The Gradle cache is on in every job that builds** (`cache: gradle` on
every `setup-java` step). One job warms the cache, the others read it, and
the six jobs no longer pull the same dependency graph from the same registry
at the same moment. The CI gets faster and stops failing about the network.

## Rationale

The two problems are the same problem seen from two sides: a guarantee that
reports nothing is not a guarantee. The nightly reports its failures; the
watchdog reports the nightly's silence. The gate reports the watchdog's
absence. Each layer covers the failure mode of the one below it, and the
gate — which runs on every change, locally and in CI — is the only one that
is checked continuously.

The forty-eight-hour window is twice the nightly's period. One missed night
is a delay; two is a schedule that is not working. A shorter window would
report GitHub's own queue delays as failures, and a watchdog that cries
wolf is a watchdog that is ignored.

The cache is not a performance tweak dressed up as a decision. The reason
for it is that a CI failure must be about the change: a job that fails
because Maven Central hiccuped tells the person reading it nothing about
their change, and the re-run that follows teaches the wrong lesson. The
cache removes the thundering herd, and with it most of the randomness.

## Consequences

- **Always** treat "the schedule did not run" as a failure. The watchdog
  reports it, and the gate checks that the watchdog is there.
- **Never** let a watchdog open a second issue about the same thing. One
  open issue is the whole design.
- **Always** cache Gradle in the CI. Six jobs pulling the same graph from
  the same registry at the same moment is a race, and the race is lost at
  random.
- **Never** count a manual run as proof that the schedule works. The
  question is about the schedule.
- The nightly now costs two workflows a week instead of one: the run
  itself, and the watchdog that checks it. The watchdog is a few seconds of
  API calls.
