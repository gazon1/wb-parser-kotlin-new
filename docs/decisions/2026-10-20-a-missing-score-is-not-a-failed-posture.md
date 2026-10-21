---
title: "A missing score is not a failed posture"
date: 2026-10-20
tags: [ci, security, observability]
---

## Context

The Scorecard workflow ended with a step that asked the Scorecard API whether
it held a score for this repository, waited up to a hundred seconds, and
failed the run when it did not. The run failed on every push to `main` with
the message "the Scorecard API holds no score for this repository — the
publication was rejected".

Nothing had been rejected. The facts:

- The API answers `404` for `gazon1/wb-parser-kotlin-new`,
  `gazon1/product-catalog` and `gazon1/Caddy-vps` alike, and `200` for
  repositories it indexes (`ossf/scorecard` answers with a commit).
- The action itself prints `Publication enabled: false`. In
  `ossf/scorecard-action` v2.5.0 — the newest published release, and the one
  whose image lives on ghcr.io — the `Options.PublishResults` field carries no
  `env` tag, so `env.Parse` never fills it from `INPUT_PUBLISH_RESULTS` and
  `setPublishResults` computes `false && !privateRepo`. The `publish_results:
  true` input is ignored, in v2.5.0 and in upstream's `main` alike. The
  action's own publish path (signing the results and posting them) does run,
  the API takes the post, and it keeps no score for a repository it does not
  index.

So the gate was reporting a rejection that never happened, on every run, for
a reason nobody could act on. A gate that cries wolf is worse than no gate:
the first reaction to a red that means nothing is to stop reading it.

## Idea

- Keep the strict check for repositories the API indexes: there, a score that
  stays on an older commit is the imposter-commit bug, and it must fail.
- Report the unindexed case as what it is — the API does not know this
  repository — and let the score live where it does live: in code scanning.
- Make the one unambiguous failure its own step: no results at all means the
  score was not computed, and that is a real failure of this workflow.

## Decision

The Scorecard workflow checks three things, in this order, and says which
one failed:

1. **The score was computed.** `results.sarif` exists and is not empty. No
   results means the analysis itself failed; the run is red.
2. **The API reached.** `GET https://api.scorecard.dev/projects/github.com/<repo>`
   answers `200` — the repository is indexed and the publication is expected.
   The workflow then waits for the API to hold the score of *this* commit
   (`github.sha`), and fails if the score stays on an older one: that is a
   rejected publication.
3. **The API does not know this repository.** The same GET answers `404`.
   Nothing was rejected, because there was nothing to reject. The run stays
   green with a warning that names the reason.

## Rationale

The workflow exists for two outputs: the SARIF in code scanning, which is
uploaded on every run, and the score in the Scorecard API, which is a badge
for a repository the API does not index. Failing on the second one every time
meant the first was delivered under a permanent red that nobody could clear —
and a permanent red is the state a reader learns to scroll past.

Waiting was also the wrong shape for the unindexed case: a hundred seconds of
sleeping to learn that nothing was going to arrive. The wait now happens only
where a publication is genuinely expected.

## Consequences

- **Never** report a publication that was not rejected as rejected. Say what
  the API answered: `404` is "no score for this repository", not "the
  publication was rejected".
- **Always** compare the score the API holds with the commit this run
  computed, not merely with "some score". A stale score that used to pass the
  check is the imposter-commit bug in another shape.
- **Always** keep the results upload unconditional. The score is delivered to
  code scanning on every run, and that is where a reader should look.
- **Never** treat a red gate that nobody can act on as a gate. If a check
  cannot be made to pass by any change in this repository, it either becomes
  a warning with a named reason, or it is removed.
- The badge stays empty while the repository is not indexed by the API. When
  it is indexed, the strict comparison starts working on the next run, with no
  change to the workflow.
