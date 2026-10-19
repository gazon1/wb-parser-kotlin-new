---
title: "The coverage floor lives in one file, and the gate reads the report"
date: 2026-10-19
tags: [build, tests, gate, coverage]
---

## Context

The line-coverage floor existed in two places, and they disagreed:

- `wbparser/build.gradle.kts` carried `verify { rule { minBound(45) } }` — the
  build failed below 45%.
- `config/gates/coverage-floor.txt` said `44`, and pointed at
  `./scripts/raise-kover-floor.py` — a script that did not exist.

So the build enforced a number the repository did not record, and the file
recorded a number the build did not enforce. A floor in two places is not a
floor; it is two claims, and the one that runs is the one nobody reviews.

The application module had no tests at all: 769 lines of wiring — the admin
filter, the configuration binding, the health probe, the scheduler — measured
by nothing. The startup gate in the `tests` module boots the real application
and asserts that it starts, but "it starts" is not "it works": nothing noticed
that the admin filter accepts every key, or that a lock timeout is read in
minutes where the code wants milliseconds.

And the sibling script had the same shape of defect. `scripts/check-test-runs.py
--update` rewrote the test-count floor from whatever results happened to be on
disk: run it after a partial run and it records the partial count as the new
floor, and the next full run passes against a floor that was never earned.

## Idea

1. **Keep the floor in the build script and fix the file.** One edit, and the
   two places can drift again the next time somebody edits only one of them.
2. **Make the file the single source of truth, and let a script enforce it.**
   The build script produces the report; the gate reads the report and compares
   it with the file. Chosen.
3. **Keep the floor in the file and enforce it with the coverage plugin's own
   `minBound`.** The plugin reads the value from the DSL, so the file would
   still have to be copied into the build — the same duplication in a different
   shape.

## Decision

`config/gates/coverage-floor.txt` is the floor, one `<module> <percent>` row
per module, and `scripts/check-coverage.py` is the gate. The build scripts only
produce the report; the plugin's `verify { rule { … } }` block is gone.

The gate reads the kover XML report and answers three different things with
three exit codes:

- **0** — every module met or beat its floor.
- **1** — a module is below its floor.
- **2** — the gate could not measure: the report is missing or unreadable, or
  the floor file is absent. "No report" is not "no coverage" — it is "the
  coverage task did not run", which is the same shape of problem as a test task
  that was UP-TO-DATE and never executed.

`scripts/raise-kover-floor.py` raises the floor to the measured coverage. It
refuses to lower it without `--allow-drop '<reason>'`: the floor is a ratchet,
and a drop is a deliberate act, not an accident of a refactor.

The application module joins the record. It gets unit tests — the admin filter,
the configuration binding, the health probe — and an entry in the floor file,
and `scripts/check-test-runs.py` learns the `app` source set, so a suite that
stops executing is noticed there too.

The same fix goes into the sibling script: `check-test-runs.py --update` now
refuses to rewrite the floor file when any module's results are missing, and it
records a module that has gained its first tests.

## Rationale

The gate has to read a measurement, not assert one. A `minBound` inside the
build is the build asserting a number it also wrote; a script comparing a
committed floor with a report the build produced is a gate that can be wrong
about the *code* and right about the *number*, which is the only asymmetry that
tells you something.

Reading the report also means the gate knows the difference between "coverage
fell" and "coverage was not measured". Both are failures, but only the first is
a statement about the code, and conflating them is how a broken measurement
looks like a passing one.

## Consequences

- **Always** keep the coverage floor in `config/gates/coverage-floor.txt` and
  let `scripts/check-coverage.py` enforce it. A second copy of the number in a
  build script is a second claim about it.
- **Always** raise the floor in the same commit that raises the coverage, with
  `./scripts/raise-kover-floor.py`.
- **Never** lower a coverage floor without `--allow-drop '<reason>'`, and never
  without a reason in the commit that does it.
- **Never** treat a missing coverage report as zero coverage. It is an absent
  measurement, and the gate says so with a different exit code.
- **Always** give every module with tests a floor in `config/gates/coverage-floor.txt`
  and a row in `config/gates/test-runs-floor.txt`. A module nobody measures is
  a module nobody watches.
- **Never** rewrite a floor file from a partial run. If the results are not
  complete, the floor stays as it was.
- The coverage gate runs in `./check.sh` (step 7) and in ci.yml's `coverage`
  job, symmetrically — Part E of `scripts/check-gate-wiring.py` compares the two
  surfaces.
