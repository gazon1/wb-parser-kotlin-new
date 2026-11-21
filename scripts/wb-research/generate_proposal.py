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
  of the answer and the order of the products, with the body reconstructed from the
  recorded shape (no captured payload ever enters the repository);
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
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlsplit

import yaml

ROOT = Path(__file__).resolve().parents[2]
INVESTIGATIONS = ROOT / "devtools" / "wb-research" / "investigations"
PROPOSALS = ROOT / "devtools" / "wb-research" / "proposals"

# Where the generated artifacts live, relative to the repository root. The test goes
# into the module that owns the contract (`:wbparser`), the fixtures into its test
# resources — a fixture that is not on the classpath is not a fixture.
TEST_FILE = "wbparser/src/test/kotlin/ru/wbparser/infra/http/WbCatalogSearchContractTest.kt"
FIXTURE_DIR = "wbparser/src/test/resources/fixtures/catalog-search"
PROPOSAL_DIR = "devtools/wb-research/proposals"
GENERATOR_PATH = "scripts/wb-research/generate_proposal.py"

# The id of an investigation names a directory in the repository. A name that carries a
# separator or a `..` would point at another directory than the operator named, so it is
# refused before any file is read or written — the rule `capture.py` and `plan.py` follow
# for the same reason.
INVESTIGATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The contract family this tool knows. It is one family on purpose: a generator that
# promises every endpoint of every site is a framework, and a framework nobody can
# verify is worse than no framework. A new family is a new entry here.
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


class Refused(Exception):
    """The record does not earn a proposal."""


# --------------------------------------------------------------------------------------
# The record
# --------------------------------------------------------------------------------------


def load_yaml(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def claims_of(investigation: dict[str, Any]) -> list[dict[str, Any]]:
    return list(investigation.get("validation") or [])


def validated_claims(investigation: dict[str, Any]) -> list[dict[str, Any]]:
    return [claim for claim in claims_of(investigation) if claim.get("state") in VALIDATED_STATES]


def candidate_of(investigation: dict[str, Any], candidate_id: str) -> dict[str, Any]:
    for candidate in investigation.get("api_candidates") or []:
        if candidate.get("id") == candidate_id:
            return candidate
    raise Refused(f"the record has no candidate '{candidate_id}'")


def claims_about_candidate(
    investigation: dict[str, Any],
    evidence: dict[str, dict[str, Any]],
    endpoint: str,
) -> list[dict[str, Any]]:
    """The claims whose evidence captured this candidate's endpoint.

    A record holds the claims of every endpoint it saw, so a claim in a validated
    state may be about another endpoint entirely — the card list, the antibot
    service, an analytics call. The link is the capture: the claim names it, and
    the capture asked the candidate's endpoint.
    """
    earned: list[dict[str, Any]] = []
    for claim in claims_of(investigation):
        if claim.get("state") not in VALIDATED_STATES:
            continue
        for observation_id in claim.get("evidence") or []:
            capture = evidence.get(str(observation_id))
            if capture is None:
                continue
            url = str((capture.get("request") or {}).get("url") or "")
            if url.split("?", 1)[0] == endpoint:
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
            f"no claim about the candidate '{candidate_id}' reached a validated state — "
            f"the record holds {states or ['no claims at all']}"
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
                index[str(capture["id"])] = capture
    return index


# --------------------------------------------------------------------------------------
# The contract
# --------------------------------------------------------------------------------------


def query_of(url: str) -> dict[str, str]:
    """The query of a URL, decoded — the request as it was made."""
    return dict(parse_qsl(urlsplit(url).query, keep_blank_values=True))


def header_names_of(observation: dict[str, Any]) -> list[str]:
    request = observation.get("request") or {}
    return list(request.get("headerNames") or [])


def synthesized_body(observation: dict[str, Any], capture: dict[str, Any]) -> dict[str, Any] | None:
    """The body of the answer, reconstructed from what was recorded.

    The capture kept the shape of the answer and the ids of the products, not the
    payload — a payload is where a price, a name and a session live. The body the
    test decodes is rebuilt from the recorded shape: the products it names, in the
    order it recorded them, and nothing else.
    """
    answer = observation.get("schema") or {}
    keys = list(answer.get("topLevelKeys") or [])
    if "products" not in keys:
        return None

    ids = list(product_ids_of(observation, capture))
    body: dict[str, Any] = {}
    if "metadata" in keys:
        body["metadata"] = {"catalog_type": "catalog-merger", "name": "<redacted:query-param>"}
    if "total" in keys:
        body["total"] = answer.get("total")
    body["products"] = [{"id": product_id, "name": f"Товар {product_id}"} for product_id in ids]
    return body


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
        "body": synthesized_body(observation, capture),
        "bodySource": "synthesized-from-the-recorded-shape",
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
import kotlinx.serialization.json.JsonObject
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.json.wbJson
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

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
                javaClass.getResourceAsStream("/fixtures/catalog-search/$name.json")
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

        /** The body of a fixture — rebuilt from the recorded shape, never captured. */
        fun bodyOf(fixture: ContractFixture): String =
            requireNotNull(fixture.body) { "the fixture ${fixture.observation} carries no body" }.toString()

        val baseline = fixture("%%BASELINE%%")
        val baselineReplay = fixture("%%BASELINE_REPLAY%%")
        val sorted = fixture("%%SORTED%%")
        val sortedReplay = fixture("%%SORTED_REPLAY%%")
        val nextPage = fixture("%%NEXT_PAGE%%")
        val nextPageReplay = fixture("%%NEXT_PAGE_REPLAY%%")
        val reload = fixture("%%RELOAD%%")
        val reloadReplay = fixture("%%RELOAD_REPLAY%%")

        val productionUrl = WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = token)
        val productionQuery = queryOf(productionUrl)
        val productionHeaders =
            WbCatalogApi
                .buildWbHeaders()
                .keys
                .map { it.lowercase() }
                .toSet()

        test("the request goes to the endpoint the record observed") {
            // The path is as much a part of the contract as the parameters are: the
            // wrong endpoint answers 200 with somebody else's products. The recorded
            // query is redacted, so only the place the request goes to is compared.
            val production = URI(productionUrl)
            val recorded = URI(baseline.request.url)

            production.scheme shouldBe recorded.scheme
            production.host shouldBe recorded.host
            production.path shouldBe recorded.path
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
                if (value.startsWith("<redacted:") || name in storefrontOnly) return@forEach
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
            val page = queryOf(WbCatalogApi.buildWbApiUrl(page = 2, searchQuery = token))

            page["page"] shouldBe "2"
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
    val evidence: List<String>,
    val request: FixtureRequest,
    val response: FixtureResponse,
    val answer: FixtureAnswer,
    val ordering: FixtureOrdering,
    val body: JsonObject? = null,
    val bodySource: String? = null,
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
) -> str:
    """The Kotlin test, with the contract's names and the evidence's fixture names."""
    experiments = contract["experiments"]

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
    evidence: dict[str, dict[str, Any]],
) -> str:
    validated = validated_claims(investigation)
    about = {
        str(claim.get("claim"))
        for claim in claims_about_candidate(investigation, evidence, str(candidate.get("endpoint") or ""))
    }
    evidence_of = {observation["id"] for observation in observations_of(eligible_experiments(validation))}

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
    lines.append(
        f"- **Answer**: `{candidate.get('response_content_type')}`, "
        f"top-level keys `{', '.join((candidate.get('response_schema') or {}).get('top_level_keys') or [])}`"
    )
    lines.append(f"- **Observed status**: `{candidate.get('observed_status')}`")
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
    replay_headers = header_names_of(observations_of(eligible_experiments(validation))[0])
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
        "this contract* when the capture it names asked this endpoint — that is what the "
        "eligibility rule counts, and it is why a validated claim about another endpoint is not "
        "enough."
    )
    lines.append("")
    lines.append("| Claim | State | About this contract | Evidence | Where it is pinned |")
    lines.append("| --- | --- | --- | --- | --- |")
    for claim in validated:
        evidence = ", ".join(f"`{item}`" for item in (claim.get("evidence") or []))
        seen = "yes" if str(claim.get("claim")) in about else "no"
        lines.append(f"| {claim.get('claim')} | `{claim.get('state')}` | {seen} | {evidence} | `{TEST_FILE}` |")
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
            lines.append(f"- `{candidate.get('id')}` — `{candidate.get('endpoint')}`")
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

    experiments = eligible_experiments(validation)
    candidate = candidate_of(investigation, candidate_id)
    evidence = evidence_index(directory)
    earned = claims_about_candidate(investigation, evidence, str(candidate.get("endpoint") or ""))
    check_eligible(investigation, validation, candidate_id, experiments, earned)

    if candidate_id not in CONTRACTS:
        raise Refused(f"this tool does not know the contract '{candidate_id}' — it knows {sorted(CONTRACTS)}")
    contract = CONTRACTS[candidate_id]

    owner = existing_proposal_for(output_root, candidate_id, investigation_id)
    if owner:
        raise Refused(
            f"the contract '{candidate_id}' was already proposed by '{owner}' — the test and the "
            f"fixtures are the contract's, so one contract has one proposal; remove the old "
            f"proposal first if this investigation supersedes it"
        )

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
            observation_id = str(observation.get("id"))
            capture = evidence.get(observation_id)
            if capture is None:
                raise Refused(
                    f"the observation '{observation_id}' has no capture in the evidence bundle — "
                    f"a fixture cannot be rebuilt from a shape nobody recorded"
                )
            ids = product_ids_of(observation, capture)
            recorded = (observation.get("schema") or {}).get("productCount")
            if recorded is not None and len(ids) != recorded:
                raise Refused(
                    f"the observation '{observation_id}' recorded {recorded} products, the evidence "
                    f"carries {len(ids)} — the fixture would not be the answer that was recorded"
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

    def write(relative: str, content: str) -> None:
        path = output_root / relative
        if not path.resolve().is_relative_to(root):
            raise Refused(f"the generator would write outside the output root: {relative}")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written.append(path)

    for document in fixture_documents:
        write(
            f"{FIXTURE_DIR}/{document['observation']}.json",
            json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        )

    write(TEST_FILE, render_kotlin(contract, investigation_id, candidate_id, fixtures))

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
        + [f"{FIXTURE_DIR}/{document['observation']}.json" for document in fixture_documents]
        + [proposal_file],
        evidence,
    )
    write(proposal_file, proposal)

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
        # compares this number with the tool on disk.
        "generator": {GENERATOR_PATH: sha256_of(Path(__file__).resolve())},
        "sources": sources,
        "generated": {path.relative_to(output_root).as_posix(): sha256_of(path) for path in written},
    }
    write(
        f"{PROPOSAL_DIR}/{investigation_id}/manifest.json",
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )

    return written


# --------------------------------------------------------------------------------------
# The self-test
# --------------------------------------------------------------------------------------


def self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        investigations = root / "devtools/wb-research/investigations"
        investigation_dir = investigations / "2026-01-01-demo"
        (investigation_dir / "evidence").mkdir(parents=True)

        def observation(
            experiment: str,
            run: int,
            ids: list[int],
            total: int,
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
                    "url": f"https://www.wildberries.ru/x?{query}",
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
                    "total": total,
                },
                "ordering": {"orderHash": f"hash-{experiment}"},
            }

        experiments = [
            {
                "name": "baseline",
                "state": "reproduced",
                "observations": [observation("baseline", 1, [1, 2], 7), observation("baseline", 2, [1, 2], 7)],
            },
            {
                "name": "sort-priceup",
                "state": "validated",
                "observations": [
                    observation("sort-priceup", 1, [3, 4], 5, sort="priceup"),
                    observation("sort-priceup", 2, [3, 4], 5, sort="priceup"),
                ],
            },
            {
                "name": "page-2",
                "state": "validated",
                "observations": [
                    observation("page-2", 1, [5, 6], 7, page=2),
                    observation("page-2", 2, [5, 6], 7, page=2),
                ],
            },
            {
                "name": "after-reload",
                "state": "validated",
                "observations": [
                    observation("after-reload", 1, [1, 2], 7),
                    observation("after-reload", 2, [1, 2], 7),
                ],
            },
        ]

        # The evidence keeps the order itself; the summary keeps its hash.
        captures = []
        for experiment in experiments:
            for item in experiment["observations"]:
                ids = [1, 2] if item["experiment"] in ("baseline", "after-reload") else [3, 4]
                if item["experiment"] == "page-2":
                    ids = [5, 6]
                captures.append({**item, "ordering": {**item["ordering"], "productIds": ids}})
        (investigation_dir / "evidence" / "validation.json").write_text(
            json.dumps({"schema": "wb-research/validation/v1", "captures": captures}),
            encoding="utf-8",
        )

        def write_record(state: str, with_validation: bool = True) -> None:
            record = {
                "id": "2026-01-01-demo",
                "status": "concluded",
                "api_candidates": [
                    {
                        "id": "storefront-catalog-search",
                        "method": "GET",
                        "endpoint": "https://www.wildberries.ru/x",
                        "query": {"resultset": "catalog"},
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
            if with_validation:
                (investigation_dir / "validation.yaml").write_text(
                    yaml.safe_dump(
                        {"candidate": "storefront-catalog-search", "experiments": experiments},
                        allow_unicode=True,
                    ),
                    encoding="utf-8",
                )
            else:
                (investigation_dir / "validation.yaml").unlink(missing_ok=True)

        def run(root_path: Path) -> list[Path]:
            return generate("2026-01-01-demo", root_path, investigations)

        # A claim that was only observed is not a contract.
        write_record("observed")
        try:
            run(root)
        except Refused as refusal:
            assert "validated" in str(refusal), str(refusal)
        else:
            raise AssertionError("an observed claim must not produce a proposal")

        # A record without controlled experiments is not a contract either.
        write_record("validated", with_validation=False)
        try:
            run(root)
        except Refused as refusal:
            assert "validation.yaml" in str(refusal), str(refusal)
        else:
            raise AssertionError("a record without validation.yaml must not produce a proposal")

        # A validated claim produces the files, and produces them the same way twice.
        write_record("validated")
        written = run(root)
        assert written, "a validated record must produce files"
        first = {path.relative_to(root).as_posix(): path.read_bytes() for path in written}
        second = {path.relative_to(root).as_posix(): path.read_bytes() for path in run(root)}
        assert first == second, "the generator must be reproducible"

        test_source = (root / TEST_FILE).read_text(encoding="utf-8")
        assert "%%" not in test_source, "a template token was left in the generated test"
        assert "headerNames" in test_source, "the headers must be read from the fixture"
        assert "<redacted:deviceid>" not in test_source, "a redacted value must not be committed"

        fixture = json.loads((root / f"{FIXTURE_DIR}/baseline-1.json").read_text(encoding="utf-8"))
        assert fixture["ordering"]["productIds"] == [1, 2], "the order must come from the evidence"
        assert len(fixture["body"]["products"]) == 2, "the body must be rebuilt from the recorded order"

    print("self-test: an observed claim and a record without experiments are both refused, a validated one generates")


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
