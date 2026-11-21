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

- sanitized fixtures under
  `wbparser/src/test/resources/fixtures/catalog-search/<contract>/`;
- a Kotlin regression test at
  `wbparser/src/test/kotlin/ru/wbparser/infra/http/WbCatalogSearchContractTest.kt`;
- a proposal document at `devtools/wb-research/proposals/<id>/proposal.md`, with a
  `manifest.json` that records the SHA-256 of the record, of the generator and of
  every file it wrote.

The generator writes files and nothing else: no commit, no branch, no merge.
The proposal is a diff a human reads.

The eligibility rule is the whole point of the tool: a contract is eligible only
when the record is `concluded`, has a `validation.yaml` for that candidate, at
least one experiment of that candidate reached `validated` or `reproduced`, and
at least one claim *about that candidate* reached `validated` or `reproduced` —
and at least one of those claims reached `validated` strictly. A record holds the
claims of every endpoint it saw, so a validated claim about another endpoint says
nothing about this one; the link is the capture — the claim names it, and the
capture asked the candidate's endpoint. A `reproduced` claim is a fact the record
earned, but a contract needs a claim a controlled experiment confirmed, so a
record whose claims are only `reproduced` is refused. Only the experiments in
those states produce fixtures and assertions. The proposals name the claims that
were held back and why — a claim that did not reach a validated state is not an
assertion, and a candidate the record did not validate is not a contract.

The eligibility rule binds at the level of the observation, not only of the
claim. A claim is supported only when the evidence it names was produced by an
experiment of *this* contract that reached `validated` or `reproduced`, and when
that observation asked the candidate's own request: the same method, the same
endpoint, and the identity parameters the contract declares — the catalog search
and the filters search share one path and differ only in `resultset`, so the
path alone does not identify a contract. An identity parameter has to occur
exactly once: a request that names it twice says two things about which request
it is, and the reader that keeps the last value and the server that keeps the
first would disagree about it. An observation of another endpoint is not evidence
of this one, and a claim of an old capture is not evidence of a contract that was
validated by later experiments.

The contract table is the authority on the request itself. The endpoint and the
method of the candidate have to be the ones the contract declares: a record that
keeps the id of a known candidate and names another endpoint is not that
contract, however convincing its evidence is. The capture has to be the capture
of the observation — the same experiment, the same run, the same request and the
same order of the answer — because the bundle and the summary describe one run
twice, and a bundle filed under the id of another observation would put the
products of one run into the fixture of another.

The record is the boundary of sanitization. The query of the candidate is
published in the proposal, the request of the observation is committed in the
fixture and the crawl URL is committed in the test, so every value that reaches
one of them has to hold to the record's own rule: a committed value is a
placeholder or a value that carries no secret shape. A session token, an account
id or a phrase somebody typed in the query is refused, not published.

The rules that decide that are the record's own, and they are not copied into
the generator. The generator asks `redact.py` — the redactor that sanitized the
evidence in the first place — and the redactor judges the value together with
the name of the parameter it was sent under. The name is what carries the rule:
a value of `session` is a credential whatever it looks like, and a value of
`query` is a phrase somebody typed, so `Alice` and `123456789012` are refused
even though both are short and made of ordinary characters. A second, weaker
copy of the rules inside the generator would be a second policy, and the weaker
one is the one that decides what a proposal commits.

The redactor alone is not enough, and the reason is the same name: the redactor
has a rule for every name it knows, and no rule at all for a name it has never
seen. `customer=Alice` is an ordinary word under a name nobody declared, and
every shape check walks it through. So the contract declares the parameters of
its own request and the values they may hold — a fixed value, a closed set of
values, a number the site chose, or a phrase somebody typed, which the record
may only publish as the placeholder that stands for it. A name the contract does
not declare never reaches a committed file, whatever it looks like. The
declaration is the contract's, not the tool's: a reviewer who disagrees with one
of the values changes the record, not the list.

The candidate and the evidence have to agree. The proposal publishes the query
of the candidate as the parameters of the contract, and the fixture pins the
request that was recorded — so the candidate has to name the identity
parameters the contract declares, with the values the contract declares, and its
query has to be the query of the recorded request: the same parameters with the
same values, in both directions. A parameter the candidate advertises and the
request did not send is a parameter the evidence does not have; a parameter the
request sent and the candidate does not name is a parameter the proposal's table
leaves out while the fixture pins it. A candidate that says `resultset=filters`
while the record says `resultset=catalog` would leave the proposal advertising
one request and pinning another.

The capture has to describe its run. The bundle and the summary describe the
same run twice, and every dimension that tells one run from another — the
experiment, the run, the method, the URL and the order of the answer — has to
be there on both sides and to agree. A dimension that is missing is not a
dimension that matches: a capture that says nothing about the experiment it
belongs to cannot be told apart from a capture of another one, so an incomplete
capture is refused rather than accepted on the dimensions that happen to be
present. The observation has to belong to the experiment that holds it as well:
the bundle and the summary may agree with each other about a run while both
disagree with the experiment around them, and then a claim is earned by an
experiment the observation does not name.

The whole proposal is rendered and checked before the first byte reaches the
disk. A refusal never leaves half a proposal behind — a fixture without its
test, a test without its proposal — because a generator that leaves a mess
behind is a generator whose output has to be cleaned by hand before anybody can
look at it. The manifest records the hashes of the content that was written, so
the manifest says what the disk holds even when the writing itself fails.

The generator writes where the record points, so the record must not be able to
point outside: an observation id names a fixture file and has to be a plain file
name, and a fixture is written only inside the fixture directory — the output
root alone is not a boundary, because a name like `../../baseline` resolves
inside the repository. The guarantee is about the output root the operator chose:
the default is the repository, and every generated path stays inside the root
that was selected. The evidence index refuses two bundles that carry the same
capture id, because the second bundle would silently shadow the first.

The fixture directory belongs to the contract: the fixtures live in
`fixtures/catalog-search/<contract>/`, so two contract families never share one
inventory, and the directory is owned by the generator — a file in it that this
generation does not produce is a leftover of another one, and the generator
refuses before it writes.

The drift gate inventories the disk and not only the manifest: a fixture or an
evidence bundle that no manifest names is drift too, because the manifest is an
inventory someone can edit. The evidence directory is derived from the id of the
investigation rather than from the manifest, so a manifest that names no evidence
at all cannot hide the bundle the generator would read next time, and a file that
is not text is a finding rather than a crash.

The known differences between the recorded request and the production request
are declared in the generator (`crawler_only` / `storefront_only`), so the
generated test compares the two and treats any difference that is not listed as
drift. The reasons and the expected values live in that table, and the table is
the decision: a reviewer who disagrees with one of them changes the table, and
the change is visible in the diff of the generator. A difference the crawler adds
also carries its expected value, where the value is fixed: the storefront did not
send that parameter, so the record holds no value for it and the crawler's own
contract holds it. The generated test pins the endpoint as well — the scheme, the
host and the path of the recorded URL — because the wrong endpoint answers 200
with somebody else's products, and it pins the crawler's own page, so a page that
starts at zero is a failing test.

The crawl URL of the record is the crawler's own URL for that catalog, and the
contract declares its shape. The test builds the runtime URL from the catalog id
with the production constructor, parses it back and asks the API with the token
the parse yields — so the test proves that the token of the crawl URL reaches the
request, and not that a constant in the test reaches it.

The fixtures carry no captured payload. The body is reconstructed from the
recorded shape and the ordered product ids; only the *names* of the headers
are kept; the redacted placeholders stay placeholders. The production
`deviceid` constant never reaches a fixture or a proposal.

The output goes where the code lives: the test and the fixtures are in
`:wbparser`, so CI runs them offline, and the proposal document is next to the
investigations it came from. The research tooling stays in `devtools/` and
`scripts/`; no production module depends on it.

The test and the fixtures belong to the *contract*, not to the investigation
that found it. Two investigations of one contract would write the same files,
and the second would silently invalidate the first one's proposal — so one
contract has one proposal, and an investigation that supersedes the old one
removes the old proposal first. The investigation id names a directory in the
repository, so it is checked to be a single name and not a path before any file
is read or written, the rule `capture.py` and `plan.py` already follow.

The house pattern from `scripts/ci/check-catalog-models.py` is reused: a
generator, a committed artifact, an offline drift gate. `scripts/ci/check-proposal-fresh.py`
compares the manifest's hashes with the files on disk and fails when the record,
the evidence bundles, the generator or a generated file changed under it. It
reads hashes only — no regeneration, no YAML, no network — and it is registered
in `scripts/ci/static-gates.sh` as `gate blocking "the generated proposals are
current" …`, so it runs in `./check.sh` and in CI alike. The gate also compares
the manifest with the file list the proposal itself carries: a file deleted from
the disk *and* from the manifest together would otherwise pass.

The manifest holds the hash of the redactor beside the hash of the generator.
The generator loads the redactor by its path, so the redactor is a generation
dependency: a change of the sanitization rules has to invalidate the proposal
exactly as a change of the generator does, or the committed artifacts were never
checked against the policy that is in force now.

The gate binds a fixture to the record, not to itself. A fixture names the
observation it was built from, and the gate checks that the observation is one
the evidence holds and that the experiment and the order of the answer are the
ones the capture recorded. The fixture's own fields cannot be the witness: a
fixture renamed on the disk, renamed inside itself, in the manifest and in the
proposal together is internally consistent and says nothing the record ever
said, and only the record can tell. A file that is gone is a finding, not a
crash: the gate reports what is wrong with the proposal and keeps going.

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
research tooling. The manifest keeps the hashes of the record, of the evidence
bundles the fixtures are rebuilt from and of the generator, so a stale proposal
is caught whichever of them moved. The gate compares the manifest with the file
list the proposal itself carries, because a manifest that was edited to match a
deleted file would otherwise pass.

## Consequences

- **Always** generate from a record, never from a chat message; the generator
  refuses a record that does not earn a proposal and says which part of it
  failed.
- **Never** generate an assertion from a claim that is only `observed`,
  `inconclusive`, `open` or `rejected`.
- **Never** generate a proposal from a record whose claims are only `reproduced`:
  a contract needs a claim a controlled experiment confirmed.
- **Never** accept a validated claim of another endpoint as the validation of
  this one: the claim has to be one the candidate's own experiments observed.
- **Never** generate an assertion from an observation that has no capture in the
  evidence, or whose capture does not hold the products the observation
  recorded — a fixture rebuilt from a shape nobody recorded is a guess.
- **Never** put a captured body, a cookie, a token or the production `deviceid`
  into a fixture or a proposal; the body is reconstructed from the recorded
  shape.
- **Never** let the generator commit, branch or merge; the output is a diff a
  human reviews.
- **Never** let an investigation id name anything but a single directory name;
  every generated path stays inside the output root that was chosen, and the
  default output root is the repository.
- **Never** let an observation id name anything but a plain fixture file name,
  and write a fixture only inside the fixture directory — the output root alone
  is not a boundary.
- **Never** accept an observation that did not ask the candidate's own request:
  the method, the endpoint and the identity parameters the contract declares —
  and each identity parameter exactly once.
- **Never** accept a candidate whose endpoint or method disagrees with the
  contract table: the contract is what the generated test pins.
- **Never** accept a capture that does not match the observation it is filed
  under: the experiment, the run, the request and the order have to agree, and
  every one of them has to be there — a dimension that is missing is not a
  dimension that matches.
- **Never** accept an observation that does not belong to the experiment that
  holds it: the bundle and the summary may agree with each other and both
  disagree with the experiment around them.
- **Never** commit a value the record did not sanitize: a query value, a request
  URL and a crawl URL that reach a proposal, a fixture or a test carry a
  placeholder or a value the record's own redactor would leave alone — and the
  redactor judges the value by the name of the parameter it was sent under.
- **Never** publish a parameter the contract does not declare, whatever its value
  looks like: the redactor has no rule for a name it has never seen, and a name
  nobody can explain may carry somebody's name. The contract declares the
  parameters of its request and the values they may hold.
- **Never** keep a second copy of the sanitization rules in the generator: the
  generator asks `redact.py`, so the two cannot drift apart — and the hash of
  the redactor belongs to the manifest beside the hash of the generator, so a
  change of the rules invalidates the proposal.
- **Never** accept a candidate that contradicts the recorded request: it has to
  name the identity parameters the contract declares, and its query has to be
  the query of the recorded request — the same parameters with the same values,
  in both directions.
- **Never** write a file before the whole proposal is rendered and checked: a
  refusal leaves nothing behind.
- **Never** let the fixtures of two contracts share one directory, and never
  leave a file in the fixture directory that the generator does not produce.
- **Never** accept a claim whose evidence does not come from an experiment of
  this contract that reached `validated` or `reproduced`.
- **Never** accept two evidence bundles that carry the same capture id.
- **Always** declare the known differences between the recorded request and the
  production request in the `CONTRACTS` table, so an undeclared difference
  fails the generated test — and pin the value of the differences the crawler
  adds, since the record holds no value for them.
- **Always** regenerate and commit the proposal when the record changes; the
  drift gate fails otherwise.
- **Always** let the drift gate inventory the disk as well as the manifest: a
  file no manifest names is drift too.
- **Always** let the drift gate check that a fixture is named after the
  observation it holds and lives in the directory of its contract: a fixture
  renamed on the disk, in the manifest and in the proposal together still drifts.
- **Always** let the drift gate bind a fixture to the record: the observation it
  names has to be one the evidence holds, with the experiment and the order the
  capture recorded — a fixture cannot be its own witness.
- **Never** add a production dependency on the research tooling; the generator
  writes into a test source set and a development directory.
- A new contract family is a new entry in `CONTRACTS`, not a new framework.
- One contract has one proposal: the test and the fixtures belong to the
  contract, so a second investigation of it is refused.
- The suite of the generator is `scripts/tests/test_wb_research_generate_proposal.py`;
  the `--self-test` of the tool is the smoke check of the installed generator, and
  it does not repeat the rules — the rules live in the suite.
