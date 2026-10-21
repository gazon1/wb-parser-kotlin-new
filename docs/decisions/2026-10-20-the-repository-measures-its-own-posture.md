---
title: "The repository measures its own posture, and the actions it runs are pinned"
date: 2026-10-20
tags: [ci, security, supply-chain, scorecard, gate]
---

## Context

The Scorecard workflow started measuring the repository's own security
posture. A measurement is only useful if somebody acts on it, and the
report this repository would produce on its first run had four findings
that are all about *how the repository is configured* rather than about the
crawler:

- **Pinned-Dependencies** — every action was referenced by a tag
  (`actions/checkout@v7`). A tag is a name the owner can move, and what runs
  inside this repository's permissions should not be decided by a name.
- **Security-Policy** — there was no file saying what is in scope, what is
  not, and how to report a problem. A project with no policy has no
  "private" channel, so a finder either says nothing or says it publicly.
- **Code-Review** — there were no code owners, so nothing said who is
  expected to look at a change to the security configuration, to the
  workflows, or to the crawler.
- **Branch-Protection** — `main` was not protected at all. The first
  finding of this project was that anybody with push access could change
  the workflows that run with the repository's permissions.

Three of the four are settings of the repository rather than files in it.
The fourth is files.

The one thing that is *not* measured by Scorecard, and that this repository
already believed in, is the rule that a guarantee nobody can see is not a
guarantee: the gates here are a registry, and every gate has to be able to
fail. A pinned action that a later commit unpins is exactly the kind of
regression that nothing in the build would report.

## Idea

1. **Fix the findings and stop there.** The score goes up, the report
   changes, and the next action — someone unpins an action while fixing an
   unrelated thing — is caught by nobody. Rejected: the finding is fixed
   once and then decays.
2. **Fix the findings and make the fix stick.** The pinning becomes a rule
   the gate enforces, the policy and the owners are files in the tree, and
   the protection is a setting with the contexts it requires. Chosen.
3. **Pin nothing and rely on Dependabot.** Rejected: Dependabot updates the
   versions the repository *names*; it does not decide that the repository
   should name a commit at all.

## Decision

**The actions are pinned to commits.** Every `uses:` in every workflow names
a forty-character commit, with the version kept in a trailing comment so
that Dependabot still knows what to update and a reader still knows what is
running. A local reference — `uses: ./.github/workflows/ci.yml` — is this
repository's own code and has nothing to pin.

**The pinning is a gate.** `scripts/ci/check-ci-workflows.py` fails when a
workflow names an action by a tag. The rule is in the same registry as the
rest of the gates, so it runs in the local loop and in CI, and it has a
`--self-test` case that unpins an action to prove it still fires. A score
that is measured once and decays is a number, not a guarantee.

**There is a security policy** (`SECURITY.md`): what is in scope for this
crawler, what is explicitly not (the Wildberries API itself, rate limiting,
dependencies that are reported upstream), and how to report a problem
privately through GitHub's private vulnerability reporting.

**There are code owners** (`.github/CODEOWNERS`): the security
configuration, the CI surfaces, the crawler and the rest all point at the
owner. The order matters — the last matching pattern wins — so the specific
patterns come first.

**`main` is protected.** The required status checks are the pipeline's own
gates: `static`, `build`, `coverage`, `lint`, `dependency-health`,
`gate-honesty`, and the CodeQL analysis. Force pushes and deletions are
off. Approving reviews are *not* required, because this repository is
maintained by one person and by agents acting for that person: a rule that
demands a second approver would block every change, and a rule that is
always bypassed is a rule that is not there. Administrators are not
enforced either, so a human can still get out of a stuck branch.

**Dependabot's security updates are enabled**, so that a dependency with a
published advisory opens a pull request instead of waiting to be noticed.

## Rationale

The three findings that are files were cheap to fix and worth fixing
because each of them says something the repository meant anyway. The
security policy names the boundary of this project: a crawler for somebody
else's service, where the interesting question is what the crawler does
with a response it did not expect, and where "the storefront's API is
vulnerable" is not this repository's finding to make. The code owners
file says what the owners file in every repository says, and it earns its
place because the workflow files *are* code that runs with the
repository's permissions.

The pinning is the one with a real trade-off. A pinned action cannot
receive a security fix without a commit that updates the pin — which is
the point, because the commit is a reviewable diff that says what changed.
The cost is that a pinned action does not pick up a fix on its own, and
Dependabot's pull request is what closes that gap. The version in the
comment is what makes the pull request legible: a reviewer sees
`# v7` next to a new commit and knows it is the same major version, moved.

The gate is what turns a one-time fix into a property of the repository.
Scorecard measures the posture once a week; the gate measures it on every
change, including the one that would undo it.

Branch protection without required reviews is the honest version of it
for a single-maintainer repository: the gates must pass, the history
cannot be rewritten, and a human can still override.

## Consequences

- **Always** pin an action to a commit and keep the version in a comment.
  The gate fails otherwise.
- **Never** let a score be the thing that is maintained. The scorecard
  report is read; the gate is what holds.
- **Always** protect `main` with the pipeline's own gates as the required
  checks. A check that is not required is a check that can be skipped.
- **Never** require approving reviews in a repository with one maintainer.
  A rule that is always bypassed teaches people to bypass rules.
- **Always** keep the security policy's scope narrow and honest. A policy
  that claims everything is out of scope is not a policy.
- The pin bumps arrive as pull requests from Dependabot, and each one is a
  commit that moves a hash. That is the intended cost: the change is
  visible, reviewable, and reversible.
