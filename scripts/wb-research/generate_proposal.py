#!/usr/bin/env python3
"""
generate_proposal.py — a Kotlin proposal and its regression tests from a validated
investigation.

Why this tool exists
--------------------
A research record knows *what* the site sends; the crawler needs *code* that asks for
exactly that. Writing the code by hand from a record is where the two drift: a
parameter is dropped, a header is invented, a page boundary is guessed. This tool reads
the record and writes the code that pins it, so the code and the evidence carry the
same claim.

The one rule that makes the output worth anything
-------------------------------------------------
**Only a validated contract is eligible.** A candidate whose claims are merely
*observed* is a hypothesis; code generated from a hypothesis is a guess with a
confident shape. The generator refuses such a record and says why.

What it writes
--------------
For the seller-catalog contract it writes:

- the fixtures — sanitized, committed, offline: the request as it was made, the shape
  of the answer and the order of the products (the body is rebuilt by the test from
  that shape, so no captured payload ever enters the repository);
- a Kotlin regression test that pins the production request to those fixtures;
- the proposal — a document that says which claim is behind which assertion, and what
  a reviewer has to look at.

It writes files and nothing else: no commit, no branch, no merge. The proposal is a
diff a human reviews.

Usage:
    uv run python scripts/wb-research/generate_proposal.py --investigation <id>
    uv run python scripts/wb-research/generate_proposal.py --investigation <id> --output-root <dir>
    uv run python scripts/wb-research/generate_proposal.py --self-test
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import SplitResult, parse_qsl, urlsplit, urlunsplit

import yaml

ROOT = Path(__file__).resolve().parents[2]
INVESTIGATIONS = ROOT / "devtools" / "wb-research" / "investigations"
PROPOSALS = ROOT / "devtools" / "wb-research" / "proposals"

# Where the generated artifacts live, relative to the repository root. The test goes
# into the module that owns the contract (`:wbparser`), the fixtures into its test
# resources — a fixture that is not on the classpath is not a fixture.
TEST_FILE = "wbparser/src/test/kotlin/ru/wbparser/infra/http/WbCatalogSearchContractTest.kt"
FIXTURE_ROOT = "wbparser/src/test/resources/fixtures/catalog-search"
PROPOSAL_DIR = "devtools/wb-research/proposals"
GENERATOR_PATH = "scripts/wb-research/generate_proposal.py"
# The generator loads the record's own redactor by its path, so the redactor is a
# generation dependency: a change of its rules has to invalidate the proposal, or
# the committed artifacts were never checked against the current sanitization
# policy.
REDACTOR_PATH = "scripts/wb-research/redact.py"

# The id of an investigation names a directory in the repository. A name that carries a
# separator or a `..` would point at another directory than the operator named, so it is
# refused before any file is read or written — the rule `capture.py` and `plan.py` follow
# for the same reason.
INVESTIGATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The id of an observation names a fixture file. The same rule applies to it: an id
# with a separator in it would name a file outside the fixture directory, and a
# containment check that only asks "is this still inside the repository?" would accept
# it — the repository holds files that must never be rewritten by a generator.
OBSERVATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The contract family this tool knows. It is one family on purpose: a generator that
# promises every endpoint of every site is a framework, and a framework nobody can
# verify is worse than no framework. A new family is a new entry here.
#
# `identity` — the parameters that tell two requests of one path apart. The catalog
# search and the filters search share the path `/__internal/u-search/exactmatch/...`
# and differ only in `resultset`, so the path alone does not say which request a
# capture holds. Without the identity parameters, a capture of the filters search
# passes for a capture of the catalog search.
#
# `crawler_only` — parameters the crawler sends and the storefront did not.
# `storefront_only` — parameters the storefront sent and the crawler does not.
#
# The generated test compares the recorded request with the production request, so a
# difference that is not listed here is a drift the test must catch. The reasons are
# the contract's, not the tool's: a reviewer who disagrees with one of them changes
# the record, not the list.
#
# A crawler-only parameter carries its expected value too, where the value is fixed:
# the storefront did not send it, so the record does not hold a value for it, and the
# only value anybody can pin is the crawler's own. `None` means the value is the one
# the request asks for — the page the crawler wants.
CONTRACTS: dict[str, dict[str, Any]] = {
    "storefront-catalog-search": {
        "test_class": "WbCatalogSearchContractTest",
        "endpoint": "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search",
        "method": "GET",
        "identity": {"resultset": "catalog"},
        # The URL the crawler builds for a catalog — the shape of the crawl URL the
        # record has to name. It mirrors `WbCatalogRequest.crawlUrlOf`, and the record
        # is checked against it: a page URL of another shape is a page the crawler
        # would not ask with, so the contract would pin a request nobody sends.
        "crawl_url": "https://www.wildberries.ru/catalog/{catalog_id}/list.aspx",
        "crawler_only": {
            "ffeedbackpoints": {
                "value": "1",
                "reason": "the «Баллы ВБ за отзыв» filter this crawler exists for",
            },
            "page": {
                "value": None,
                "reason": "the storefront's first request omits it — the crawler always sends the page it wants",
            },
        },
        "storefront_only": {
            "inheritFilters": "the storefront sends it; the crawler does not, and the record does not say the endpoint needs it",
            "suppressSpellcheck": "the storefront sends it; the crawler does not, and the record does not say the endpoint needs it",
        },
        # The parameters this contract's request carries, and the values they may
        # hold. A name that is not declared here is a name this contract does not
        # know: the record may hold it, the proposal must not publish it.
        #
        # The rule exists because a redactor judges a value by the name it was sent
        # under, and it has no name to judge an unknown one by: `customer=Alice` is
        # an ordinary word under a name it does not know, and it walks through every
        # shape check the record made. So the contract declares the parameters of
        # its own request, and each of them says what its value may be:
        #
        # - `value` — the one value the request carries;
        # - `values` — the closed set of values the request may carry;
        # - `range` — a number within the bounds the endpoint itself names (the
        #   page it is willing to answer);
        # - `free_text` — a phrase somebody typed, so the record may only publish
        #   the placeholder that stands for it.
        #
        # A number the site chose is not a free number: `dest` is the one id the
        # site's own code sends, and a rule that accepts any digits would accept a
        # phone number as readily — the redactor cannot tell them apart, because
        # `dest=9123456789` is not a shape it knows. So a number is named by its
        # value or by its bounds, never by its shape alone.
        #
        # The reasons are the contract's, not the tool's: a reviewer who disagrees
        # with one of them changes the record, not the list.
        #
        # Three declarations above answer three different questions about a
        # parameter, and they are kept apart on purpose:
        #
        # - `identity` answers *which request this is* — the parameters that tell
        #   two requests of one path apart (`resultset=catalog` against
        #   `resultset=filters`);
        # - `crawler_only` and `storefront_only` answer *who sends it* — the
        #   parameters the crawler adds and the ones the storefront adds;
        # - `parameters` answers *what may be committed* — the value a name may
        #   hold when it reaches a fixture or a proposal.
        #
        # So a name may appear in more than one of them, and it is the same name
        # with a different question asked of it: `resultset=catalog` identifies
        # the request and is also the one value the name may hold.
        #
        # The page is bounded from one: the pagination of the catalog is
        # one-based, and the record saw that `page=0` is answered with another
        # envelope — the search result, without products. The upper bound is the
        # number of pages the endpoint answers.
        "parameters": {
            "ab_testing": {"value": "false"},
            "appType": {"value": "1"},
            "curr": {"value": "rub"},
            "dest": {"value": "1259570991"},
            "ffeedbackpoints": {"value": "1"},
            "hide_vflags": {"value": "4294967296"},
            "inheritFilters": {"value": "true"},
            "lang": {"value": "ru"},
            "locale": {"value": "ru"},
            "page": {"range": {"min": 1, "max": 60}},
            "query": {"free_text": True},
            "resultset": {"value": "catalog"},
            "search": {"free_text": True},
            "sort": {"values": ["popular", "priceup"]},
            "spp": {"value": "30"},
            "suppressSpellcheck": {"value": "false"},
        },
        "experiments": {
            "baseline": "baseline",
            "sorted": "sort-priceup",
            "next-page": "page-2",
            "reload": "after-reload",
        },
    },
}

# The states a claim may have. A claim in any other state is not a fact the record
# earned, and a fact the record did not earn is not a contract.
VALIDATED_STATES = {"validated", "reproduced"}

# The token the crawler asks with. The record redacted the token it saw (it is the
# search text of the investigated catalog), so the value cannot be compared with the
# recorded one — only the name of the parameter can.
SEARCH_TOKEN = "menu_v3_9467 карты памяти"


# A placeholder is the only shape a removed value may take. The redactor leaves one
# behind instead of the value, and the generator publishes it as it is.
PLACEHOLDER = re.compile(r"<redacted:[^>]+>")


def is_placeholder(value: Any) -> bool:
    """Whether the value is the placeholder the redactor left behind.

    The placeholder is the whole value: a value that begins with one and goes on is
    not a placeholder but a value nobody judged. So the rule is asked in one place,
    and every place that recognises a placeholder asks it the same way.
    """
    return bool(PLACEHOLDER.fullmatch(str(value if value is not None else "")))


# A committed value is either a placeholder or a value that carries no secret shape.
# The rule is the one the record's own redactor applies, kept to what a query
# parameter can be: a name, a number, a flag, a path.
SAFE_QUERY_VALUE = re.compile(r"[A-Za-z0-9._%:@/+-]{1,32}")


class Refused(Exception):
    """The record does not earn a proposal."""


def load_redactor():
    """The record's own redactor, loaded from the file that sits next to this one.

    The generator does not keep a second, weaker copy of the sanitization rules: the
    rules that decide what may be committed live in `redact.py`, and a second copy is a
    second policy — the two drift, and the weaker one decides what a proposal commits.
    So the generator asks the redactor itself, and the redactor answers for the name of
    the parameter as well as for its value.

    The module is loaded by its path rather than imported by name: the tool is run as a
    script, tested by its path, and lives in a directory whose modules are called by
    what they do (`plan.py`, `redact.py`, `validate.py`), not by a package.
    """
    path = Path(__file__).resolve().parent / "redact.py"
    spec = importlib.util.spec_from_file_location("wb_research_redact", path)
    if spec is None or spec.loader is None:
        raise Refused(f"the redactor '{path}' could not be loaded — the sanitization rules are not optional")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


REDACTOR = load_redactor()


# --------------------------------------------------------------------------------------
# The record
# --------------------------------------------------------------------------------------


class UniqueKeyLoader(yaml.SafeLoader):
    """A loader that refuses a mapping that names the same key twice.

    PyYAML keeps the last value of a duplicated key and says nothing about it. So
    a record whose candidate names `sort` twice reads as a candidate that names it
    once, and every check that runs afterwards sees a request nobody sent: the
    duplicate is gone before anybody can refuse it. The record is written by
    hand, and a key written twice is a mistake in the record — so it is refused
    where it is read.
    """


def _construct_unique_mapping(loader: UniqueKeyLoader, node: Any, deep: bool = False) -> dict:
    mapping: dict = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise Refused(
                f"the record holds '{key}' twice — a key written twice is a key nobody can read, "
                f"and the value that is kept is the one nobody meant"
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


def load_yaml(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        try:
            return yaml.load(handle, Loader=UniqueKeyLoader)
        except yaml.YAMLError as error:
            raise Refused(f"the record '{path.name}' is not readable YAML: {error}") from error


def claims_of(investigation: dict[str, Any]) -> list[dict[str, Any]]:
    return list(investigation.get("validation") or [])


def validated_claims(investigation: dict[str, Any]) -> list[dict[str, Any]]:
    return [claim for claim in claims_of(investigation) if claim.get("state") in VALIDATED_STATES]


def claim_problems(claim: dict[str, Any]) -> list[str]:
    """Why a claim may not be published.

    A claim is free text the record wrote about the request, and the proposal
    publishes it. The record's own redactor already knows the rule for a note:
    an e-mail address, a phone number, an account id, a token and a URL are
    replaced in it. So the claim is judged by that rule — the generator keeps no
    second, weaker copy of it — and a claim the redactor would change is refused.

    It is refused, and not rewritten: the wording of a claim is what makes it
    traceable to the observation it names, so a claim that carries something
    personal is reworded by the record, not silently edited here.
    """
    text = str(claim.get("claim") or "")
    if not text:
        return []
    if REDACTOR.redact_note(text) != text:
        return ["the claim carries a value the record's own redactor would replace"]
    return []


def url_parts(url: str) -> SplitResult:
    """The parts of a URL — refused when the URL cannot be read as one.

    `urlsplit` raises for a malformed authority — an IPv6 bracket that is not
    closed, for example — and it is permissive about the rest: it accepts a string
    without a scheme and without a host, and it keeps an invalid port until
    somebody asks for it. A record that holds such a URL is a record the tool
    refuses, not one that crashes it: a traceback is not a way to tell a reviewer
    that the record does not earn a proposal.

    So the scheme and the host are asked for as well: a request that names no
    host is not a request, and a URL that reads as one is the address the record
    saw.
    """
    text = str(url or "")
    try:
        parts = urlsplit(text)
        # The port is asked for: `urlsplit` keeps an invalid port until somebody
        # asks for it, and a URL with a port nobody could dial is not a URL.
        _ = parts.port
    except ValueError as error:
        raise Refused(f"the URL '{url}' cannot be read as a URL ({error})") from error
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise Refused(
            f"the URL '{url}' names no host — a request that names no host is not a request the record could have made"
        )
    return parts


def published_endpoint(endpoint: str) -> str:
    """The endpoint as the proposal may name it — the place, without its query.

    The proposal names the candidates the record held besides the one it made a
    contract of, and their parameters are not the contract's: the record holds
    them in its own `query` field, where a reviewer of the record can read
    them. So the proposal repeats the place and not the parameters.

    The reason is the redactor's rule, not a rule of its own: the redactor has
    no rule for a name it has never seen, and `customer=Alice` is an ordinary
    word under such a name — a person's name all the same. A value that cannot
    be vouched for is not published; it is not published in a rewritten form
    either, because the record is what says what the request was.
    """
    parts = url_parts(endpoint)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


def endpoint_problems(endpoint: str) -> list[str]:
    """Why an endpoint may not be published.

    The proposal names the endpoint of every candidate the record holds, not only
    the one it makes a contract of: a candidate that did not become a contract is
    still a line of the proposal, and a line of a committed file. So every
    endpoint is judged by the record's own rule — a committed value is either a
    placeholder or a value the record's own redactor would leave alone.

    The rule is the redactor's, not a copy of it: the query of an endpoint is
    read as the pairs it carries, and each value is asked the same question the
    values of the contract are asked. A value that is a placeholder is the one
    the redactor left behind, and a placeholder is the whole value — a
    placeholder with something appended to it is a value the redactor would
    replace, and it is refused.

    The path is read as the segments it is made of — a segment is a name of a
    thing, and a long opaque one is a value that may not be committed. The host
    is a name of a service, so it is not judged as a note — but a host that
    carries credentials is refused outright.
    """
    problems: list[str] = []
    parts = url_parts(endpoint)
    for name, value in parse_qsl(parts.query, keep_blank_values=True):
        if is_placeholder(value):
            continue
        if REDACTOR.redact_query_value(name, value) != value:
            problems.append(f"the parameter '{name}' carries a value the record's own redactor would replace")
    for segment in parts.path.split("/"):
        if not segment:
            continue
        if REDACTOR.redact_note(segment) != segment:
            problems.append("the path carries a value the record's own redactor would replace")
            break
    if "@" in parts.netloc:
        problems.append("the host carries credentials")
    return problems


def candidate_of(investigation: dict[str, Any], candidate_id: str) -> dict[str, Any]:
    for candidate in investigation.get("api_candidates") or []:
        if candidate.get("id") == candidate_id:
            return candidate
    raise Refused(f"the record has no candidate '{candidate_id}'")


def candidate_request_matches(
    contract: dict[str, Any],
    method: Any,
    url: Any,
) -> bool:
    """Whether a recorded request asked this contract's endpoint.

    The endpoint alone is not enough: the record holds several candidates that share
    a path — the catalog search and the filters search are the same URL with a
    different `resultset`. So a request is this contract's when it asks the same
    method, the same endpoint and the same identity parameters.

    The endpoint and the method are the contract's, not the record's: the contract
    table is what the generated test pins, so a record that names another endpoint
    is refused before it reaches a fixture (see `generate`).

    An identity parameter has to occur exactly once. A request that names it twice
    says two different things about which request it is, and the reader that keeps
    the last value and the server that keeps the first would disagree about it.
    """
    text = str(url or "")
    if text.split("?", 1)[0] != str(contract.get("endpoint") or ""):
        return False
    if str(method or "").upper() != str(contract.get("method") or "GET").upper():
        return False
    pairs = query_pairs_of(text)
    for name, value in (contract.get("identity") or {}).items():
        if [seen for key, seen in pairs if key == name] != [value]:
            return False
    return True


def capture_matches_observation(capture: dict[str, Any], observation: dict[str, Any]) -> bool:
    """Whether the capture is the capture of this observation.

    The summary (`validation.yaml`) and the evidence bundle describe the same run
    twice. A bundle filed under the id of another observation — the same endpoint,
    another experiment — would put the products of one run into the fixture of
    another, and the fixture would look perfectly plausible. So the two descriptions
    have to agree on every dimension that tells one run from another: the
    experiment, the run, the method, the URL and the order of the answer.

    Every dimension has to be *there* on both sides. A dimension that is missing is
    not a dimension that matches: a capture that says nothing about the experiment it
    belongs to cannot be told apart from a capture of another experiment, and a
    bundle that has lost the order hash cannot be checked against the summary at all.
    So an incomplete capture is refused rather than accepted on the dimensions that
    happen to be present.
    """
    for name in ("experiment", "run"):
        left, right = capture.get(name), observation.get(name)
        if left is None or right is None or left != right:
            return False
    capture_request = capture.get("request") or {}
    observation_request = observation.get("request") or {}
    for name in ("method", "url"):
        left, right = capture_request.get(name), observation_request.get(name)
        if left is None or right is None or str(left) != str(right):
            return False
    capture_order = (capture.get("ordering") or {}).get("orderHash")
    observation_order = (observation.get("ordering") or {}).get("orderHash")
    return capture_order is not None and observation_order is not None and capture_order == observation_order


def baseline_observation(contract: dict[str, Any], experiments: list[dict[str, Any]]) -> dict[str, Any]:
    """The first observation of the baseline experiment of this contract.

    The baseline is the request the other experiments are compared with: it is the
    one the record replayed, so it is the one that says what the endpoint was asked
    for when nothing was changed.
    """
    wanted = str((contract.get("experiments") or {}).get("baseline") or "")
    for experiment in experiments:
        if str(experiment.get("name")) != wanted:
            continue
        observations = experiment.get("observations") or []
        if observations:
            return observations[0]
    raise Refused(f"the evidence has no observation of the '{wanted}' experiment")


def candidate_agrees_with_evidence(
    contract: dict[str, Any],
    candidate: dict[str, Any],
    observation: dict[str, Any],
) -> None:
    """Refuse a candidate that contradicts the evidence of its own contract.

    The candidate is the summary of the request the storefront sent, and the proposal
    publishes its query as the parameters of the contract. The evidence is the request
    that was actually made. When the two disagree, the proposal advertises one
    request and pins another: the Markdown says `resultset=filters` while the fixture
    and the test say `resultset=catalog`, and the reader of the proposal is left
    with two truths.

    So the candidate has to name the identity parameters the contract declares, with
    the values the contract declares, and its query has to be the query of the
    recorded request — the same parameters with the same values, in both directions.
    A parameter the candidate advertises and the request did not send is a parameter
    the evidence does not have; a parameter the request sent and the candidate does
    not name is a parameter the proposal's table leaves out while the fixture pins
    it. The comparison is against the baseline, because the baseline is the request
    the other experiments were compared with.

    A parameter the recorded request sends twice is refused before the comparison:
    read as a map, the second value hides the first, so the proposal would pin one
    request and the fixture another.
    """
    query = {str(name): str(value) for name, value in (candidate.get("query") or {}).items()}

    for name, value in (contract.get("identity") or {}).items():
        if query.get(str(name)) != str(value):
            raise Refused(
                f"the candidate's query holds '{name}={query.get(str(name))}', the contract "
                f"declares '{name}={value}' — the parameters that tell two requests of one "
                f"path apart are the contract, and a candidate that names another one is "
                f"not this contract"
            )

    recorded_pairs = query_pairs_of(str((observation.get("request") or {}).get("url") or ""))
    repeated = duplicate_parameter_problems(recorded_pairs)
    if repeated:
        raise Refused(
            f"the recorded request sends a parameter twice: {repeated} — the proposal would "
            f"advertise one request and pin another, because a query read as a map keeps only "
            f"the last value"
        )
    recorded = {name: value for name, value in recorded_pairs}

    # The storefront's own request is its first one: the contract declares the
    # parameters the crawler adds, and the storefront does not send them. A
    # baseline that carries one of them is not the storefront's first request —
    # and the generated test compares the crawler's request with the baseline's
    # one, so it would compare two different requests and fail on a record that
    # was accepted.
    crawler_only = sorted(set(recorded) & set(contract.get("crawler_only") or {}))
    if crawler_only:
        raise Refused(
            f"the storefront's own request carries {crawler_only} — the contract declares "
            f"{sorted(contract.get('crawler_only') or {})} as the parameters the crawler adds "
            f"and the storefront does not send, so this is not the storefront's first request"
        )

    for name, value in sorted(query.items()):
        if name not in recorded:
            raise Refused(
                f"the candidate's query holds '{name}', the recorded request did not send it — "
                f"the proposal would advertise a parameter the evidence does not have"
            )
        if str(recorded[name]) != value:
            raise Refused(
                f"the candidate's query holds '{name}={value}', the recorded request sent "
                f"'{name}={recorded[name]}' — the proposal would advertise one request and "
                f"pin another"
            )
    for name in sorted(recorded):
        if name not in query:
            raise Refused(
                f"the recorded request sent '{name}', the candidate's query does not name it — "
                f"the proposal's parameter table would omit a parameter of the request it pins"
            )


def answer_of_the_evidence(candidate: dict[str, Any], capture: dict[str, Any]) -> dict[str, Any]:
    """The answer the evidence recorded — and the refusal when the candidate disagrees.

    The proposal publishes three things about the answer: the status it came with,
    the content type it had and the keys its body carried. All three are the
    candidate's own summary, and a summary is written by hand — so it can name an
    answer the evidence never saw, or an answer of another contract.

    The evidence is the witness, so the summary has to agree with it before the
    summary is published. And the values that reach the proposal are the ones the
    capture holds: when the two agree they are the same value, and the capture is
    the one a reader can check.

    The types are asked for as well: a status that became the text `200` is not
    the status the request came with.
    """
    recorded = capture.get("response") or {}
    schema = capture.get("schema") or {}
    status = recorded.get("status")
    content_type = recorded.get("contentType")
    keys = schema.get("topLevelKeys")

    if not isinstance(status, int) or isinstance(status, bool):
        raise Refused(
            "the evidence records no status of the answer — the proposal publishes the status the "
            "request came with, and a status nobody recorded cannot be published"
        )
    if not isinstance(content_type, str) or not content_type:
        raise Refused(
            "the evidence records no content type of the answer — the proposal publishes the content "
            "type the answer had, and a content type nobody recorded cannot be published"
        )
    if not isinstance(keys, list) or not all(isinstance(key, str) for key in keys):
        raise Refused(
            "the evidence records no shape of the answer — the proposal publishes the keys the body "
            "carried, and a shape nobody recorded cannot be published"
        )

    if candidate.get("observed_status") != status:
        raise Refused(
            f"the candidate names the status '{candidate.get('observed_status')}', the evidence "
            f"recorded '{status}' — the proposal would publish an answer the record never saw"
        )
    if candidate.get("response_content_type") != content_type:
        raise Refused(
            f"the candidate names the content type '{candidate.get('response_content_type')}', the "
            f"evidence recorded '{content_type}' — the proposal would publish an answer the record "
            f"never saw"
        )
    named = (candidate.get("response_schema") or {}).get("top_level_keys")
    if named is not None:
        if not isinstance(named, list) or not all(isinstance(key, str) for key in named):
            raise Refused(
                f"the candidate names the keys {named!r} of the answer, which is not a list of names — "
                f"the keys of the body are names, and a name that is not a name is not a name"
            )
        if set(named) != set(keys):
            raise Refused(
                f"the candidate names the keys {sorted(named)} of the answer, the evidence recorded "
                f"{sorted(keys)} — the proposal would publish a shape the record never saw"
            )

    return {"status": status, "contentType": content_type, "topLevelKeys": list(keys)}


def claims_about_candidate(
    investigation: dict[str, Any],
    evidence: dict[str, dict[str, Any]],
    contract: dict[str, Any],
    observations: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    """The claims the candidate's own controlled experiments established.

    A record holds the claims of every endpoint it saw, so a claim in a validated
    state may be about another endpoint entirely — the card list, the antibot
    service, an analytics call. Two things tie a claim to this contract:

    - the claim names an observation of the candidate's controlled experiments, not
      an old capture of the page load;
    - that observation's capture asked the candidate's endpoint, and it is the
      capture of that observation and of no other.

    A capture of the right endpoint from the first page load is not enough on its
    own: it says what the storefront does, not what the controlled experiments
    established.
    """
    earned: list[dict[str, Any]] = []
    for claim in claims_of(investigation):
        if claim.get("state") not in VALIDATED_STATES:
            continue
        for observation_id in claim.get("evidence") or []:
            name = str(observation_id)
            observation = observations.get(name)
            if observation is None:
                continue
            capture = evidence.get(name)
            if capture is None:
                continue
            if not capture_matches_observation(capture, observation):
                continue
            request = capture.get("request") or {}
            if candidate_request_matches(contract, request.get("method"), request.get("url")):
                earned.append(claim)
                break
    return earned


def check_eligible(
    investigation: dict[str, Any],
    validation: dict[str, Any] | None,
    candidate_id: str,
    experiments: list[dict[str, Any]],
    earned: list[dict[str, Any]],
) -> None:
    """Refuse a record that has not earned a proposal."""
    if investigation.get("status") != "concluded":
        raise Refused(f"the record is '{investigation.get('status')}', not 'concluded'")

    if validation is None:
        raise Refused("the record has no validation.yaml — a contract needs controlled experiments, not one capture")

    if validation.get("candidate") != candidate_id:
        raise Refused(
            f"the validation belongs to the candidate '{validation.get('candidate')}', not to '{candidate_id}'"
        )

    if not experiments:
        raise Refused(
            f"no experiment of the candidate '{candidate_id}' reached a validated state — "
            f"a contract needs controlled experiments, not one capture"
        )

    if not earned:
        states = sorted({str(claim.get("state")) for claim in claims_of(investigation)})
        raise Refused(
            f"no claim about the candidate '{candidate_id}' reached a validated state in the "
            f"candidate's own experiments — the record holds {states or ['no claims at all']}"
        )

    if not any(str(claim.get("state")) == "validated" for claim in earned):
        raise Refused(
            f"no claim about the candidate '{candidate_id}' reached the `validated` state — a "
            f"`reproduced` claim is a fact the record earned, but a contract needs a claim that "
            f"a controlled experiment confirmed"
        )


def eligible_experiments(validation: dict[str, Any]) -> list[dict[str, Any]]:
    """The experiments whose state is a fact the record earned."""
    return [
        experiment
        for experiment in validation.get("experiments") or []
        if experiment.get("state") in VALIDATED_STATES and experiment.get("observations")
    ]


def observations_of(experiments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [observation for experiment in experiments for observation in experiment["observations"]]


def observation_ids(experiments: list[dict[str, Any]]) -> set[str]:
    """The ids of the observations of these experiments."""
    return {str(observation.get("id")) for observation in observations_of(experiments)}


def safe_observation(value: Any) -> str:
    """The id of an observation, checked to be a single safe file name.

    The id becomes a fixture file name. A name that carries a separator or a `..`
    would point at another file than the operator named — the containment check
    below would accept it as long as it stays inside the repository, and the
    repository holds files a generator must never rewrite.
    """
    text = str(value or "").strip()
    if not text or ".." in text or not OBSERVATION_NAME.fullmatch(text):
        raise Refused(f"the observation id must be a single name, not a path: {value!r}")
    return text


def evidence_index(directory: Path) -> dict[str, dict[str, Any]]:
    """Every capture of the investigation's evidence, by its id.

    The summary (`validation.yaml`) keeps the shape of the answer and a hash of the
    order; the evidence bundle keeps the order itself. The fixture needs both, so it
    reads both.
    """
    index: dict[str, dict[str, Any]] = {}
    evidence = directory / "evidence"
    if not evidence.is_dir():
        return index
    for path in sorted(evidence.glob("*.json")):
        with path.open(encoding="utf-8") as handle:
            bundle = json.load(handle)
        for capture in bundle.get("captures") or []:
            if isinstance(capture, dict) and capture.get("id"):
                name = str(capture["id"])
                if name in index:
                    raise Refused(
                        f"two evidence bundles carry the capture '{name}' — the record would "
                        f"have to say which of them is the one that was recorded"
                    )
                index[name] = capture
    return index


# --------------------------------------------------------------------------------------
# The contract
# --------------------------------------------------------------------------------------


def query_of(url: str) -> dict[str, str]:
    """The query of a URL, decoded — the request as it was made."""
    return dict(parse_qsl(url_parts(url).query, keep_blank_values=True))


def query_pairs_of(url: str) -> list[tuple[str, str]]:
    """The query of a URL as pairs — a repeated parameter stays two pairs.

    `query_of` collapses a repeated parameter into its last value, which is fine for
    reading a query and wrong for checking one: a request that names a parameter
    twice is a request that says two things about it.
    """
    return list(parse_qsl(url_parts(url).query, keep_blank_values=True))


def crawl_url_carries_search(crawl_url: str) -> bool:
    """Whether the crawl URL names the search parameter the crawler asks with.

    The crawler carries the catalog's token in the URL, and the request is built
    from that URL. A crawl URL without the parameter is a crawl without a token —
    the endpoint answers 200 with the unfiltered popular list — so the record has
    to name one before the proposal is written.
    """
    parts = url_parts(crawl_url)
    names = [chunk.split("=", 1)[0] for chunk in parts.query.split("&") if chunk]
    return "search" in names


def publishable_query_value(name: str, value: Any) -> bool:
    """Whether a query value may be committed as it is.

    The record's rule: a committed value is either a placeholder or a value the
    record's own redactor would leave alone. The generator publishes the query of the
    candidate in the proposal, so it has to hold the record to that rule — a session
    token, an account id or a search phrase that somebody typed would otherwise be
    committed in the Markdown of the proposal.

    The rule is the redactor's, not the generator's, and the name of the parameter is
    part of it. The shape of a value says little on its own: `123456789012` and
    `YWJjZGVmZ2hp` are short, they are made of ordinary characters, and they are a
    session id and a token all the same. The name says more: `session` is a credential
    whatever its value looks like, and `query` carries what somebody typed — `Alice` is
    a name and a phrase, and it is short too. So the value is judged by the name it was
    sent under, by the same rules the record used to sanitize it in the first place.

    The shape is checked as well, for what the redactor does not judge: a value with a
    space in it is a phrase (the redactor replaces those, but only when it reads the
    name), and a value that is longer than a parameter name and a number may be is not
    a value anybody types.
    """
    text = str(value if value is not None else "")
    if is_placeholder(text):
        return True
    if not SAFE_QUERY_VALUE.fullmatch(text):
        return False
    return str(REDACTOR.redact_query_value(name, text)) == text


def parameter_problems(contract: dict[str, Any], pairs: list[tuple[str, Any]]) -> list[str]:
    """Why these parameters may not be committed — one message per parameter.

    The contract declares the parameters of its request and the values they may
    hold; the record's redactor judges a value by the name it was sent under. The
    two rules answer two different questions, and both are needed: the redactor
    knows that `session` is a credential whatever its value looks like, and only the
    contract knows that `customer` is not a parameter of this request at all — a
    name nobody can explain may carry somebody's name, and the redactor has no rule
    for a name it has never seen.

    So a parameter is publishable when the contract declares it, the record's
    redactor would leave its value alone (or the value is the placeholder that
    stands for what it redacted), and the value is one the contract allows.
    """
    problems: list[str] = []
    declared = contract.get("parameters") or {}
    problems += duplicate_parameter_problems(pairs)
    for name, value in pairs:
        rule = declared.get(name)
        if not publishable_query_value(name, value):
            problems.append(f"{name} holds a value the record did not sanitize")
            continue
        if not isinstance(rule, dict):
            problems.append(f"{name} is not a parameter of this contract")
            continue
        text = str(value if value is not None else "")
        if "value" in rule:
            allowed = text == str(rule["value"])
        elif "values" in rule:
            allowed = text in {str(item) for item in rule["values"]}
        elif "range" in rule:
            allowed = within_range(text, rule["range"])
        elif rule.get("free_text"):
            allowed = is_placeholder(text)
        else:
            allowed = False
        if not allowed:
            problems.append(f"{name} holds '{text}', which the contract does not allow")
    return problems


def within_range(text: str, bounds: Any) -> bool:
    """Whether a value is a number within the bounds the contract declares.

    The bounds are the endpoint's own: the API answers sixty pages, so a page
    beyond that is a request the endpoint never answers with goods. A value that
    is not a number at all is not within any range — the shape of a number is
    not a number.
    """
    if not isinstance(bounds, dict):
        return False
    if not text.isdigit():
        return False
    number = int(text)
    low = bounds.get("min")
    high = bounds.get("max")
    if low is not None and number < int(low):
        return False
    return high is None or number <= int(high)


def duplicate_parameter_problems(pairs: list[tuple[str, Any]]) -> list[str]:
    """Why a parameter that is sent twice may not be committed.

    A parameter that occurs twice says two different things about the request,
    and the reader that keeps the last value and the server that keeps the first
    would disagree about it. The comparison that reads the query as a map cannot
    see the difference at all: it keeps the last value and loses the first, so a
    request that sends `sort=popular&sort=priceup` would be compared as if it
    had sent one of them. So the repetition is refused before anything is read
    as a map.
    """
    seen: set[str] = set()
    repeated: set[str] = set()
    for name, _ in pairs:
        if name in seen:
            repeated.add(name)
        seen.add(name)
    return [f"{name} is sent twice — a parameter sent twice says two different things" for name in sorted(repeated)]


def query_problems(contract: dict[str, Any], url: str) -> list[str]:
    """The parameters of a URL that may not be committed."""
    return parameter_problems(contract, list(parse_qsl(url_parts(url).query, keep_blank_values=True)))


def fixture_dir_of(candidate_id: str) -> str:
    """The directory the fixtures of one contract live in.

    The fixtures belong to the contract, so the directory does too: a directory
    shared by two contract families would make one proposal's inventory the
    other's, and the drift gate would report the neighbour's fixtures as
    leftovers.
    """
    return f"{FIXTURE_ROOT}/{candidate_id}"


def header_names_of(observation: dict[str, Any]) -> list[str]:
    request = observation.get("request") or {}
    return list(request.get("headerNames") or [])


def product_ids_of(observation: dict[str, Any], capture: dict[str, Any]) -> list[int]:
    """The ids of the products, in the order the answer carried them.

    The summary keeps the hash of the order; the evidence keeps the order. Both are
    read, and the evidence wins when both have it.
    """
    for source in (capture, observation):
        ordering = source.get("ordering") or {}
        ids = ordering.get("productIds")
        if ids:
            return list(ids)
    return []


def fixture_of(
    observation: dict[str, Any],
    capture: dict[str, Any],
    candidate_id: str,
    investigation_id: str,
) -> dict[str, Any]:
    request = observation.get("request") or {}
    response = observation.get("response") or {}
    answer = observation.get("schema") or {}
    ordering = observation.get("ordering") or {}

    return {
        "schema": "wb-research/contract-fixture/v1",
        "investigation": investigation_id,
        "candidate": candidate_id,
        "experiment": observation.get("experiment"),
        "observation": observation.get("id"),
        "run": observation.get("run"),
        "evidence": [observation.get("id")],
        "request": {
            "method": request.get("method"),
            "url": request.get("url"),
            "query": query_of(request.get("url") or ""),
            "headerNames": list(request.get("headerNames") or []),
        },
        "response": {
            "status": response.get("status"),
            "contentType": response.get("contentType"),
            "bodyBytes": response.get("bodyBytes"),
            "isJson": response.get("isJson"),
        },
        "answer": {
            "topLevelKeys": list(answer.get("topLevelKeys") or []),
            "productCount": answer.get("productCount"),
            "total": answer.get("total"),
        },
        "ordering": {
            "productIds": product_ids_of(observation, capture),
            "orderHash": ordering.get("orderHash"),
        },
    }


# --------------------------------------------------------------------------------------
# The generated Kotlin
# --------------------------------------------------------------------------------------

KOTLIN_TEST = """package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.json.wbJson
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

// A placeholder is the whole value the redactor left behind. A value that begins
// with one and goes on is not a placeholder but a value nobody judged — so the
// pattern is the generator's own, and the test asks the same question the
// generator asks.
private val REDACTED_VALUE = Regex("%%REDACTED_PATTERN%%")

/**
 * The seller-catalog contract, pinned to the evidence of one investigation.
 *
 * Generated by `scripts/wb-research/generate_proposal.py` from the investigation
 * `%%INVESTIGATION%%` (candidate `%%CANDIDATE%%`). Do not edit by hand: the file is
 * regenerated from the record, and the record is the evidence.
 *
 * ## What is pinned here
 *
 * Every assertion is a fact the investigation earned against the live endpoint, and
 * every fact is read from a fixture that was sanitized when it was recorded:
 *
 * - the request the storefront sends — its parameters and its header names;
 * - the shape of the answer — its top-level keys, its product count and its total;
 * - the order of the products, and the fact that a replay answers the same order.
 *
 * ## Why a fixture carries no body
 *
 * The capture kept the shape of the answer and the ids of the products, not the
 * payload — a payload is where a price, a name and a session live. The body the test
 * decodes is reconstructed from the recorded shape, so a fixture can never carry
 * something that was captured.
 *
 * ## Why it is worth having
 *
 * The endpoint answers 200 with a plausible page of products when the request is
 * wrong in a way nobody notices — a different `resultset`, a dropped `sort`, a page
 * that is not the page that was asked for. A crawl then stores the wrong goods and
 * reports success. These are the assertions that fail quietly when they are wrong.
 */
class %%TEST_CLASS%% :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        /** The token the crawler asks with; the record redacted the one it saw. */
        val token = "%%TOKEN%%"

        /**
         * The crawl URL of the record — the page the crawl starts from.
         *
         * The record redacted the token it saw, so this URL carries a placeholder.
         */
        val crawlUrl = "%%CRAWL_URL%%"

        /** The catalog the record crawled. */
        val catalogId = "%%CATALOG_ID%%"

        /**
         * The crawl URL the crawler asks with — the URL the crawler itself builds
         * for this catalog and this token.
         *
         * The record redacted the token it saw, so the crawl URL of the record holds a
         * placeholder. At runtime the crawl starts from the URL that carries the real
         * token, and the crawler builds it: the request is built from that URL, exactly
         * as the crawler builds it.
         */
        val runtimeCrawlUrl = WbCatalogRequest.crawlUrlOf(catalogId, token)

        /** The parameters the crawler sends and the storefront did not. */
        val crawlerOnly = setOf(%%CRAWLER_ONLY%%)

        /**
         * The values the crawler sends for those parameters, where the value is fixed.
         *
         * The storefront did not send them, so the record holds no value for them —
         * the crawler's own contract holds it, and a change of it is a change of the
         * contract. The page is not here: its value is the page the crawler asks for.
         */
        val crawlerOnlyValues = mapOf(%%CRAWLER_ONLY_VALUES%%)

        /** The parameters the storefront sent and the crawler does not. */
        val storefrontOnly = setOf(%%STOREFRONT_ONLY%%)

        fun fixture(name: String): ContractFixture {
            val stream =
                javaClass.getResourceAsStream("/fixtures/catalog-search/%%CANDIDATE%%/$name.json")
                    ?: error("the fixture $name is not on the classpath")
            return json.decodeFromString(stream.readBytes().decodeToString())
        }

        /** The query of a URL, decoded — the parameters of the request itself. */
        fun queryOf(url: String): Map<String, String> =
            url
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val name = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    name to URLDecoder.decode(value, "UTF-8")
                }

        fun fetched(body: String): Fetched {
            val url = CrawlUrl.of("https://www.wildberries.ru/catalog/131954/list.aspx").getOrNull()!!
            return Fetched(
                task = Crawling("task-1", url, 0, UUID.randomUUID()),
                statusCode = CrawlHttpStatusCode(200),
                body = body,
                headers = emptyMap(),
                durationMs = 10L,
            )
        }

        /**
         * The body of a fixture — rebuilt from the recorded shape, never captured.
         *
         * The record kept the shape of the answer and the order of the products, not
         * the payload: a payload is where a price, a name and a session live. So the
         * body is assembled here, from the shape the fixture carries and the ids it
         * names — the fixture holds the order once, and the body follows it.
         */
        fun bodyOf(fixture: ContractFixture): String {
            require("products" in fixture.answer.topLevelKeys) {
                "the fixture ${fixture.observation} recorded an answer without products"
            }
            val body =
                buildJsonObject {
                    if ("metadata" in fixture.answer.topLevelKeys) {
                        put(
                            "metadata",
                            buildJsonObject {
                                put("catalog_type", "catalog-merger")
                                put("name", "<redacted:query-param>")
                            },
                        )
                    }
                    if ("total" in fixture.answer.topLevelKeys) {
                        put("total", fixture.answer.total ?: 0)
                    }
                    put(
                        "products",
                        buildJsonArray {
                            fixture.ordering.productIds.forEach { id ->
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("name", "Товар $id")
                                    },
                                )
                            }
                        },
                    )
                }
            return body.toString()
        }

        val baseline = fixture("%%BASELINE%%")
        val baselineReplay = fixture("%%BASELINE_REPLAY%%")
        val sorted = fixture("%%SORTED%%")
        val sortedReplay = fixture("%%SORTED_REPLAY%%")
        val nextPage = fixture("%%NEXT_PAGE%%")
        val nextPageReplay = fixture("%%NEXT_PAGE_REPLAY%%")
        val reload = fixture("%%RELOAD%%")
        val reloadReplay = fixture("%%RELOAD_REPLAY%%")

        // The request is built from the crawl URL, exactly as the crawler builds it:
        // the URL is parsed, and the token it carries becomes the query of the request.
        // A literal here would pin a request nobody sends.
        val parsedCrawl = WbCatalogRequest.parseCatalogUrl(runtimeCrawlUrl)
        val productionUrl =
            WbCatalogApi.buildWbApiUrl(
                page = parsedCrawl.page,
                searchQuery = parsedCrawl.searchQuery,
            )
        val productionQuery = queryOf(productionUrl)
        val productionHeaders =
            WbCatalogApi
                .buildWbHeaders()
                .keys
                .map { it.lowercase() }
                .toSet()

        test("the crawl URL of the crawler is the record's URL with the crawler's token in it") {
            // The record redacted the token it saw, so the record's URL and the
            // crawler's URL differ in one parameter — and in nothing else. A token
            // that changed the page would be a crawl of another catalog.
            val recorded = URI(crawlUrl)
            val runtime = URI(runtimeCrawlUrl)

            recorded.scheme shouldBe runtime.scheme
            recorded.host shouldBe runtime.host
            recorded.path shouldBe runtime.path
        }

        test("the search token of the crawl URL reaches the request") {
            // The chain the crawler walks: crawl URL → parsed page → request. The
            // token of the URL is the identity of the catalog, and a request that
            // carried another token would ask for another catalog's goods while
            // answering 200 with plausible ones.
            parsedCrawl.catalogId shouldBe catalogId
            parsedCrawl.searchQuery shouldBe token
            productionQuery["query"] shouldBe token
        }

        test("the request goes to the endpoint the record observed") {
            // The path is as much a part of the contract as the parameters are: the
            // wrong endpoint answers 200 with somebody else's products. The recorded
            // query is redacted, so only the place the request goes to is compared.
            val production = URI(productionUrl)
            val recorded = URI(baseline.request.url)

            production.scheme shouldBe recorded.scheme
            production.host shouldBe recorded.host
            production.path shouldBe recorded.path
            production.port shouldBe recorded.port
        }

        test("the request carries every parameter of the contract — and only those") {
            // The set is the contract. A parameter the record does not name is one the
            // crawler invented; a parameter of the record the crawler does not send is
            // one that was dropped. Both fail quietly: the endpoint answers 200 with
            // somebody else's products.
            productionQuery.keys shouldBe baseline.request.query.keys + crawlerOnly - storefrontOnly
        }

        test("the parameters the crawler and the storefront share carry the same values") {
            baseline.request.query.forEach { (name, value) ->
                // The search token was redacted in the record: only its name can be
                // compared, its value is the crawler's own token. The storefront-only
                // parameters are not compared at all — the crawler does not send them.
                if (REDACTED_VALUE.matches(value) || name in storefrontOnly) return@forEach
                productionQuery[name] shouldBe value
            }
        }

        test("the parameters the crawler adds carry the values the crawler declares") {
            // The storefront did not send these parameters, so the record holds no value
            // for them. The crawler's own contract holds it — and a value that changes
            // without anybody deciding it is a crawl that quietly collects other goods.
            crawlerOnlyValues.forEach { (name, value) ->
                productionQuery[name] shouldBe value
            }
        }

        test("the crawler asks for the page it wants") {
            // The first request asks for the first page, and the next one for the
            // second: the page is the crawler's own, and it is a value like any other.
            productionQuery["page"] shouldBe "1"
        }

        test("the request carries exactly the headers the contract names") {
            productionHeaders shouldBe
                baseline.request.headerNames
                    .map { it.lowercase() }
                    .toSet()
        }

        test("no header of the request is one the browser forbids") {
            // `cookie`, `user-agent` and `referer` are the browser's, and `sec-*` is a
            // family of them. A value passed from page JavaScript is dropped without an
            // error, so a header here is a header WB never saw.
            val forbidden = baseline.request.headerNames.map { it.lowercase() }
            forbidden.none { it == "cookie" || it == "user-agent" || it == "referer" } shouldBe true
            forbidden.none { it.startsWith("sec-") } shouldBe true
        }

        test("the answer of the fixture decodes into the products it recorded") {
            val envelope = wbJson.decodeFromString<WbCatalogEnvelope>(bodyOf(baseline))

            val products = envelope.products ?: error("the fixture's answer carries no products")
            products.size shouldBe baseline.answer.productCount
            envelope.total shouldBe baseline.answer.total
            products.map { it.id } shouldBe baseline.ordering.productIds
        }

        test("every product of the fixture maps into the domain model") {
            val page =
                parseWbCatalog(fetched(bodyOf(baseline))).fold(
                    { error -> error("the answer was refused: ${error.message}") },
                    { it },
                )

            page.items.map { it.productId.value } shouldBe baseline.ordering.productIds
        }

        test("the answer of a replay is the same answer") {
            // The record replayed the request and the answer came back byte for byte.
            // An endpoint that answered a different order would make the crawler's
            // pagination a walk over a moving target.
            baselineReplay.ordering.orderHash shouldBe baseline.ordering.orderHash
            baselineReplay.ordering.productIds shouldBe baseline.ordering.productIds
            baselineReplay.answer.total shouldBe baseline.answer.total
        }

        test("the next page is the same request with the page parameter incremented") {
            // The page is not a literal here: the crawl URL names it, the URL algebra
            // advances it and the request builder reads it back. A pagination that
            // stopped incrementing would ask for the first page forever, and the
            // endpoint would answer 200 with the same products every time.
            val next = WbCatalogRequest.nextPageUrl(runtimeCrawlUrl)!!
            val parsed = WbCatalogRequest.parseCatalogUrl(next)
            parsed.page shouldBe 2
            parsed.catalogId shouldBe catalogId
            parsed.searchQuery shouldBe token

            val page = queryOf(WbCatalogApi.buildWbApiUrl(page = parsed.page, searchQuery = parsed.searchQuery))
            page["page"] shouldBe "2"
            page["query"] shouldBe token
            page.keys shouldBe productionQuery.keys
            nextPage.request.query["page"] shouldBe "2"
        }

        test("the next page is another window of the same catalog") {
            // The same total, another hundred products: the pagination moves the window,
            // it does not change the catalog.
            nextPage.answer.total shouldBe baseline.answer.total
            nextPage.ordering.productIds shouldNotBe baseline.ordering.productIds
            nextPageReplay.ordering.orderHash shouldBe nextPage.ordering.orderHash
        }

        test("the sort parameter changes the order of the answer") {
            sorted.request.query["sort"] shouldBe "priceup"
            sorted.ordering.productIds shouldNotBe baseline.ordering.productIds
            sortedReplay.ordering.orderHash shouldBe sorted.ordering.orderHash
        }

        test("the crawler asks for the order the storefront asks for") {
            // The storefront's own request carried `sort=popular`, and the crawler's
            // request is the one the record describes.
            productionQuery["sort"] shouldBe baseline.request.query["sort"]
            productionQuery["resultset"] shouldBe baseline.request.query["resultset"]
        }

        test("a reload does not change the answer") {
            reload.ordering.orderHash shouldBe baseline.ordering.orderHash
            reload.ordering.productIds shouldBe baseline.ordering.productIds
            reloadReplay.ordering.orderHash shouldBe reload.ordering.orderHash
        }
    })

/**
 * One sanitized observation of the contract — the request as it was made, the shape of
 * the answer and the order of the products.
 */
@Serializable
private data class ContractFixture(
    val schema: String,
    val investigation: String,
    val candidate: String,
    val experiment: String,
    val observation: String,
    val run: Int,
    val evidence: List<String>,
    val request: FixtureRequest,
    val response: FixtureResponse,
    val answer: FixtureAnswer,
    val ordering: FixtureOrdering,
)

@Serializable
private data class FixtureRequest(
    val method: String,
    val url: String,
    val query: Map<String, String>,
    val headerNames: List<String>,
)

@Serializable
private data class FixtureResponse(
    val status: Int,
    val contentType: String,
    val bodyBytes: Int,
    val isJson: Boolean,
)

@Serializable
private data class FixtureAnswer(
    val topLevelKeys: List<String>,
    val productCount: Int?,
    val total: Int?,
)

@Serializable
private data class FixtureOrdering(
    val productIds: List<Long>,
    val orderHash: String,
)
"""


def kotlin_set(values: list[str]) -> str:
    return ", ".join(f'"{value}"' for value in values)


def kotlin_map(values: dict[str, str]) -> str:
    return ", ".join(f'"{name}" to "{value}"' for name, value in sorted(values.items()))


def render_kotlin(
    contract: dict[str, Any],
    investigation_id: str,
    candidate_id: str,
    fixtures: dict[str, list[str]],
    validation: dict[str, Any],
) -> str:
    """The Kotlin test, with the contract's names and the evidence's fixture names."""
    experiments = contract["experiments"]

    # The crawl URL of the record: the pagination of the contract is the pagination of
    # this URL, so the test walks the production URL algebra instead of a literal.
    crawl_url = str(validation.get("page") or "").strip()
    if not crawl_url:
        raise Refused("the record names no crawl URL — the page the crawl starts from is part of the contract")
    catalog = re.search(r"/catalog/(\d+)", crawl_url)
    if catalog is None:
        raise Refused(f"the crawl URL '{crawl_url}' carries no catalog id")
    # The crawl URL of the record has to be the crawler's own URL for that catalog:
    # the test builds the runtime URL from the catalog id, and a page of another shape
    # would be a page the crawler never asks with.
    expected_crawl_url = str(contract.get("crawl_url") or "").format(catalog_id=catalog.group(1))
    if not expected_crawl_url or crawl_url.split("?", 1)[0] != expected_crawl_url:
        raise Refused(
            f"the crawl URL '{crawl_url}' is not the crawler's URL for the catalog "
            f"'{catalog.group(1)}' — the contract declares '{expected_crawl_url}'"
        )
    if not crawl_url_carries_search(crawl_url):
        raise Refused(
            f"the crawl URL '{crawl_url}' names no search parameter — the crawler carries "
            f"the catalog's token in the URL, and a crawl without one asks for the "
            f"unfiltered popular list"
        )
    # The crawl URL is committed in the test, so its values have to hold to the
    # record's sanitization rule: a placeholder or a value that carries no secret
    # shape, under a name the contract declares.
    unsafe = query_problems(contract, crawl_url)
    if unsafe:
        raise Refused(
            f"the crawl URL holds parameters the contract does not allow to be committed: "
            f"{unsafe} — the URL is committed in the test, so a committed parameter is one "
            f"the contract declares and one the record's own redactor would leave alone"
        )

    def names(role: str) -> list[str]:
        if role not in fixtures:
            raise Refused(f"the evidence has no '{experiments.get(role)}' experiment")
        return fixtures[role]

    # Every role the contract names must be present in the evidence, even where the
    # test below only needs one of the two observations of it.
    for role in ("baseline", "sorted", "next-page", "reload"):
        names(role)

    def first_and_replay(role: str) -> tuple[str, str]:
        pair = names(role)
        if len(pair) < 2:
            raise Refused(f"the '{experiments.get(role)}' experiment was run once — nothing to replay")
        return pair[0], pair[1]

    baseline_first, baseline_replay = first_and_replay("baseline")
    sorted_first, sorted_replay = first_and_replay("sorted")
    next_first, next_replay = first_and_replay("next-page")
    reload_first, reload_replay = first_and_replay("reload")

    replacements = {
        "%%INVESTIGATION%%": investigation_id,
        "%%CANDIDATE%%": candidate_id,
        "%%TEST_CLASS%%": contract["test_class"],
        "%%TOKEN%%": SEARCH_TOKEN,
        "%%CRAWL_URL%%": crawl_url,
        "%%CATALOG_ID%%": catalog.group(1),
        "%%REDACTED_PATTERN%%": PLACEHOLDER.pattern,
        "%%CRAWLER_ONLY%%": kotlin_set(sorted(contract["crawler_only"])),
        "%%CRAWLER_ONLY_VALUES%%": kotlin_map(
            {
                name: str(spec["value"])
                for name, spec in contract["crawler_only"].items()
                if spec.get("value") is not None
            }
        ),
        "%%STOREFRONT_ONLY%%": kotlin_set(sorted(contract["storefront_only"])),
        "%%BASELINE%%": baseline_first,
        "%%BASELINE_REPLAY%%": baseline_replay,
        "%%SORTED%%": sorted_first,
        "%%SORTED_REPLAY%%": sorted_replay,
        "%%NEXT_PAGE%%": next_first,
        "%%NEXT_PAGE_REPLAY%%": next_replay,
        "%%RELOAD%%": reload_first,
        "%%RELOAD_REPLAY%%": reload_replay,
    }

    rendered = KOTLIN_TEST
    for token, value in replacements.items():
        rendered = rendered.replace(token, value)
    leftover = re.findall(r"%%[A-Z_]+%%", rendered)
    if leftover:
        raise Refused(f"the template still carries {sorted(set(leftover))}")
    return rendered


# --------------------------------------------------------------------------------------
# The proposal
# --------------------------------------------------------------------------------------


def render_proposal(
    investigation: dict[str, Any],
    validation: dict[str, Any],
    candidate: dict[str, Any],
    contract: dict[str, Any],
    candidate_id: str,
    investigation_id: str,
    fixtures: dict[str, list[str]],
    generated_files: list[str],
    earned: list[dict[str, Any]],
    answer: dict[str, Any],
) -> str:
    validated = validated_claims(investigation)
    wanted = set(contract["experiments"].values())
    experiments = [experiment for experiment in eligible_experiments(validation) if experiment.get("name") in wanted]
    about = {str(claim.get("claim")) for claim in earned}
    evidence_of = {observation["id"] for observation in observations_of(experiments)}

    # Every claim of the record is published — the validated ones in the table of
    # traceability, the others in the table of what the generator held back. A
    # claim that carries personal data or a captured secret is therefore refused
    # before the first word of the proposal is written.
    for claim in claims_of(investigation):
        problems = claim_problems(claim)
        if problems:
            raise Refused(
                f"a claim of the record in the state '{claim.get('state')}' carries something the "
                f"record's own redactor would replace: {problems} — the proposal publishes the "
                f"claim, so a claim with personal data in it is a file that must not be written"
            )

    lines: list[str] = []
    lines.append(f"# Proposal — `{candidate_id}`")
    lines.append("")
    lines.append(
        f"Generated by `scripts/wb-research/generate_proposal.py` from the investigation "
        f"`{investigation_id}`. Nothing here is merged by the tool: the tool writes files, "
        f"and a human reads the diff."
    )
    lines.append("")
    lines.append("## The contract")
    lines.append("")
    lines.append(f"- **Endpoint**: `{candidate.get('endpoint')}`")
    lines.append(f"- **Method**: `{candidate.get('method')}`")
    lines.append(f"- **Answer**: `{answer['contentType']}`, top-level keys `{', '.join(answer['topLevelKeys'])}`")
    lines.append(f"- **Observed status**: `{answer['status']}`")
    lines.append("")
    lines.append("### Parameters")
    lines.append("")
    lines.append("| Parameter | Value |")
    lines.append("| --- | --- |")
    for name, value in (candidate.get("query") or {}).items():
        lines.append(f"| `{name}` | `{value}` |")
    lines.append("")
    lines.append("### Headers")
    lines.append("")
    lines.append("Only the names are part of the contract: a value that was captured is never committed.")
    lines.append("")
    lines.append("| Header | The storefront's request | The validated replay |")
    lines.append("| --- | --- | --- |")
    storefront_headers = list(candidate.get("headers") or {})
    replay_headers = header_names_of(observations_of(experiments)[0])
    for name in sorted(set(storefront_headers) | set(replay_headers)):
        lines.append(
            f"| `{name}` | {'yes' if name in storefront_headers else 'no'} "
            f"| {'yes' if name in replay_headers else 'no'} |"
        )
    lines.append("")
    lines.append(
        "The generated test pins the crawler's header set to the replay's set, so a header that "
        "appears or disappears is a failing test rather than a silent change. The headers the "
        "storefront sent and the replay did not are named here because the difference has to be "
        "somebody's decision: a browser-controlled header (`referer`) is not an "
        "application-controlled one, and the record does not say why `x-queryid` was not replayed."
    )
    lines.append("")
    lines.append("## Traceability — claim → evidence → assertion")
    lines.append("")
    lines.append(
        "The table lists the claims the record proves in a validated state. A claim is *about "
        "this contract* when the candidate's own controlled experiments established it — the "
        "claim names one of their observations, and that observation asked this endpoint. That "
        "is what the eligibility rule counts, and it is why a validated claim about another "
        "endpoint, or one that only the first page load supports, is not enough."
    )
    lines.append("")
    lines.append("| Claim | State | About this contract | Evidence | Where it is pinned |")
    lines.append("| --- | --- | --- | --- | --- |")
    for claim in validated:
        evidence = ", ".join(f"`{item}`" for item in (claim.get("evidence") or []))
        seen = "yes" if str(claim.get("claim")) in about else "no"
        pinned = f"`{TEST_FILE}`" if seen == "yes" else "—"
        lines.append(f"| {claim.get('claim')} | `{claim.get('state')}` | {seen} | {evidence} | {pinned} |")
    lines.append("")
    lines.append(
        "The fixtures carry the evidence itself: the request as it was made, the shape of the "
        "answer and the order of the products. The body is reconstructed from that shape — no "
        "captured payload enters the repository."
    )
    lines.append("")
    lines.append("## The files this proposal adds")
    lines.append("")
    for path in generated_files:
        lines.append(f"- `{path}`")
    lines.append("")
    lines.append("## What the repository already implements")
    lines.append("")
    lines.append(
        "The endpoint, the parameters and the shape of the answer are the ones the crawler "
        "already knows — `WbCatalogApi` builds the request, `WbCatalogRequest` owns the "
        "pagination and `WbCatalogParser` turns the answer into items. The proposal therefore "
        "adds no second adapter: it pins the existing one to the evidence, so a change in the "
        "crawler and a change in the site cannot drift apart unnoticed."
    )
    lines.append("")
    lines.append("## What the generator did not generate")
    lines.append("")
    lines.append(
        "The gate is the point of the tool, so what it held back is part of the proposal: a "
        "claim that did not reach a validated state is not an assertion, and a candidate the "
        "record did not validate is not a contract."
    )
    lines.append("")
    unearned = [claim for claim in claims_of(investigation) if claim.get("state") not in VALIDATED_STATES]
    if unearned:
        lines.append("| Claim | State | Evidence |")
        lines.append("| --- | --- | --- |")
        for claim in unearned:
            evidence = ", ".join(f"`{item}`" for item in (claim.get("evidence") or []))
            lines.append(f"| {claim.get('claim')} | `{claim.get('state')}` | {evidence} |")
        lines.append("")
    others = [
        candidate for candidate in investigation.get("api_candidates") or [] if candidate.get("id") != candidate_id
    ]
    if others:
        lines.append(
            f"The record names {len(others)} other candidate(s). The validation is of "
            f"`{candidate_id}` alone, so none of them became a contract:"
        )
        lines.append("")
        for candidate in others:
            lines.append(f"- `{candidate.get('id')}` — `{published_endpoint(str(candidate.get('endpoint') or ''))}`")
        lines.append("")
    lines.append("## Known differences from the storefront's request")
    lines.append("")
    for name, spec in contract["crawler_only"].items():
        value = spec.get("value")
        sent = f" with the value `{value}`" if value is not None else ""
        lines.append(f"- **`{name}`** — the crawler sends it{sent}, the storefront did not: {spec['reason']}.")
    for name, reason in contract["storefront_only"].items():
        lines.append(f"- **`{name}`** — the storefront sent it, the crawler does not: {reason}.")
    lines.append("")
    lines.append("## What the reviewer has to look at")
    lines.append("")
    lines.append("1. The fixtures: are the recorded facts the ones the crawler must hold to?")
    lines.append("2. The known differences above: are they facts, or guesses that should be in the record?")
    lines.append("3. The assertions: does each one fail *loudly* when the contract changes?")
    lines.append("4. `./check.sh` — the module tests, the linters and the gates must pass before this is committed.")
    lines.append("")
    lines.append("## The evidence behind this proposal")
    lines.append("")
    for observation_id in sorted(evidence_of):
        lines.append(f"- `{observation_id}`")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes()).hexdigest()


def sha256_of_text(content: str) -> str:
    """The hash of the bytes a file will hold when it is written.

    The manifest is written from the content the generator is about to write, not
    from the files it has already written: the proposal is rendered in full before
    anything reaches the disk, so the hashes are of the same bytes the disk will
    hold.
    """
    import hashlib

    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def safe_investigation(value: Any) -> str:
    """The id of an investigation, checked to be a single safe path component.

    The id names a directory in the repository and a directory in the proposal set.
    A name that carries a separator or a `..` would point at another directory than
    the operator named, so it is refused before any file is read or written.
    """
    text = str(value or "").strip()
    if not text or ".." in text or not INVESTIGATION_NAME.fullmatch(text):
        raise Refused(f"the investigation id must be a single name, not a path: {value!r}")
    return text


def existing_proposal_for(output_root: Path, candidate_id: str, investigation_id: str) -> str | None:
    """The investigation that already proposed this contract, if there is one.

    The test and the fixtures belong to the *contract*, not to the investigation that
    found it: two investigations of one contract would write the same files, and the
    second one would silently invalidate the first one's proposal. So one contract has
    one proposal, and a new investigation that supersedes it says so by removing the
    old proposal first.
    """
    proposals = output_root / PROPOSAL_DIR
    if not proposals.is_dir():
        return None
    for manifest_path in sorted(proposals.glob("*/manifest.json")):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if not isinstance(manifest, dict):
            continue
        if manifest.get("candidate") != candidate_id:
            continue
        owner = str(manifest.get("investigation") or "")
        if owner and owner != investigation_id:
            return owner
    return None


def contract_problems(contract: dict[str, Any]) -> list[str]:
    """Why a contract declaration is not consistent with itself.

    The table answers three questions about the parameters of the request — which
    request this is, who sends the parameter, and what value it may hold — and the
    answers are kept apart on purpose. But they answer about the same names: a name
    that one declaration names and another does not is a parameter the contract
    cannot judge, and a parameter the contract declares without a rule is a
    parameter whose value nobody may publish.

    The check is on the table, not on the record: the table is the tool's own
    declaration, and a declaration that contradicts itself is a bug of the tool.
    """
    problems: list[str] = []
    declared = contract.get("parameters") or {}
    for section in ("identity", "crawler_only", "storefront_only"):
        for name in contract.get(section) or {}:
            if name not in declared:
                problems.append(f"'{name}' is named as {section} but no value is declared for it")
    for name, spec in declared.items():
        if not isinstance(spec, dict):
            problems.append(f"'{name}' is declared as a parameter of the request but declares no rule")
            continue
        if not any(key in spec for key in ("value", "values", "range")) and not spec.get("free_text"):
            problems.append(
                f"'{name}' is declared as a parameter of the request but no rule says what its value may be"
            )
    return problems


def generate(
    investigation_id: str,
    output_root: Path,
    investigations_root: Path = INVESTIGATIONS,
) -> list[Path]:
    """Write the proposal of one investigation. Returns the files it wrote."""
    investigation_id = safe_investigation(investigation_id)
    directory = investigations_root / investigation_id
    if not directory.is_dir():
        raise Refused(f"there is no investigation '{investigation_id}' in {investigations_root}")

    investigation = load_yaml(directory / "investigation.yaml") or {}
    validation_path = directory / "validation.yaml"
    validation = load_yaml(validation_path) if validation_path.is_file() else None

    if validation is None:
        raise Refused("the record has no validation.yaml — a contract needs controlled experiments, not one capture")

    candidate_id = str(validation.get("candidate") or "").strip()
    if not candidate_id:
        raise Refused("the validation.yaml names no candidate — the record does not say which contract it validated")

    if candidate_id not in CONTRACTS:
        raise Refused(f"this tool does not know the contract '{candidate_id}' — it knows {sorted(CONTRACTS)}")
    contract = CONTRACTS[candidate_id]

    # The table answers three questions about the parameters of the request, and the
    # answers are kept apart on purpose — but they answer about the same names. A
    # name one declaration names and another does not is a parameter the contract
    # cannot judge, so the table is checked against itself before it is used.
    inconsistent = contract_problems(contract)
    if inconsistent:
        raise Refused(f"the contract '{candidate_id}' is not consistent with itself: {'; '.join(inconsistent)}")

    candidate = candidate_of(investigation, candidate_id)
    evidence = evidence_index(directory)

    # The contract table is what the generated test pins, so it is the authority on the
    # endpoint: a record that names another endpoint is not this contract, however
    # convincing its evidence is.
    if str(candidate.get("endpoint") or "") != str(contract.get("endpoint") or ""):
        raise Refused(
            f"the candidate '{candidate_id}' names the endpoint '{candidate.get('endpoint')}', "
            f"the contract declares '{contract.get('endpoint')}' — the contract is what the "
            f"generated test pins, so the two have to be the same request"
        )
    if str(candidate.get("method") or "GET").upper() != str(contract.get("method") or "GET").upper():
        raise Refused(
            f"the candidate '{candidate_id}' names the method '{candidate.get('method')}', "
            f"the contract declares '{contract.get('method')}'"
        )

    # The proposal names the endpoint of every candidate the record holds, so every
    # one of them is a committed value and holds to the record's own rule: a value
    # the record's own redactor would replace is refused here as well.
    for other in investigation.get("api_candidates") or []:
        problems = endpoint_problems(str(other.get("endpoint") or ""))
        if problems:
            raise Refused(
                f"the candidate '{other.get('id')}' names an endpoint that may not be published: {'; '.join(problems)}"
            )

    # Only the experiments of this contract produce fixtures, so only they can earn the
    # proposal. Every observation of them must have asked the candidate's endpoint: an
    # experiment of another endpoint would otherwise be pinned as this contract's
    # evidence.
    wanted = set(contract["experiments"].values())
    experiments = []
    observations: dict[str, dict[str, Any]] = {}
    for experiment in eligible_experiments(validation):
        if experiment.get("name") not in wanted:
            continue
        for observation in experiment["observations"]:
            # The id becomes a file name, and the request becomes the claim the
            # fixture pins. Both are checked before anything is written.
            observation_id = safe_observation(observation.get("id"))
            # An observation has to belong to the experiment that holds it. The
            # capture is checked against the observation below, but a capture and an
            # observation that agree with each other and disagree with the
            # experiment around them would earn a claim for an experiment the
            # observation does not name.
            if observation.get("experiment") != experiment.get("name"):
                raise Refused(
                    f"the observation '{observation_id}' names the experiment "
                    f"'{observation.get('experiment')}', the experiment that holds it is "
                    f"'{experiment.get('name')}' — an observation has to belong to the "
                    f"experiment it is filed under"
                )
            request = observation.get("request") or {}
            if not candidate_request_matches(contract, request.get("method"), request.get("url")):
                raise Refused(
                    f"the observation '{observation_id}' of the '{experiment.get('name')}' "
                    f"experiment asked another endpoint — a fixture of this contract must be "
                    f"built from a request of this contract"
                )
            # The URL of the request is committed in the fixture, so its values have
            # to hold to the record's sanitization rule and to the contract's
            # declaration of the parameters this request carries.
            unsafe = query_problems(contract, str(request.get("url") or ""))
            if unsafe:
                raise Refused(
                    f"the observation '{observation_id}' holds a request with parameters the "
                    f"contract does not allow to be committed: {unsafe} — the request is "
                    f"committed in the fixture, so a committed parameter is one the contract "
                    f"declares and one the record's own redactor would leave alone"
                )
            # The capture has to be the capture of this observation: the bundle is
            # filed under the id, and a bundle of another run of the same endpoint
            # would put its products into this fixture.
            capture = evidence.get(observation_id)
            if capture is None:
                raise Refused(
                    f"the observation '{observation_id}' has no capture in the evidence bundle — "
                    f"a fixture cannot be rebuilt from a shape nobody recorded"
                )
            if not capture_matches_observation(capture, observation):
                raise Refused(
                    f"the capture of the observation '{observation_id}' describes another run — "
                    f"the bundle and the record disagree about what was requested, so the "
                    f"fixture would not be the answer that was recorded"
                )
            observations[observation_id] = observation
        experiments.append(experiment)

    earned = claims_about_candidate(
        investigation,
        evidence,
        contract,
        observations,
    )
    check_eligible(investigation, validation, candidate_id, experiments, earned)

    owner = existing_proposal_for(output_root, candidate_id, investigation_id)
    if owner:
        raise Refused(
            f"the contract '{candidate_id}' was already proposed by '{owner}' — the test and the "
            f"fixtures are the contract's, so one contract has one proposal; remove the old "
            f"proposal first if this investigation supersedes it"
        )

    # A captured header value never reaches a query, a fixture or a proposal: the
    # header names are the contract, their values are not. A record that holds the
    # same value in a query and in a header has leaked one into the other, and the
    # query is the place it would be committed.
    header_values = {
        str(value) for value in (candidate.get("headers") or {}).values() if value and not is_placeholder(value)
    }
    leaked = sorted(name for name, value in (candidate.get("query") or {}).items() if str(value) in header_values)
    if leaked:
        raise Refused(
            f"the candidate's query carries the value of a header for {leaked} — a captured "
            f"header value never reaches a query, a fixture or a proposal"
        )

    # The query of the candidate is published in the proposal, so the record has to
    # hold to its own sanitization rule and the contract has to declare every
    # parameter: a session token, an account id or a search phrase in the query
    # would be committed in the Markdown of the proposal, and a name nobody can
    # explain may carry somebody's name.
    unpublishable = parameter_problems(contract, list((candidate.get("query") or {}).items()))
    if unpublishable:
        raise Refused(
            f"the candidate's query holds parameters the contract does not allow to be "
            f"committed: {unpublishable} — a committed query parameter is one the contract "
            f"declares, with a value the contract allows, and one the record's own redactor "
            f"would leave alone"
        )

    # The candidate is the summary of the request, and the proposal publishes its
    # query as the parameters of the contract. A candidate that contradicts the
    # recorded request would leave the proposal advertising one request and pinning
    # another.
    baseline = baseline_observation(contract, experiments)
    candidate_agrees_with_evidence(contract, candidate, baseline)

    # The proposal publishes three things about the answer — the status, the content
    # type and the keys of the body — and the candidate's own summary is where they
    # come from. A summary is written by hand, so the evidence has to agree with it
    # before it is published; the values that reach the proposal are the ones the
    # capture holds.
    baseline_capture = evidence.get(str(baseline.get("id") or ""))
    if baseline_capture is None:
        raise Refused(
            f"the baseline observation '{baseline.get('id')}' has no capture in the evidence bundle — "
            f"the answer the proposal publishes has to be one the record saw"
        )
    answer = answer_of_the_evidence(candidate, baseline_capture)

    fixtures: dict[str, list[str]] = {}
    fixture_documents: list[dict[str, Any]] = []
    for experiment in experiments:
        role = next(
            (name for name, wanted in contract["experiments"].items() if wanted == experiment["name"]),
            None,
        )
        if role is None:
            continue
        names: list[str] = []
        for observation in experiment["observations"]:
            observation_id = safe_observation(observation.get("id"))
            capture = evidence.get(observation_id)
            if capture is None:
                raise Refused(
                    f"the observation '{observation_id}' has no capture in the evidence bundle — "
                    f"a fixture cannot be rebuilt from a shape nobody recorded"
                )
            captured = capture.get("request") or {}
            if not candidate_request_matches(contract, captured.get("method"), captured.get("url")):
                raise Refused(
                    f"the capture of the observation '{observation_id}' asked another endpoint — "
                    f"a fixture of this contract must be built from a request of this contract"
                )
            ids = product_ids_of(observation, capture)
            recorded = (observation.get("schema") or {}).get("productCount")
            if recorded is not None and len(ids) != recorded:
                raise Refused(
                    f"the observation '{observation_id}' recorded {recorded} products, the evidence "
                    f"carries {len(ids)} — the fixture would not be the answer that was recorded"
                )
            # The body of the answer is rebuilt by the test from the shape and the
            # order the record kept, so the shape has to name the products: a record
            # that saw no products cannot be rebuilt into a page of them.
            keys = list((observation.get("schema") or {}).get("topLevelKeys") or [])
            if "products" not in keys:
                raise Refused(
                    f"the observation '{observation_id}' recorded an answer without products — "
                    f"the body of a fixture is rebuilt from the shape the record kept, and a "
                    f"shape without products rebuilds nothing"
                )
            document = fixture_of(observation, capture, candidate_id, investigation_id)
            fixture_documents.append(document)
            names.append(document["observation"])
        fixtures[role] = names

    missing = [role for role in contract["experiments"] if role not in fixtures]
    if missing:
        raise Refused(f"the evidence has no experiment for the role(s) {missing}")

    written: list[Path] = []
    root = output_root.resolve()
    fixture_dir = fixture_dir_of(candidate_id)
    planned: dict[str, str] = {}

    def plan(relative: str, content: str, within: str | None = None) -> None:
        """Put a file on the list of files this generation writes.

        Nothing is written here: the file is only checked and remembered. The whole
        proposal is rendered and checked before the first byte reaches the disk, so a
        refusal never leaves half a proposal behind — a fixture without its test, a
        test without its proposal. A generator that leaves a mess behind is a
        generator whose output has to be cleaned by hand before anybody can look at
        it.
        """
        path = output_root / relative
        resolved = path.resolve()
        if not resolved.is_relative_to(root):
            raise Refused(f"the generator would write outside the output root: {relative}")
        if within is not None and not resolved.is_relative_to((output_root / within).resolve()):
            raise Refused(f"the generator would write outside {within}: {relative}")
        planned[relative] = content

    # The fixture directory belongs to the contract: everything in it is written by
    # this generator, and a file that this generation does not produce is a leftover
    # of another one. The drift gate reports it too, but the generator refuses
    # before it writes, so the leftover never gets a new sibling.
    produced = {f"{document['observation']}.json" for document in fixture_documents}
    folder = output_root / fixture_dir
    leftovers = (
        sorted(path.name for path in folder.glob("*.json") if path.name not in produced) if folder.is_dir() else []
    )
    if leftovers:
        raise Refused(
            f"the fixture directory '{fixture_dir}' holds {leftovers} — the directory belongs to "
            f"the contract and every file in it is written by this generator; remove the leftovers"
        )

    for document in fixture_documents:
        plan(
            f"{fixture_dir}/{document['observation']}.json",
            json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            within=fixture_dir,
        )

    plan(TEST_FILE, render_kotlin(contract, investigation_id, candidate_id, fixtures, validation))

    proposal_file = f"{PROPOSAL_DIR}/{investigation_id}/proposal.md"
    proposal = render_proposal(
        investigation,
        validation,
        candidate,
        contract,
        candidate_id,
        investigation_id,
        fixtures,
        [TEST_FILE]
        + [f"{fixture_dir}/{document['observation']}.json" for document in fixture_documents]
        + [proposal_file],
        earned,
        answer,
    )
    plan(proposal_file, proposal)

    sources = {
        f"devtools/wb-research/investigations/{investigation_id}/{name}": sha256_of(directory / name)
        for name in ("investigation.yaml", "validation.yaml")
        if (directory / name).is_file()
    }
    # The evidence bundles are read as well: the order of the products comes from them,
    # so a bundle that changed under the proposal must fail the drift gate.
    sources |= {
        f"devtools/wb-research/investigations/{investigation_id}/evidence/{path.name}": sha256_of(path)
        for path in sorted((directory / "evidence").glob("*.json"))
    }

    manifest = {
        "schema": "wb-research/proposal-manifest/v1",
        "investigation": investigation_id,
        "candidate": candidate_id,
        # The generator's own hash: a proposal that was generated by a different
        # version of the tool is a proposal nobody checked, and the drift gate
        # compares this number with the tool on disk. The redactor is in the same
        # section because it is loaded by path and decides what may be committed:
        # a change of its rules has to invalidate the proposal just as a change of
        # the generator does.
        "generator": {
            GENERATOR_PATH: sha256_of(Path(__file__).resolve()),
            REDACTOR_PATH: sha256_of(ROOT / REDACTOR_PATH),
        },
        "sources": sources,
        # The hashes are of the content that is about to be written, so the manifest
        # says what the disk holds even if the writing itself fails half way.
        "generated": {relative: sha256_of_text(content) for relative, content in sorted(planned.items())},
    }
    plan(
        f"{PROPOSAL_DIR}/{investigation_id}/manifest.json",
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )

    # Everything is rendered and checked: now the files may be written.
    for relative, content in planned.items():
        path = output_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written.append(path)

    return written


# --------------------------------------------------------------------------------------
# The self-test
# --------------------------------------------------------------------------------------
#
# The suite of the generator is `scripts/tests/test_wb_research_generate_proposal.py`:
# it holds the rules of the tool and a counterexample for each of them. This
# self-test is the smoke check of the installed tool — it proves that the tool runs
# end to end, refuses what it must refuse, and writes the same bytes twice, without
# a test framework and without the repository's record.


def self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        endpoint = str(CONTRACTS["storefront-catalog-search"]["endpoint"])
        investigations = root / "devtools/wb-research/investigations"
        investigation_dir = investigations / "2026-01-01-demo"
        (investigation_dir / "evidence").mkdir(parents=True)

        def observation(
            experiment: str,
            run: int,
            ids: list[int],
            sort: str = "popular",
            page: int | None = None,
        ) -> dict[str, Any]:
            query = f"resultset=catalog&sort={sort}&query=%3Credacted%3Aquery-param%3E"
            if page is not None:
                query += f"&page={page}"
            return {
                "id": f"{experiment}-{run}",
                "experiment": experiment,
                "run": run,
                "request": {
                    "method": "GET",
                    "url": f"{endpoint}?{query}",
                    "headerNames": ["deviceid", "x-requested-with"],
                },
                "response": {
                    "status": 200,
                    "contentType": "text/plain",
                    "bodyBytes": 10,
                    "isJson": True,
                },
                "schema": {
                    "jsonType": "object",
                    "topLevelKeys": ["metadata", "products", "total"],
                    "productCount": len(ids),
                    "total": 7,
                },
                "ordering": {"orderHash": f"hash-{experiment}"},
            }

        experiments = [
            {
                "name": "baseline",
                "state": "reproduced",
                "observations": [observation("baseline", 1, [1, 2]), observation("baseline", 2, [1, 2])],
            },
            {
                "name": "sort-priceup",
                "state": "validated",
                "observations": [
                    observation("sort-priceup", 1, [3, 4], sort="priceup"),
                    observation("sort-priceup", 2, [3, 4], sort="priceup"),
                ],
            },
            {
                "name": "page-2",
                "state": "validated",
                "observations": [
                    observation("page-2", 1, [5, 6], page=2),
                    observation("page-2", 2, [5, 6], page=2),
                ],
            },
            {
                "name": "after-reload",
                "state": "validated",
                "observations": [observation("after-reload", 1, [1, 2]), observation("after-reload", 2, [1, 2])],
            },
        ]

        # The evidence keeps the order itself; the summary keeps its hash.
        captures = []
        for experiment in experiments:
            for item in experiment["observations"]:
                ids = (
                    [5, 6]
                    if item["experiment"] == "page-2"
                    else [3, 4]
                    if item["experiment"] == "sort-priceup"
                    else [1, 2]
                )
                captures.append({**item, "ordering": {**item["ordering"], "productIds": ids}})
        (investigation_dir / "evidence" / "validation.json").write_text(
            json.dumps({"schema": "wb-research/validation/v1", "captures": captures}),
            encoding="utf-8",
        )

        def write_record(state: str) -> None:
            record = {
                "id": "2026-01-01-demo",
                "status": "concluded",
                "api_candidates": [
                    {
                        "id": "storefront-catalog-search",
                        "method": "GET",
                        "endpoint": endpoint,
                        "query": {
                            "resultset": "catalog",
                            "sort": "popular",
                            "query": "<redacted:query-param>",
                        },
                        "headers": {"deviceid": "<redacted:deviceid>"},
                        "observed_status": 200,
                        "response_content_type": "text/plain",
                        "response_schema": {"top_level_keys": ["metadata", "products", "total"]},
                    },
                ],
                "validation": [
                    {
                        "claim": "the catalog is read with resultset=catalog",
                        "state": state,
                        "evidence": ["baseline-1"],
                    },
                ],
            }
            (investigation_dir / "investigation.yaml").write_text(
                yaml.safe_dump(record, allow_unicode=True), encoding="utf-8"
            )
            (investigation_dir / "validation.yaml").write_text(
                yaml.safe_dump(
                    {
                        "candidate": "storefront-catalog-search",
                        "page": "https://www.wildberries.ru/catalog/131954/list.aspx?search=%3Credacted%3Aquery-param%3E",
                        "experiments": experiments,
                    },
                    allow_unicode=True,
                ),
                encoding="utf-8",
            )

        def run() -> list[Path]:
            return generate("2026-01-01-demo", root, investigations)

        # A claim that was only observed is not a contract.
        write_record("observed")
        try:
            run()
        except Refused as refusal:
            assert "validated" in str(refusal), str(refusal)
        else:
            raise AssertionError("an observed claim must not produce a proposal")

        # A validated claim produces the files, and produces them the same way twice.
        write_record("validated")
        written = run()
        assert written, "a validated record must produce files"
        first = {path.relative_to(root).as_posix(): path.read_bytes() for path in written}
        second = {path.relative_to(root).as_posix(): path.read_bytes() for path in run()}
        assert first == second, "the generator must be reproducible"

        test_source = (root / TEST_FILE).read_text(encoding="utf-8")
        assert "%%" not in test_source, "a template token was left in the generated test"
        assert "<redacted:deviceid>" not in test_source, "a redacted value must not be committed"

        fixture = json.loads(
            (root / f"{fixture_dir_of('storefront-catalog-search')}/baseline-1.json").read_text(encoding="utf-8")
        )
        assert fixture["ordering"]["productIds"] == [1, 2], "the order must come from the evidence"
        assert "body" not in fixture, "the fixture holds the order once — the test rebuilds the body from it"
        assert fixture["run"] == 1, "the fixture names the run it was built from"

    print("self-test: an observed claim is refused, a validated one generates the same files twice")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--investigation", help="the id of the investigation to read")
    parser.add_argument("--output-root", type=Path, default=ROOT, help="where the files are written")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()

    if arguments.self_test:
        self_test()
        return 0

    if not arguments.investigation:
        parser.error("--investigation is required")

    try:
        written = generate(arguments.investigation, arguments.output_root)
    except Refused as refusal:
        print(f"refused: {refusal}", file=sys.stderr)
        return 2

    for path in written:
        print(path.relative_to(arguments.output_root).as_posix())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
