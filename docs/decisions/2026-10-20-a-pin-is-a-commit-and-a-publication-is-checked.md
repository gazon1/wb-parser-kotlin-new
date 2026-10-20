---
title: "A pin is a commit, and a publication is checked, not assumed"
date: 2026-10-20
tags: [pipeline, security, ci, gates]
---

## Context

The Scorecard workflow had been green for weeks, and the Scorecard API held
nothing for this repository. Every run computed a score, and every run threw
it away:

```
error sending scorecard results to webapp: http response 400, status: 400 Bad
Request, error: {"code":400,"message":"workflow verification failed: workflow
verification failed: imposter commit: 46dc3187a971a9febf3aeb7f94ccde489f7bb46a
does not belong to ossf/scorecard-action"}
```

Three things hid this. The action treats a rejected publication as a
*warning* — the step is green, and so is the run. The pin looked as strong as
a pin can look: forty hexadecimal characters. And the gate that checks the
pins checked exactly that — the shape, forty characters — which a tag object
and a commit share.

The pin was the SHA of `refs/tags/v2.5.0`, and that tag is *annotated*: its
SHA is the identifier of the tag object, not of any commit. The publication
API asks the upstream repository whether the SHA is a commit of the action's
own repository, and answered no. The commit the tag points at is
`fa7117db83c52d787ae5a89f350999701505d78e` — which is what upstream's own
workflow pins.

Two smaller findings came out of the same investigation. The workflow asked
for `fail_on_publish_error: true`, an input that does not exist in v2.5.0 —
it arrived in the action's `main` branch seven commits later — so the
publication failure was a warning on every run ("Unexpected input(s)").
And no workflow declared a top-level `permissions:`, which the Scorecard
reports as "no topLevel permission defined": a job that does not declare its
own inherits the caller's grant.

## Idea

1. **Pin the action to the commit, and add a check that a pin is a commit.**
   Chosen. The pin is fixed, and the class of mistake is made impossible to
   repeat silently.
2. **Ask the upstream repository in the local gate.** Rejected: the local
   cycle would depend on the network, and a CI failure that is about the
   network is a failure about nothing.
3. **Keep `fail_on_publish_error` and hope the action notices.**
   Rejected: the input does not exist in the pinned release. Passing an input
   the action does not know is a warning, not a guard.
4. **Trust the green run.** Rejected — that is what happened.

## Decision

**The Scorecard action is pinned to the commit the tag points at**, and the
comment next to it names the version, as every other pin does.

**The publication is checked against the API.** The workflow asks
`api.scorecard.dev` what score it holds for this repository, and fails when
the answer is empty. The check runs with `if: always()`, so it also runs when
the action itself failed — that is exactly when "no score was published" is
the most important thing to say. This is the check `fail_on_publish_error`
would have done, and it works the same way for every version of the action,
including the ones that predate the input.

**A new checker asks the upstream repository whether each pinned SHA is a
commit** — `scripts/ci/check-action-pins.py`. It needs the network, so it
runs in the pipeline's `static` job rather than in the static registry, and
the gate that guards the CI surfaces checks that the checker exists and that
the pipeline calls it. A checker that lives nowhere is dead code, and a
checker that lives only in the pipeline can be deleted without anything
noticing.

**Every workflow declares a top-level `permissions:`.** It is the floor, not
the grant: every job below declares its own, and a job added later without a
declaration gets read-only access rather than everything the caller has.

## Rationale

A pin is a promise about *what runs*. Forty hexadecimal characters promise
nothing at all: they name a git object, and the interesting kinds of git
object — a commit, an annotated tag — are different things. The publication
API enforces the difference, and it enforces it silently: the analysis runs,
the result is refused, the step is green. A repository whose security posture
is measured by a number that never appears is a repository that believes it
is measured.

That asymmetry — the failure is invisible, the success is invisible — is why
the check asks the API rather than the shape of the SHA. A shape check cannot
tell a tag object from a commit; only the repository can. The check is
therefore network-bound, and the repository's rule that a local cycle must
not depend on the network decides where it lives: in the pipeline, with a
gate that makes sure it stays there.

Checking the publication rather than trusting the action's exit code closes
the same gap from the other side. The action is a third-party program whose
failure handling changes between releases; the API is the thing whose state
we actually care about. Asking the API is version-independent, and it is the
difference between "the workflow says it published" and "the score is there".

The top-level `permissions:` is the same idea one level down. The grant of a
job is a decision; a workflow that decides nothing delegates the decision to
the repository's default, which nobody chose and which can change underneath.
Declaring the floor makes the default explicit, and it is what the
Scorecard has been asking for.

## Consequences

- **Always** pin an action to a commit, and verify that the pin is a commit
  of the action's own repository. The checker is the verification.
- **Never** read a pin as a commit because it is forty characters long. A
  tag object's SHA is forty characters too.
- **Always** check that a publication reached the API. A green run that
  published nothing is the failure this repository cares most about.
- **Never** pass an input an action does not declare. It is a warning on
  every run and a guard on none.
- **Always** declare a top-level `permissions:` in every workflow.
- The pin checker needs `GITHUB_TOKEN`: the API's unauthenticated rate limit
  is sixty requests an hour, which is fewer than the number of pins here.
