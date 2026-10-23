---
title: "The measurement must describe this run — the union is not behind its parts"
date: 2026-10-23
tags: [gates, coverage, boundaries]
---

## Context

The coverage gate reads a kover report and compares a percentage with a floor. It
did not ask two questions that a measurement has to answer before it can be
believed:

- **Does the report describe this run?** A report is a file on disk. It can be
  from an earlier run, and nothing in the gate noticed.
- **Does the union describe the run that produced its parts?** The merged report
  is the union of the modules' reports. Gradle builds the union from the
  modules' artifacts, and the union's task can run *before* the part it is made
  of is regenerated.

The second one bit for real. A run of `./check.sh` reported `browser: 90.4%,
floor is 91%` — while the browser module's own report, written by the same
build, said 92.1%. The union was showing less of a part than the part itself.
The number was not wrong about the browser module: it was wrong about the
*union*, and the gate had no way to tell the difference between "the browser
module is at 90.4%" and "the union never saw the browser module's latest run".

Two smaller holes sat next to it:

- The Playwright boundary was declared on one side only. `check-repo-rules.py`
  forbade the browser module from naming a database or a framework, and nothing
  forbade `wbparser` from naming Playwright — which is exactly how it got there
  the first time, and why the crawler needed `implementation(libs.playwright)`.
- The rule "a double of our own type is a `Fake*`" lived only in
  `CODING_STANDARDS.md`, where it is read by a reviewer who has to remember it.

## Idea

- (1) Compare the union with its parts, and treat a union that is behind as a
  failure with the same meaning as a missing report: there is no measurement.
- (2) Bound the gap between the report and the test run it describes, rather
  than the age of the report.
- (3) Check the boundary from both sides: the browser module names nothing but
  a browser, and the rest of the repository names no Playwright at all.
- (4) Mechanise the naming half of the `Fake*` rule — but only for doubles of
  types this repository declares.

## Decision

We present the union as the union and check it as such.

**A union covers at least as much as its parts.** The merged report holds every
module's classes and the coverage every suite produced; a module's own suites
are one of the contributors. So the union of *this* run covers at least as many
lines of a module's packages as that module's own report does. A union that
covers fewer is not the union of this run — it was built before that part was
regenerated, and it is missing coverage that exists. `scripts/check-coverage.py`
compares the two and fails with exit code 2, the same code a missing report
gets: the answer is "no measurement", not "not enough coverage".

A part older than the union is not compared. A part built from an older run may
cover more lines than the union of the current one — coverage goes down when
code is added without tests — and comparing against it would report a loss that
never happened.

**`--max-age` bounds a gap, not an age.** The report is the coverage of a test
run, so it cannot be behind that run by much: the bound is the distance between
the report and the newest JUnit XML. A report that is a week old is honest when
the tests it describes are also a week old. What makes a report stale is the
code moving under it, not the passage of time — and a gate that treated age as
staleness would fire on every UP-TO-DATE report, which is every report of a
repository that has not changed since it was written.

**The same correction applies to the test-count gate, and it was needed there
first.** `check-test-runs.py` had a six-hour age window, and it fired on a
repository that had not changed in seven hours: the test tasks were UP-TO-DATE,
Gradle did not rewrite the results, and the gate declared a stale artifact that
was in fact the truth about the code. The age bound is now **off by default**,
and CI — where the workspace may be reused and a task that never ran leaves
yesterday's XML behind — passes it explicitly. Everywhere else the evidence
that the results match the code is Gradle's own up-to-date check, which compares
content hashes rather than clocks.

The two gates now disagree in one direction and agree in the other: the count
gate has no age bound by default and the coverage gate bounds a *gap*. The
coverage gate keeps a bound because its subject is a report that a build step
regenerates independently of the tests, and a report that is hours behind a
test run that just happened is missing that run.

**The build asks for the tests and the report together.** `check.sh` and the CI
coverage job both run `test koverXmlReport` rather than `koverXmlReport`. The
report and the run it describes become one thing instead of two tasks that
happen to be in the same build.

**The boundary is checked from both sides.** `FORBIDDEN_IN_BROWSER` said the
browser module knows about a browser and nothing else; `FORBIDDEN_OUTSIDE_BROWSER`
says the rest of the repository does not know about Playwright. The browser
module's own adapter may name it — that is what an adapter is.

**The naming rule is checked where it can be decided.** A double of a type this
repository declares, named `Mock*`, is a finding. Spring's `MockHttpServletRequest`
is not ours, and the rule stays silent about it.

**The gaps have a command.** `scripts/coverage-gaps.py` answers "who is not
covered" from the merged report, over the same package filter the gate
measures. Nine ad-hoc scripts answered that question in one session before the
answer lived in the repository.

## Rationale

The invariant is a property of a correct state, not a heuristic about it: a
union of this run *is* at least as covered as its parts. That makes the check
sound — it cannot fire on a healthy repository — and it is exactly the shape of
the failure that had no detection.

The gap beats the age because the age has a false positive on every unchanged
repository, and a gate that fires on an unchanged repository is a gate people
learn to skip.

Both sides of the Playwright boundary, because a boundary declared from one side
is a boundary that only one mistake can cross. The leak happened in the
unchecked direction.

The `Fake*` rule only where it can be decided, because a rule that fires on
Spring's mocks is a rule that gets disabled.

## Consequences

- **Always** ask a measurement whether it describes this run before comparing it
  with a floor.
- **Never** treat the age of a report or of a result as staleness; treat its
  distance from the run it describes, or leave it to Gradle.
- **Never** bound the age of test results outside a workspace that may be
  reused.
- **Always** compare the union with its parts.
- **Never** compare against a part that is older than the union.
- **Always** declare a boundary from both sides, or say which side is checked.
- **Always** run the tests and the coverage report in one invocation.
- **MUST** answer "what is not covered" with `scripts/coverage-gaps.py`, not
  with a fresh script.
