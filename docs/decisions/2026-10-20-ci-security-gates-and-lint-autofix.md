---
title: "The repository's CI surface is CodeQL, Scorecard, a nightly run that opens an issue, and a lint auto-fix"
date: 2026-10-20
tags: [ci, security, tooling, gate, lint]
---

## Context

The repository's CI answered one question: does the code build, does the test
suite pass, are the linters quiet. Four things it did not answer:

1. **Is the code safe?** detekt is a style and complexity linter. It does not
   know what a tainted input is, and nothing in the pipeline looked for a
   command injection, a path traversal, or a secret in the source.
2. **Is the repository safe?** Nothing measured whether the workflows were
   pinned, whether the dependencies were known-vulnerable, whether there was a
   security policy, or whether the branch was protected. These are the checks
   an attacker reads first, and they are the checks a project forgets.
3. **Does anything run when nobody pushes?** Every job ran on a push or a
   pull request. The failures that only a week of drift produces — a
   dependency that moved, a migration that stopped applying, a base image
   that changed underneath the application — were found by whoever pushed
   next, days later, with no way of knowing what had changed in between.
4. **Does a lint finding cost a review round?** detekt and ktlint both know
   how to fix most of what they report. The corrections were available as
   flags on the command line, and nothing ran them: the author had to know
   the flag, remember it, and run it by hand.

The sibling project `gazon1/sing` had already solved this shape of problem —
its `.github/workflows/ci.yml` carries a scheduled run, a job that opens an
issue when the scheduled run fails, and a detekt auto-fix that commits to a
branch for review. The question this entry answers is not "should we do
that" but "what does doing that mean *here*", where the gates are a registry
with two callers and every gate has to be able to fail.

## Idea

1. **Add the workflows, copy the shape.** Fastest, and it brings the four
   answers above. Rejected as the whole answer: a workflow that is present
   and never runs is not an answer, and this repository's history contains
   exactly that — a gate that lived only in CI and one that lived only
   locally, each green and invisible to the other surface.
2. **Add the workflows and a gate that proves they are wired.** The
   workflows become part of the repository's guarantees, and a gate says
   what each of them must contain so that deleting one, or emptying it, is a
   finding rather than a quiet loss. Chosen.
3. **Make the auto-fix push to the branch being worked on.** Convenient for
   the author. Rejected: a bot that rewrites a human's branch breaks builds
   nobody can see, and the correction is not the author's intent — it is the
   linter's.

## Decision

Four CI surfaces, each in its own workflow, each with a stated job.

**CodeQL** (`.github/workflows/codeql.yml`) analyses two languages: `java-kotlin`
and `actions`. The Kotlin analysis reads the *compiled* code — for JVM
languages CodeQL analyses whatever the build produced — so the workflow
compiles every module's main and test sources before the analysis step. The
`actions` analysis reads the workflow files, because a workflow that
interpolates an attacker-controlled string into a shell command is a remote
code execution that no Kotlin query can see. The findings land in the
code-scanning tab; they are alerts, not a gate.

**Scorecard** (`.github/workflows/scorecard.yml`) measures the repository's
own security posture with the twenty OpenSSF checks. The results are
published to the Scorecard API (`publish_results: true`, which needs
`id-token: write` for the OIDC proof that this run produced this file) and
uploaded to code scanning as SARIF. The score is a number that moves; a
number nobody reads is the same as no number.

**The nightly run and its issue** (`.github/workflows/nightly.yml` and
`.github/workflows/ci.yml`) runs the whole existing pipeline on a `schedule`
and adds `workflow_dispatch`. The pipeline is defined once, in `ci.yml`, and
the nightly is an *entry point* that calls it — the same arrangement this
repository already uses for the gates (one registry, two callers) and for the
lint corrector (one script, two callers). A schedule that is one more trigger
inside the pipeline's own file is a schedule that is invisible as an entry
point: the question "what runs overnight, and who reads it" has no answer in
the tree. As a separate file, it does.

The one difference between the two entry points is an input:
`alert_on_failure`. The nightly passes it, a pull request does not. A new
`nightly-alert` job opens a GitHub issue when a run fails, naming the jobs
that failed and linking the run. The issue is the report — a red cron run is
unread, and the badge stays red until the next push, which is also the next
time anyone looks. The event name is part of the concurrency group, so a push
neither cancels the nightly run nor waits behind it.

The report is the run's own API, not a list written by hand: the alert job
asks the API which jobs failed and prints every job with its conclusion. A
hand-written list of the pipeline's jobs is a mirror, and a mirror goes
stale the moment a job is added — at which point the issue says "nothing
failed" while something did. The job's `needs:` list remains, because it
says *when* the report is made rather than what it says; and that list is
checked against the real job list by the gate below, so a stale wait list is
a finding rather than a quiet omission.

A scheduled run is a health check, not a release: it does not push an image
to the registry. The permission model of a called workflow is what makes that
true, and it is not the model one would guess. The caller's grant is a
*ceiling*, and GitHub checks it **statically**: a nested job that asks for
more than the caller grants is rejected when the call is made, whether that
job would have run or not. The first version of the wrapper granted only
what the nightly needed, and the pipeline was refused at startup because the
image job asks for `packages: write`. The grant is therefore the pipeline's
whole ceiling, and the narrowing happens inside the pipeline: every job
declares the permissions it needs, so the registry write reaches the image
job — which does not run on a schedule — and nothing else.

**The lint auto-fix** (`.github/workflows/lint-autofix.yml`) runs
`scripts/ci/lint-autofix.sh`, which applies detekt's `--auto-correct` and
ktlint's `ktlintFormat` to the tree. The corrections are *proposed*: they go
to `lint-autofix/<run-id>`, and from there they are opened as a pull request
(on a push to main) or announced on the pull request (on a pull request). The
lint job in `ci.yml` remains the gate — a violation no rule can correct
still fails it, and the auto-fix does not make that failure disappear.

The corrector is one script with two callers: the workflow and
`just lint::autofix`. It runs one Gradle invocation per module and one task
per invocation, because `--auto-correct` is an option of the `detekt` *task*
and a task option on the command line reaches only the last matching task of
the invocation — `./gradlew detekt --auto-correct` corrects one module and
silently skips the rest, which is indistinguishable from a flag that does
nothing unless the files are read afterwards. The modules are discovered
from `settings.gradle.kts` rather than listed, so a new module is corrected
without anyone remembering to edit the script.

And the wiring is a gate: `scripts/ci/check-ci-workflows.py` is registered in
`scripts/ci/static-gates.sh` and fails when a workflow is missing, when
CodeQL cannot write its results, when Scorecard cannot publish them, when
the auto-fix calls a script that is not there, when the scheduled run stops
opening its issue, when the schedule stops calling the pipeline, or when the
alert job's wait list has gone stale. It has a `--self-test` that breaks
each of those in turn, because a gate that cannot be shown to fail may be
enforcing nothing. The gate reads the workflows with the comments stripped:
a commented-out line is not a permission, and a check satisfied by its own
documentation is a check that stopped checking.

## Rationale

The four surfaces answer four different questions, and none of them is
answered by the build: "is the code safe", "is the repository safe", "does
anything run on its own", and "does a formatting finding cost a review
round". A project that has only the build answers none of them.

The gate is the part that makes the rest honest. This repository already
learned that lesson once — Part G of `scripts/check-gate-wiring.py` exists
because a gate registry with one caller is a registry whose other surface
will drift. A workflow is the same kind of surface: present in the tree, and
either running or not running with nothing to say which. The gate says what
each workflow must contain, so "the nightly run stopped opening issues" is a
red build rather than a silent absence.

The auto-fix proposes instead of applying, because a linter's correction and
a human's intent are different things. The proposal keeps the review, and the
review is where a bot that reorders an import list is caught — if it is
caught at all, which is why the correction is a diff a person reads and not
a silent rewrite of somebody's branch.

The nightly issue exists because a failure with nowhere to land is a failure
that is not reported. GitHub's own badge stays red until the next push; an
issue is in the list of things to do, and it says which job broke.

## Consequences

- **Always** keep the CI workflows wired, and keep the gate that proves it.
  A workflow that silently stops running is indistinguishable from one that
  never ran.
- **Never** push the auto-fix onto a branch somebody is working on. The
  corrections are proposed; the human decides.
- **Never** let the auto-fix decide whether the tree is acceptable. It
  rewrites what it can and reports what it cannot; `ktlintCheck` and
  `detekt` remain the gate.
- **Always** run the corrector as one Gradle invocation per module and one
  task per invocation. A task option given once reaches only the last
  matching task.
- **Always** discover the modules from `settings.gradle.kts` rather than
  listing them. A module that is not corrected is a module whose findings
  stay.
- **Never** report a scheduled failure anywhere else than the issue. A red
  cron run that nobody reads is not a report.
- **Always** keep `security-events: write` on CodeQL and `id-token: write`
  on Scorecard. Without them the analysis runs and nobody sees the result,
  which is the same as not running it.
- **Always** pin an action to a commit that exists. `ossf/scorecard-action@v2`
  does not resolve: the published tags stop at v2.5.0 and there is no `v2`,
  so a workflow that names it fails before it starts — on the first scheduled
  run, in the dark.
- **Always** take the report from the run's API. A hand-written list of the
  pipeline's jobs is a mirror, and the mirror goes stale silently.
- **Always** define the pipeline once and call it. The schedule is an entry
  point, not a property of the pipeline: a schedule inside the pipeline is a
  copy of the entry point, and the copy is what goes stale.
- **Always** give every job of a callable pipeline its own `permissions`.
  The caller's grant is a ceiling, not a gift: a job without a declaration
  inherits all of it, and the ceiling is validated statically, against jobs
  that never run.
- **Never** give a health check the permissions of a release *by accident*.
  The nightly does not push an image, and the registry write reaches only the
  job that would have pushed it.
- **Never** put the corrector in the gate registry. It is not a gate: it
  cannot fail a build, it rewrites the tree instead of judging it, and the
  verification loop (`check.sh`) must stay a loop that does not modify the
  repository it verifies. `ktlintCheck` and `detekt` are the gates; the
  corrector is their colleague, not their replacement.
- The nightly run costs a full pipeline every day. It is the price of
  knowing that the repository is still healthy when nobody touches it.
