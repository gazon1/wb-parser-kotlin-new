---
title: "The measurement the gate reads is the measurement that is published"
date: 2026-11-07
tags: [ci, coverage, measurement]
---

## Context

The coverage gate does not read a module's report. It reads the **merged** report — the
root project's, which is the union of every module's classes and of the coverage every
suite produced, including the integration module's.

The CI artifact published the three *module* reports. A module's report holds only that
module's classes and only the coverage that module's own suites produced, so the
artifact was the parts of that union without the union — and the parts say the opposite
of the whole.

The app's own report showed the catalog API, its routes and its security chain as
uncovered, because the suites that exercise them live in the integration module and the
app's report cannot see that. The merged report put the same classes at 57–100 %.

That difference is not academic. It was read the wrong way once already, in this
repository: the largest uncovered cluster looked like the catalog — 360 lines across
twenty-two files — and the plan that came out of it was about splitting the catalog by
decision. The catalog was already split, and it is covered. The real hole was the
crawler's core: 33 lines, in two classes.

## Idea

1. **Publish only the merged report.** Rejected: a module's own report is what a
   module's row is measured on, and the artifact is also where somebody looks for the
   module's numbers. Removing the parts would remove evidence.
2. **Keep the parts and describe the difference in the artifact's name.** Rejected: the
   difference is not a name, it is a file. A reader who downloads the artifact and reads
   a part will read a part; no naming makes that safe.
3. **Publish the merged report next to the parts.** Chosen.

## Decision

**The artifact carries the report the gate reads.**

`kover-report` now holds `build/reports/kover/report.xml` — the merged report — beside
the three module reports. Whoever asks "what did the gate see?" downloads the artifact
and finds it.

The gate itself does not change: it already measured the merged report, and the merged
report is already built in the same invocation as the tests, so the number and the run
it describes are one thing. What changes is that the evidence for the number is now
published with it.

## Rationale

A measurement that cannot be reproduced from what the pipeline published is a
measurement somebody has to take on faith. The gate was right and the artifact was
wrong, and nothing in the run said so — the artifact's name (`kover-report`) does not
say which report it is.

The merged report is also the only one of the four that is about the repository. The
three module reports are about a module: its classes, and the coverage its own suites
produced. Both are true things, and one of them is the answer to the question the gate
answers.

## Consequences

- **Always** publish the report the gate measures, next to the ones it is measured
  against. A gate whose evidence is not published cannot be checked by anybody else.
- **Never** read a module's own report as the repository's coverage. It is a part of
  the union, and the part is blind to every other module's suites.
- **Never** let a measurement be taken from an artifact that contradicts the gate
  without saying which one is which. The disagreement is the information: it usually
  means the artifact holds a part.
