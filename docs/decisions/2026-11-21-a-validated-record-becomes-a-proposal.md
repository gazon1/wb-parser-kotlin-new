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

The claim is published too, and it is prose — the easiest way to smuggle a value
into a committed file, because it is not a header, not a URL and not a body. So
the claim is judged by the record's own rule for a note: the redactor replaces
an e-mail address, a phone number, an account id and a token in it. A claim the
redactor would change is refused, and not rewritten: the wording of a claim is
what makes it traceable to the observation it names, so the record rewords it,
the tool does not.

The redactor alone is not enough, and the reason is the same name: the redactor
has a rule for every name it knows, and no rule at all for a name it has never
seen. `customer=Alice` is an ordinary word under a name nobody declared, and
every shape check walks it through. So the contract declares the parameters of
its own request and the values they may hold — a fixed value, a closed set of
values, a number within the bounds the endpoint itself names, or a phrase
somebody typed, which the record may only publish as the placeholder that
stands for it. A name the contract does not declare never reaches a committed
file, whatever it looks like. The declaration is the contract's, not the tool's:
a reviewer who disagrees with one of the values changes the record, not the
list.

A number the site chose is named by its value or by its bounds, never by its
shape. `dest` is the id the site's own code sends, and a rule that accepted any
digits would take a phone number for it: the redactor cannot tell the two
apart, because `dest=9123456789` is not a shape it knows. The page is a number
the endpoint itself bounds — it answers sixty pages, so a page beyond that is a
request it never answers with goods, and the pagination is one-based: the record
saw that `page=0` is answered with another envelope, the search result without
products. So the page is bounded from one.

The page is also the crawler's own parameter: the storefront's first request does
not send it. A record whose baseline sends it is not the storefront's first
request, and the generated test compares the crawler's request with that
baseline — so such a record would produce a test that fails on a proposal the
tool accepted. A parameter the contract declares as the crawler's own is refused
in the storefront's request.

A parameter that occurs twice is refused wherever the contract reads a request:
a request that names a parameter twice says two things about it, and a query
read as a map keeps only the last value — the proposal would advertise one
request and the fixture would pin another.

The record is read by a loader that refuses a key written twice. PyYAML keeps
the last value of a duplicated key and says nothing about it, so a record whose
candidate names `sort` twice reads as a record that names it once — and every
check that runs afterwards sees a request nobody sent.

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

The fixtures carry no captured payload. The body is rebuilt by the generated
test from the recorded shape and the ordered product ids, and the fixture holds
that order once: a fixture that carried it twice could say two different things
about it. Only the *names* of the headers are kept; the redacted placeholders
stay placeholders. The production `deviceid` constant never reaches a fixture or
a proposal.

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
reads hashes and does targeted consistency checks on the JSON of the evidence
bundles and of the generated fixtures — no regeneration, no YAML, no network —
and it is registered
in `scripts/ci/static-gates.sh` as `gate blocking "the generated proposals are
current" …`, so it runs in `./check.sh` and in CI alike. The gate also compares
the manifest with the file list the proposal itself carries: a file deleted from
the disk *and* from the manifest together would otherwise pass.

The manifest holds the hash of the redactor beside the hash of the generator.
The generator loads the redactor by its path, so the redactor is a generation
dependency: a change of the sanitization rules has to invalidate the proposal
exactly as a change of the generator does, or the committed artifacts were never
checked against the policy that is in force now. The gate does not only compare
those hashes — it requires the manifest to name every tool the proposal was
produced by. A dependency the manifest leaves out is a dependency nobody
watches: the redactor could change, the proposal would keep its hashes, and the
gate would stay green over a proposal that no tool on the disk would produce
any more.

The gate binds a fixture to the record, not to itself. A fixture names the
observation it was built from, and the gate checks that the observation is one
the evidence holds and that the experiment, the run, the request and the order
of the answer are the ones the capture recorded. The fixture's own fields
cannot be the witness: a fixture renamed on the disk, renamed inside itself, in
the manifest and in the proposal together is internally consistent and says
nothing the record ever said, and only the record can tell. The URL of a
fixture is compared with the URL of its capture for the same reason: a fixture
that asks another request still matches every hash. The query of the fixture and
the order of its products are compared with the capture as well — those are the
two fields the generated test reads, so a fixture that changed either of them
changed the test while every hash still matched. So are the rest of the fields
the fixture copies from the capture: the names of the headers the request sent,
the metadata of the answer and the shape of the answer. The generated test reads
all of them, and a fixture that changed one of them changed the test while every
hash still matched. A file that is gone is a
finding, not a crash: the gate reports what is wrong with the proposal and keeps
going, and so is an evidence bundle that is malformed or a capture id that two
bundles hold — the generator refuses a duplicate id, so the gate has to read the
same record the generator read.

The gate reads the query as the pairs it carries, not as a map. A name that
occurs twice is named: the generator refuses a request that names a parameter
twice, and a gate that read the query as a map would keep the last value of the
name and say nothing about the first — the very disagreement the generator
refuses to publish.

The gate binds a fixture to its capture on every dimension, and a dimension
that is missing on either side is not a dimension that matches: the comparison
of two absent values would pass, and a capture that says nothing about the
answer would witness anything. The names of the headers and the top-level keys
of the answer are sets — the generated test asks whether a name is there, not
in which order the names came — so a capture that lists the same names in
another order records the same request.

The gate reads every nested object it walks into, and names the one that is not
an object. The request of a capture and the order of its answer are read to be
compared with the fixture, so a request that is a list has to be a finding and
not a crash on the next line that asks it for its method.

Every endpoint the proposal names is judged by the record's own rule — and the
proposal names the endpoint of *every* candidate the record holds, not only the
one that became a contract. A candidate that did not become a contract is still
a line of a committed file. So its endpoint is read as the pairs of its query
and as the segments of its path, and a value the record's own redactor would
replace is refused there as well. The host is a name of a service and is not
judged as a note, but a host that carries credentials is refused outright.

A placeholder is the whole value. The redactor leaves a placeholder behind
instead of the value it removed, and a placeholder with something appended to
it is not that: `<redacted:token>alice@example.com` is an address the redactor
would replace, and a value that starts with a placeholder is not a placeholder.
The question is asked in one place, and the generated test asks it with the
generator's own pattern: a second rule that recognises a placeholder by its
prefix would drift from the first.

A URL that cannot be read as one is refused, not raised on. `urlsplit` raises
for a malformed authority — an IPv6 bracket that is not closed — and a record
that holds such a URL is a record the tool cannot judge. A traceback is not a
way to tell a reviewer that the record does not earn a proposal, so the
generator refuses such a URL and the gate reports it as a finding and keeps
checking the other fixtures.

The contract table is checked against itself. It answers three questions about
the same names — which request this is, who sends the parameter and what value
it may hold — so a name one of its declarations names and another does not is
a parameter the contract cannot judge, and a parameter it declares without a
rule is a parameter whose value nobody may publish. Both are bugs of the tool
rather than of the record, and the tool names them.

And what cannot be vouched for is not published at all. The redactor has a rule
for every name it knows and none for a name it has never seen, and `customer=Alice`
is an ordinary word under a name nobody declared — a person's name all the same.
The parameters of a candidate that is not a contract are not the contract's:
the record holds them in its own `query` field, where the reviewer of the
record reads them. So the proposal names the place and not the parameters.

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
- **Never** accept a capture whose answer disagrees with the observation: the
  fixture is built from the summary and the proposal publishes the capture, so
  the status, the content type, the size, the kind of the answer and the shape
  of the answer have to be the same on both sides — a summary that says 200
  while the bundle says 498 would write a fixture and a proposal that describe
  two different answers.
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
  parameters of its request and the values they may hold — a fixed value, a
  closed set of values, a number within the bounds the endpoint names, or a
  phrase that may only be published as its placeholder. A number the site chose
  is named by its value or its bounds, never by its shape: a phone number and a
  catalog id look the same to a redactor.
- **Never** accept a request that names a parameter twice: it says two things
  about the request, and a query read as a map keeps only the last value — so
  the generated test reads the query as pairs and asserts that every parameter
  is named once.
- **Never** accept a record that writes a key twice: the duplicate is gone before
  anybody can refuse it, and every check after that sees a request nobody sent.
- **Never** publish a claim the record's own redactor would change: the claim is
  prose, and prose is the easiest way to smuggle a value into a committed file.
  The wording of a claim is the record's to change, not the tool's.
- **Never** accept a storefront request that carries a parameter the contract
  declares as the crawler's own: the baseline would not be the storefront's first
  request, and the generated test would compare two different requests.
- **Never** keep a second copy of the sanitization rules in the generator: the
  generator asks `redact.py`, so the two cannot drift apart — and the hash of
  the redactor belongs to the manifest beside the hash of the generator, so a
  change of the rules invalidates the proposal.
- **Never** let a manifest leave a generation dependency out: the gate requires
  the generator and the redactor to be named, because a dependency nobody names
  is a dependency nobody watches.
- **Never** let a fixture hold the order of the products twice: the body is
  rebuilt by the test from the order the fixture carries.
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
  names has to be one the evidence holds, with the experiment, the run, the
  request, the query, the order of the products and the order of the answer the
  capture recorded — a fixture cannot be its own witness.
- **Never** let a missing dimension count as a match: a dimension that is
  missing on either side of the comparison is a dimension the record does not
  speak about, and the capture cannot witness what it does not hold.
- **Never** let a dimension of the wrong type count as a match: a status that
  became the text `200` is not the status the capture recorded, and a comparison
  that reads both sides as text would call them equal.
- **Never** read the names of the headers or the top-level keys of the answer as
  an order: the generated test asks whether a name is there — and a name that
  appears twice is named on its own, because a set hides the repetition.
- **Never** let a malformed URL raise out of the tool: a URL that cannot be read
  is a record the tool refuses and a finding the gate names; a URL that reads
  but names no host is refused as well, because a request that names no host is
  not a request the record could have made — and the host is read as the host
  and not as the whole authority, so an authority that names a port alone
  (`http://:80/path`) names no host either.
- **Never** let the drift gate read a query as a map of anything but text to
  text: that is how the generated test reads it, so a name or a value of
  another type is a query the test would read differently than the request that
  was made.
- **Always** let the drift gate check that a fixture names the investigation of
  the proposal that holds it: a fixture of another investigation is a fixture of
  another record — and a fixture that names no investigation is named too.
- **Never** let the drift gate bind a fixture to the investigation only when the
  manifest names one: the manifest has to name the investigation it was produced
  from, and a check that runs only when the manifest helps it is a check the
  manifest can switch off.
- **Never** let a malformed record raise out of the tool: a nested field that is
  not an object is refused by name — an optional field that is absent is a field
  the record does not speak about, and a field that is there and is not an
  object is a broken declaration. Reading it as an empty one would make the two
  the same and let an answer publish itself over a declaration nobody could
  read. An evidence bundle that is not an object, or whose captures are not a
  list, is refused by name for the same reason. A field that is there and is
  null is a broken declaration as well: the record says the field is there and
  says nothing about it, which is not the same as a field the record does not
  speak about.
- **Never** let the drift gate read the evidence of another investigation: a
  proposal is produced from one record, the record of the investigation the
  manifest names, and the captures of another record cannot witness its
  fixtures. A source of another investigation is a finding, and its bundles are
  not read at all. A source outside that record is a finding as well: a
  manifest that may point anywhere is a manifest that watches nothing.
- **Never** let the manifest leave part of the record out: the investigation and
  its validation are both named as sources, because a manifest that watches one
  of them watches half of the record — and the half it leaves out is the half
  that may change unnoticed.
- **Never** let the proposal and the manifest point at two records: the
  manifest names the investigation, the proposal lives in the directory of that
  investigation and says in its own words which record it was produced from.
  A proposal that names another investigation is a proposal that witnesses
  neither.
- **Never** let the generator skip an evidence bundle in silence: a bundle that
  names no captures is a malformed bundle, and it is refused by name — a bundle
  nobody reads is a bundle nobody watches. A capture that is not an object, or
  that names no id, is refused the same way: the drift gate names it, and the
  two tools have to agree on what a bundle may hold.
- **Never** write a dimension of the wrong type into a fixture: the size of the
  body is a number and the kind of the body is a boolean, and a boolean is not a
  number. The generator checks the types the drift gate expects before it
  writes, so a malformed record is refused before it reaches a fixture.
- **Never** publish an answer that was not counted: the generated test asserts
  the number of the products the answer carried, so an observation that records
  no count is refused by name, and the drift gate asks the same question of the
  fixture and of the capture.
- **Never** take a source on its word: the path is normalized and resolved
  before it is judged, so a name that reaches out of the record with `..`, or
  that points at a file of another investigation, is not a file of this record.
- **Never** publish an answer the evidence did not record: the status, the
  content type and the keys of the body come from the candidate's own summary,
  so the summary has to agree with the capture, and the published values are the
  ones the capture holds.
- **Never** recognise a placeholder by its prefix: one predicate asks whether the
  placeholder is the whole value, and the generated test uses the generator's
  own pattern.
- **Never** let the contract table contradict itself: a name one of its
  declarations names and another does not is a parameter the contract cannot
  judge.
- **Never** let the drift gate die on a malformed record: a bundle that is not
  JSON, a bundle whose captures are not a list and a capture id that two bundles
  hold are findings, not crashes — the gate reports what is wrong and keeps
  going.
- **Never** let the drift gate die on a malformed nested object: a capture or a
  fixture whose request or ordering is not an object is a finding, not a crash.
- **Never** let the drift gate read a query as a map: a name that occurs twice is
  named, because the map would keep only the last value and hide the first.
- **Never** publish an endpoint the record's own redactor would change: the
  proposal names the endpoint of every candidate the record holds, and a
  candidate that did not become a contract is still a line of a committed file.
- **Never** take a value for a placeholder when it only starts like one: a
  placeholder is the whole value the redactor left behind, and a value with
  something appended to it is a value the redactor would replace.
- **Never** publish the parameters of a candidate that is not a contract: the
  redactor has no rule for a name it has never seen, so a value it cannot vouch
  for is not published — the proposal names the place, and the record holds the
  parameters.
- **Never** let the drift gate bind a fixture to the record on some of its
  fields: every field the fixture copies from the capture — the request, the
  header names, the metadata of the answer, the shape of the answer and the
  order of the products — is what the generated test reads.
- **Never** add a production dependency on the research tooling; the generator
  writes into a test source set and a development directory.
- A new contract family is a new entry in `CONTRACTS`, not a new framework.
- One contract has one proposal: the test and the fixtures belong to the
  contract, so a second investigation of it is refused.
- The suite of the generator is `scripts/tests/test_wb_research_generate_proposal.py`;
  the `--self-test` of the tool is the smoke check of the installed generator, and
  it does not repeat the rules — the rules live in the suite.
