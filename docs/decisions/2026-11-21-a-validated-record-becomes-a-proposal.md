---
title: "A validated investigation becomes a Kotlin proposal and an offline regression test"
date: 2026-11-21
tags: [research, tooling, contract]
---

## Context

Issue #313 asks for a narrow generator: a validated WB investigation should
turn into a reviewable Kotlin proposal plus fixture-based offline regression
tests and documentation.

The crawler already speaks one of the contracts the research has validated. The
storefront catalog endpoint is implemented in `wbparser`: `WbCatalogApi` builds
the URL, `WbCatalogRequest` owns the pagination, `WbCatalogParser` turns the
answer into items. What did not exist was a link between the *evidence* and
that code — nothing said that the parameters the crawler sends are the ones the
site was seen to send, and nothing said that the header set has not drifted.

Writing the test by hand from a record is where the two drift: a parameter is
dropped, a header is invented, a page boundary is guessed. And a test written
from a record that only *observed* something is a guess with a confident shape:
the record `2026-11-19-wb-catalog-request-9467` has no `validation.yaml` at
all, and one of the claims of `2026-11-20-wb-catalog-agent` is only `observed`
and another is `inconclusive`.

## Idea

1. A general code-generation platform: a contract DSL, a generator per endpoint,
   an adapter framework. It would eventually cover more than one contract — and
   today it would carry one contract behind abstractions nothing needs.
2. A template per contract family, hand-written, that someone edits per
   investigation. It is the shape of the output without the guarantee that the
   output matches the record.
3. One generator that reads the record, refuses the records that do not earn a
   proposal, and writes the fixtures, the test and the proposal document.

## Decision

`scripts/wb-research/generate_proposal.py` turns a validated investigation into
three things:

- sanitized fixtures under `wbparser/src/test/resources/fixtures/catalog-search/`;
- a Kotlin regression test at
  `wbparser/src/test/kotlin/ru/wbparser/infra/http/WbCatalogSearchContractTest.kt`;
- a proposal document at `devtools/wb-research/proposals/<id>/proposal.md`, with a
  `manifest.json` that records the SHA-256 of the record, of the generator and of
  every file it wrote.

The generator writes files and nothing else: no commit, no branch, no merge.
The proposal is a diff a human reads.

The eligibility rule is the whole point of the tool: a contract is eligible only
when the record is `concluded`, has a `validation.yaml` for that candidate, and at
least one claim of that candidate reaches `validated` or `reproduced`. Only the
experiments in those states produce fixtures and assertions. The proposals
name the claims that were held back and why — a claim that did not reach a
validated state is not an assertion, and a candidate the record did not validate
is not a contract.

The known differences between the recorded request and the production request
are declared in the generator (`crawler_only` / `storefront_only`), so the
generated test compares the two and treats any difference that is not listed as
drift. The reasons are the contract's, not the tool's: a reviewer who
disagrees with one of them changes the record, not the list.

The fixtures carry no captured payload. The body is reconstructed from the
recorded shape and the ordered product ids; only the *names* of the headers
are kept; the redacted placeholders stay placeholders. The production
`deviceid` constant never reaches a fixture or a proposal.

The output goes where the code lives: the test and the fixtures are in
`:wbparser`, so CI runs them offline, and the proposal document is next to the
investigations it came from. The research tooling stays in `devtools/` and
`scripts/`; no production module depends on it.

The house pattern from `scripts/ci/check-catalog-models.py` is reused: a
generator, a committed artifact, an offline drift gate. `scripts/ci/check-proposal-fresh.py`
compares the manifest's hashes with the files on disk and fails when the record,
the generator or a generated file changed under it. It reads hashes only — no
regeneration, no YAML, no network — and it is registered in
`scripts/ci/static-gates.sh` as `gate blocking "the generated proposals are
current" …`, so it runs in `./check.sh` and in CI alike.

## Rationale

Option 1 is a framework: it would be built for contracts that do not exist yet,
and its output would be harder to review than the code a person would write.
Option 2 has the shape but not the guarantee — the template is edited by hand,
so nothing guarantees that the output still matches the record, and the person
editing it is the one who decides what is validated. Option 3 keeps the review
in the diff and puts the eligibility rule in code, where it cannot be skipped
by good intentions.

Only one contract family is known to the generator. A second family is a new
entry in the `CONTRACTS` table, not a framework — and the table is the place
where the known differences are declared, so a new family forces the author to
say what the crawler adds and what the site adds.

The proposal pins the existing adapter rather than adding a second one. The
crawler already implements the contract; what was missing was the link to the
evidence, and a second adapter would have been a second place to drift.

The drift gate is a hash comparison rather than a regeneration because the
generator needs the record and the record is a development artifact: a gate that
had to run the generator would make the ordinary commit path depend on the
research tooling. The manifest keeps both hashes — the record's and the
generator's — so a stale proposal is caught whichever of the three moved.

## Consequences

- **Always** generate from a record, never from a chat message; the generator
  refuses a record that does not earn a proposal and says which part of it
  failed.
- **Never** generate an assertion from a claim that is only `observed`,
  `inconclusive`, `open` or `rejected`.
- **Never** put a captured body, a cookie, a token or the production `deviceid`
  into a fixture or a proposal; the body is reconstructed from the recorded
  shape.
- **Never** let the generator commit, branch or merge; the output is a diff a
  human reviews.
- **Always** declare the known differences between the recorded request and the
  production request in the `CONTRACTS` table, so an undeclared difference
  fails the generated test.
- **Always** regenerate and commit the proposal when the record changes; the
  drift gate fails otherwise.
- **Never** add a production dependency on the research tooling; the generator
  writes into a test source set and a development directory.
- A new contract family is a new entry in `CONTRACTS`, not a new framework.
