"""Tests for the proposal generator.

The generator's whole value is the gate it applies: code written from a claim the
record never validated is a guess with a confident shape. So the tests here are
about what the tool refuses, about what it writes when it agrees, and about the
two things the repository must never hold — a captured payload and a captured
value.

Everything runs offline. The investigation is a directory the test writes, and
the evidence is a JSON file the test writes.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest
import yaml

GENERATOR = Path(__file__).resolve().parents[1] / "wb-research" / "generate_proposal.py"
CANDIDATE = "storefront-catalog-search"
ENDPOINT = "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"
DEVICE_ID = "site_a39361daf4ec45f2a68c5c6ca73c8adf"


def load_generator():
    spec = importlib.util.spec_from_file_location("wb_research_generate_proposal", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


generate_proposal = load_generator()


def observation(
    experiment: str,
    run: int,
    ids: list[int],
    total: int,
    sort: str = "popular",
    page: int | None = None,
) -> dict:
    query = f"resultset=catalog&sort={sort}&query=%3Credacted%3Aquery-param%3E"
    if page is not None:
        query += f"&page={page}"
    return {
        "id": f"{experiment}-{run}",
        "experiment": experiment,
        "run": run,
        "request": {
            "method": "GET",
            "url": f"{ENDPOINT}?{query}",
            "headerNames": ["deviceid", "x-requested-with"],
        },
        "response": {"status": 200, "contentType": "text/plain", "bodyBytes": 10, "isJson": True},
        "schema": {
            "jsonType": "object",
            "topLevelKeys": ["metadata", "products", "total"],
            "productCount": len(ids),
            "total": total,
        },
        "ordering": {"orderHash": f"hash-{experiment}"},
    }


def experiments() -> list[dict]:
    """The four experiments the contract needs, plus one the record did not earn."""
    return [
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
        {
            "name": "without-cookies",
            "state": "inconclusive",
            "observations": [observation("without-cookies", 1, [], None)],
        },
    ]


@pytest.fixture
def world(tmp_path):
    """An investigation directory the tests write their record into."""
    investigations = tmp_path / "investigations"
    directory = investigations / "2026-01-01-demo"
    (directory / "evidence").mkdir(parents=True)

    def captures() -> list[dict]:
        found = []
        for experiment in experiments():
            for item in experiment["observations"]:
                ids = {"baseline": [1, 2], "after-reload": [1, 2], "sort-priceup": [3, 4], "page-2": [5, 6]}.get(
                    item["experiment"], []
                )
                found.append({**item, "ordering": {**item["ordering"], "productIds": ids}})
        return found

    def write_evidence(found: list[dict]) -> None:
        (directory / "evidence" / "validation.json").write_text(
            json.dumps({"schema": "wb-research/validation/v1", "captures": found}), encoding="utf-8"
        )

    write_evidence(captures())

    def write(
        state: str = "validated",
        with_validation: bool = True,
        status: str = "concluded",
        evidence: list[str] | None = None,
        candidate: str | None = CANDIDATE,
    ) -> Path:
        record = {
            "id": "2026-01-01-demo",
            "status": status,
            "api_candidates": [
                {
                    "id": CANDIDATE,
                    "method": "GET",
                    "endpoint": ENDPOINT,
                    "query": {
                        "resultset": "catalog",
                        "sort": "popular",
                        "query": "<redacted:query-param>",
                    },
                    "headers": {"deviceid": "<redacted:deviceid>", "referer": "https://www.wildberries.ru/"},
                    "observed_status": 200,
                    "response_content_type": "text/plain",
                    "response_schema": {"top_level_keys": ["metadata", "products", "total"]},
                }
            ],
            "validation": [
                {
                    "claim": "the catalog is read with resultset=catalog",
                    "state": state,
                    "evidence": evidence if evidence is not None else ["baseline-1"],
                }
            ],
        }
        (directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")
        validation = directory / "validation.yaml"
        if with_validation:
            validation.write_text(
                yaml.safe_dump(
                    {
                        "candidate": candidate,
                        "page": "https://www.wildberries.ru/catalog/131954/list.aspx?search=%3Credacted%3Aquery-param%3E",
                        "experiments": experiments(),
                    },
                    allow_unicode=True,
                ),
                encoding="utf-8",
            )
        else:
            validation.unlink(missing_ok=True)
        return investigations

    write()
    return SimpleWorld(investigations, directory, write, captures, write_evidence)


class SimpleWorld:
    def __init__(self, investigations: Path, directory: Path, write, captures, write_evidence) -> None:
        self.investigations = investigations
        self.directory = directory
        self.write = write
        self.captures = captures
        self.write_evidence = write_evidence

    def patch_observation(self, **changes) -> None:
        """Change the baseline observation of the summary and of the evidence."""
        validation = self.directory / "validation.yaml"
        document = yaml.safe_load(validation.read_text(encoding="utf-8"))
        observation = document["experiments"][0]["observations"][0]
        for name, value in changes.items():
            if name == "url":
                observation["request"]["url"] = value
            else:
                observation[name] = value
        validation.write_text(yaml.safe_dump(document, allow_unicode=True), encoding="utf-8")

        captures = self.captures()
        for capture in captures:
            if capture["id"] == "baseline-1":
                for name, value in changes.items():
                    if name == "url":
                        capture["request"]["url"] = value
                    else:
                        capture[name] = value
        self.write_evidence(captures)

    def patch_capture(self, capture_id: str, **changes) -> None:
        """Change a capture of the evidence, leaving the summary as it is.

        The bundle and the summary describe the same run twice. A change in one of
        them alone is the case the generator has to notice: the fixture would carry
        the products of one run under the name of another.
        """
        captures = self.captures()
        for capture in captures:
            if capture["id"] == capture_id:
                for name, value in changes.items():
                    if name == "url":
                        capture["request"]["url"] = value
                    else:
                        capture[name] = value
        self.write_evidence(captures)

    def drop_from_capture(self, capture_id: str, *path: str) -> None:
        """Remove a field of a capture of the evidence, leaving the summary as it is.

        A dimension the capture does not describe is a dimension the generator cannot
        check the capture against: the bundle has to say which run it is, which
        request it made and in which order the answer came.
        """
        captures = self.captures()
        for capture in captures:
            if capture["id"] == capture_id:
                target = capture
                for name in path[:-1]:
                    target = target[name]
                target.pop(path[-1], None)
        self.write_evidence(captures)

    def patch_candidate(self, **changes) -> None:
        """Change the candidate of the record."""
        record = yaml.safe_load((self.directory / "investigation.yaml").read_text(encoding="utf-8"))
        candidate = record["api_candidates"][0]
        for name, value in changes.items():
            if name == "query":
                candidate["query"].update(value)
            else:
                candidate[name] = value
        (self.directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")

    def drop_from_candidate(self, name: str) -> None:
        """Remove a parameter from the query of the candidate."""
        record = yaml.safe_load((self.directory / "investigation.yaml").read_text(encoding="utf-8"))
        record["api_candidates"][0]["query"].pop(name, None)
        (self.directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")

    def patch_page(self, page: str) -> None:
        """Change the crawl URL of the record."""
        validation = self.directory / "validation.yaml"
        document = yaml.safe_load(validation.read_text(encoding="utf-8"))
        document["page"] = page
        validation.write_text(yaml.safe_dump(document, allow_unicode=True), encoding="utf-8")

    def patch_claim(self, claim: str) -> None:
        """Change the wording of the claim of the record."""
        record = yaml.safe_load((self.directory / "investigation.yaml").read_text(encoding="utf-8"))
        record["validation"][0]["claim"] = claim
        (self.directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")

    def add_candidate(self, endpoint: str) -> None:
        """Add a candidate the record holds next to the one it validates."""
        record = yaml.safe_load((self.directory / "investigation.yaml").read_text(encoding="utf-8"))
        record["api_candidates"].append({"id": "another-candidate", "method": "GET", "endpoint": endpoint})
        (self.directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")

    def generate(self, output_root: Path) -> list[Path]:
        return generate_proposal.generate("2026-01-01-demo", output_root, investigations_root=self.investigations)


def test_a_record_that_is_not_concluded_is_refused(world, tmp_path):
    world.write(status="open")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_record_without_a_validation_is_refused(world, tmp_path):
    world.write(with_validation=False)

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_claim_that_is_only_observed_is_refused(world, tmp_path):
    world.write(state="observed")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_validated_claim_of_another_candidate_is_not_enough(world, tmp_path):
    # The record holds the claims of every endpoint it saw. A claim in a validated
    # state whose capture asked another endpoint says nothing about this one.
    world.write(evidence=["initial-load-004"])
    world.write_evidence(
        [
            {
                "id": "initial-load-004",
                "request": {"method": "GET", "url": "https://www.wildberries.ru/__internal/u-card/cards/v4/list"},
                "ordering": {"orderHash": "hash", "productIds": []},
            }
        ]
    )

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_claim_only_the_first_page_load_supports_is_not_enough(world, tmp_path):
    # The capture asked the right endpoint, but it is not one of the controlled
    # experiments: the record has not established the claim about this contract.
    world.write(evidence=["initial-load-004"])
    world.write_evidence(
        [
            *world.captures(),
            {
                "id": "initial-load-004",
                "request": {"method": "GET", "url": f"{ENDPOINT}?resultset=catalog&sort=popular"},
                "ordering": {"orderHash": "hash", "productIds": []},
            },
        ]
    )

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_an_experiment_of_another_endpoint_is_not_evidence_of_this_contract(world, tmp_path):
    # The endpoint is shared: the filters search is the same path with another
    # resultset. A capture of it is not a capture of the catalog search.
    world.patch_observation(url=f"{ENDPOINT}?resultset=filters&sort=popular")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_an_observation_id_that_names_another_file_is_refused(world, tmp_path):
    # The id becomes a fixture file name. An id with a path in it would point at a
    # file outside the fixture directory, and the repository holds files a
    # generator must never rewrite.
    world.patch_observation(id="../../../../.betterleaks-baseline")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_two_bundles_with_the_same_capture_are_refused(world, tmp_path):
    # Which of the two bundles is the one that was recorded? The record has to say.
    (world.directory / "evidence" / "second.json").write_text(
        json.dumps({"schema": "wb-research/validation/v1", "captures": world.captures()[:1]}),
        encoding="utf-8",
    )

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_validation_that_names_no_candidate_is_refused(world, tmp_path):
    world.write(candidate=None)

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_an_investigation_id_that_is_a_path_is_refused(world, tmp_path):
    with pytest.raises(generate_proposal.Refused):
        generate_proposal.generate("../../../../tmp/target", tmp_path, investigations_root=world.investigations)


def test_an_observation_without_a_capture_is_refused(world, tmp_path):
    world.write_evidence([capture for capture in world.captures() if capture["id"] != "baseline-1"])

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_capture_that_lost_its_products_is_refused(world, tmp_path):
    lost = [
        {**capture, "ordering": {**capture["ordering"], "productIds": []}} if capture["id"] == "baseline-1" else capture
        for capture in world.captures()
    ]
    world.write_evidence(lost)

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_second_investigation_of_the_same_contract_is_refused(world, tmp_path):
    world.generate(tmp_path)

    # The test and the fixtures belong to the contract, not to the investigation that
    # found it: a second investigation of the same contract would write the same files
    # and silently invalidate the first one's proposal.
    other = world.investigations / "2026-02-02-other"
    (other / "evidence").mkdir(parents=True)
    for name in ("investigation.yaml", "validation.yaml"):
        (other / name).write_text((world.directory / name).read_text(encoding="utf-8"), encoding="utf-8")
    (other / "evidence" / "validation.json").write_text(
        (world.directory / "evidence" / "validation.json").read_text(encoding="utf-8"), encoding="utf-8"
    )

    with pytest.raises(generate_proposal.Refused):
        generate_proposal.generate("2026-02-02-other", tmp_path, investigations_root=world.investigations)


def test_the_manifest_holds_the_evidence_the_fixtures_come_from(world, tmp_path):
    world.generate(tmp_path)

    manifest = json.loads(
        (tmp_path / "devtools/wb-research/proposals/2026-01-01-demo/manifest.json").read_text(encoding="utf-8")
    )
    sources = manifest["sources"]
    assert "devtools/wb-research/investigations/2026-01-01-demo/evidence/validation.json" in sources
    assert "devtools/wb-research/investigations/2026-01-01-demo/investigation.yaml" in sources
    assert "devtools/wb-research/investigations/2026-01-01-demo/validation.yaml" in sources


def test_the_generated_test_pins_the_endpoint_and_the_crawler_values(world, tmp_path):
    written = world.generate(tmp_path)

    test = next(path for path in written if path.suffix == ".kt").read_text(encoding="utf-8")
    assert "production.path shouldBe recorded.path" in test
    assert "production.host shouldBe recorded.host" in test
    assert '"ffeedbackpoints" to "1"' in test
    assert 'productionQuery["page"] shouldBe "1"' in test


def test_an_experiment_the_record_did_not_earn_produces_no_fixture(world, tmp_path):
    written = world.generate(tmp_path)

    names = {path.name for path in written}
    assert "without-cookies-1.json" not in names


def test_the_generated_test_pins_the_contract_to_the_evidence(world, tmp_path):
    written = world.generate(tmp_path)

    test = next(path for path in written if path.suffix == ".kt").read_text(encoding="utf-8")
    assert "class WbCatalogSearchContractTest" in test
    assert 'fixture("baseline-1")' in test
    assert "productionQuery" in test


def test_the_fixture_carries_the_order_once_and_no_captured_body(world, tmp_path):
    written = world.generate(tmp_path)

    fixture = json.loads(next(path for path in written if path.name == "baseline-1.json").read_text(encoding="utf-8"))
    assert fixture["ordering"]["productIds"] == [1, 2]
    # The order is held once: the body is rebuilt by the test from this list, so a
    # fixture that carried it twice could say two different things about it.
    assert "body" not in fixture
    assert "bodySource" not in fixture
    assert fixture["run"] == 1


def test_the_generated_test_rebuilds_the_body_from_the_recorded_order(world, tmp_path):
    written = world.generate(tmp_path)

    test = next(path for path in written if path.suffix == ".kt").read_text(encoding="utf-8")
    assert "fun bodyOf(fixture: ContractFixture): String" in test
    assert "fixture.ordering.productIds.forEach" in test


def test_the_same_record_produces_the_same_bytes(world, tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "second"

    world.generate(first)
    world.generate(second)

    for path in sorted(first.rglob("*")):
        if path.is_file():
            twin = second / path.relative_to(first)
            assert twin.read_bytes() == path.read_bytes()


def test_the_proposal_names_the_evidence_behind_every_assertion(world, tmp_path):
    written = world.generate(tmp_path)

    proposal = next(path for path in written if path.name == "proposal.md").read_text(encoding="utf-8")
    assert "baseline-1" in proposal
    assert "Traceability" in proposal
    assert "the record holds" not in proposal  # a refusal is not in the proposal


def test_no_captured_value_reaches_a_file(world, tmp_path):
    world.directory.joinpath("investigation.yaml").write_text(
        world.directory.joinpath("investigation.yaml")
        .read_text(encoding="utf-8")
        .replace("<redacted:deviceid>", DEVICE_ID),
        encoding="utf-8",
    )

    written = world.generate(tmp_path)

    for path in written:
        assert DEVICE_ID not in path.read_text(encoding="utf-8")


def test_a_candidate_that_names_another_endpoint_is_refused(world, tmp_path):
    # The contract table is what the generated test pins. A record that keeps the
    # id of a known candidate and names another endpoint is not that contract, and
    # its evidence would pin a request nobody authorized.
    world.patch_candidate(endpoint="https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v19/search")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_candidate_that_names_another_method_is_refused(world, tmp_path):
    world.patch_candidate(method="POST")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_capture_that_asks_another_request_is_refused(world, tmp_path):
    # The bundle and the summary describe the same run twice. When they disagree
    # about the request, the fixture would carry the products of one run under the
    # name of another.
    world.patch_capture(
        "baseline-1",
        url=f"{ENDPOINT}?resultset=catalog&sort=priceup&query=%3Credacted%3Aquery-param%3E",
    )

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_capture_of_another_experiment_is_refused(world, tmp_path):
    world.patch_capture("baseline-1", experiment="sort-priceup")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_repeated_identity_parameter_is_refused(world, tmp_path):
    # A request that names the identity parameter twice says two things about
    # which request it is.
    world.patch_observation(
        url=f"{ENDPOINT}?resultset=catalog&resultset=filters&sort=popular&query=%3Credacted%3Aquery-param%3E"
    )

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_record_without_a_validated_claim_is_refused(world, tmp_path):
    # A reproduced claim is a fact the record earned, but a contract needs a claim
    # a controlled experiment confirmed.
    world.write(state="reproduced")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_query_value_the_record_did_not_sanitize_is_refused(world, tmp_path):
    # The query of the candidate is published in the proposal, so an unsanitized
    # value in it would be committed in the Markdown of the proposal.
    world.patch_candidate(query={"session": "8f14e45fceea167a5a36dedd4bea2543"})

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_crawl_url_that_is_not_the_crawler_s_url_is_refused(world, tmp_path):
    # The crawler builds the runtime URL from the catalog id, so a page of another
    # shape is a page the crawler never asks with.
    world.patch_page("https://www.wildberries.ru/catalog/131954/search.aspx?search=%3Credacted%3Aquery-param%3E")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_crawl_url_without_a_search_parameter_is_refused(world, tmp_path):
    # A crawl without the token asks for the unfiltered popular list.
    world.patch_page("https://www.wildberries.ru/catalog/131954/list.aspx")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_crawl_url_with_an_unsanitized_value_is_refused(world, tmp_path):
    world.patch_page("https://www.wildberries.ru/catalog/131954/list.aspx?search=8f14e45fceea167a5a36dedd4bea2543")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_the_fixtures_live_in_the_directory_of_their_contract(world, tmp_path):
    written = world.generate(tmp_path)

    fixtures = [path for path in written if path.suffix == ".json" and path.parent.name == CANDIDATE]
    assert fixtures, "the fixtures must live in the directory of the contract"


def test_a_leftover_fixture_in_the_contract_directory_is_refused(world, tmp_path):
    # The directory belongs to the contract: everything in it is written by this
    # generator, and a file of another generation is a leftover.
    folder = tmp_path / "wbparser/src/test/resources/fixtures/catalog-search" / CANDIDATE
    folder.mkdir(parents=True)
    (folder / "obsolete.json").write_text("{}\n", encoding="utf-8")

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("session", "123456789012"),
        ("session", "YWJjZGVmZ2hp"),
        ("query", "Alice"),
        ("search", "John"),
        ("access_token", "abc123"),
        ("x-auth-token", "abc123"),
    ],
)
def test_a_short_value_the_record_would_have_redacted_is_refused(world, tmp_path, name, value):
    # The shape of a value says little on its own: a session id and a token are
    # short and made of ordinary characters. The name of the parameter is what the
    # record's own redactor judges by, and the generator has to judge by it too — a
    # policy of its own would be a second, weaker policy.
    world.patch_candidate(query={name: value})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "did not sanitize" in str(refusal.value), str(refusal.value)


def test_a_value_the_record_left_alone_is_published(world, tmp_path):
    # The catalog id of the record is evidence: the site chose it, it is a run of
    # digits the site asks with, the redactor keeps it, and the contract declares it
    # as the number it is.
    world.patch_observation(
        url=f"{ENDPOINT}?resultset=catalog&sort=popular&query=%3Credacted%3Aquery-param%3E&dest=1259570991"
    )
    world.patch_candidate(query={"dest": "1259570991"})

    written = world.generate(tmp_path)

    proposal = next(path for path in written if path.name == "proposal.md").read_text(encoding="utf-8")
    assert "1259570991" in proposal


@pytest.mark.parametrize(
    "path",
    [
        ("experiment",),
        ("run",),
        ("request", "method"),
        ("request", "url"),
        ("ordering", "orderHash"),
    ],
)
def test_a_capture_that_does_not_describe_its_run_is_refused(world, tmp_path, path):
    # The bundle and the summary describe the same run twice, and every dimension
    # that tells one run from another has to be there on both sides. A dimension
    # that is missing is not a dimension that matches.
    world.drop_from_capture("baseline-1", *path)

    with pytest.raises(generate_proposal.Refused):
        world.generate(tmp_path)


def test_a_candidate_with_another_identity_parameter_is_refused(world, tmp_path):
    # The catalog search and the filters search share the path and differ only in
    # `resultset`. A candidate that names the other one is not this contract, and a
    # proposal that advertised it would pin a request nobody made.
    world.patch_candidate(query={"resultset": "filters"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the contract declares" in str(refusal.value), str(refusal.value)


def test_a_candidate_that_contradicts_the_recorded_request_is_refused(world, tmp_path):
    # The proposal publishes the query of the candidate as the parameters of the
    # contract, and the fixture pins the request that was recorded. The two must
    # say the same thing.
    world.patch_candidate(query={"resultset": "catalog", "sort": "priceup"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "would advertise one request" in str(refusal.value), str(refusal.value)


def test_a_candidate_that_advertises_a_parameter_nobody_sent_is_refused(world, tmp_path):
    world.patch_candidate(query={"resultset": "catalog", "dest": "1259570991"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the evidence does not have" in str(refusal.value), str(refusal.value)


def test_a_refusal_leaves_no_file_behind(world, tmp_path):
    # The whole proposal is rendered and checked before the first byte reaches the
    # disk: a refusal never leaves half a proposal on the disk for a human to
    # clean up.
    world.patch_page("https://www.wildberries.ru/catalog/131954/list.aspx")
    output = tmp_path / "out"

    with pytest.raises(generate_proposal.Refused):
        world.generate(output)

    assert [path for path in output.rglob("*") if path.is_file()] == []


def test_personal_data_under_an_unknown_parameter_name_is_refused(world, tmp_path):
    # The redactor judges a value by the name it was sent under, and it has no rule
    # for a name it has never seen: `Alice` under `customer` is an ordinary word to
    # every shape check. So the contract declares the parameters of its request, and
    # a name it does not declare never reaches a committed file.
    world.patch_candidate(query={"customer": "Alice"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "is not a parameter of this contract" in str(refusal.value), str(refusal.value)


def test_a_value_the_contract_does_not_allow_is_refused(world, tmp_path):
    # The contract says what the values of its parameters may be: the language of
    # the request is `ru`, and a record that says otherwise would publish a request
    # the crawler does not make.
    world.patch_candidate(query={"lang": "en"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the contract does not allow" in str(refusal.value), str(refusal.value)


def test_a_candidate_that_leaves_a_parameter_out_is_refused(world, tmp_path):
    # The parameter table of the proposal is the request the fixture pins. A
    # parameter the request sent and the table does not name is a table that lies
    # about the request.
    world.drop_from_candidate("sort")

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "would omit a parameter" in str(refusal.value), str(refusal.value)


def test_an_observation_of_another_experiment_is_refused(world, tmp_path):
    # The bundle and the summary describe the same run twice, and they agree with
    # each other here. What they do not agree with is the experiment that holds
    # them: an observation has to belong to the experiment it is filed under, or a
    # claim is earned by an experiment the observation does not name.
    world.patch_observation(experiment="sort-priceup")

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "has to belong to the experiment" in str(refusal.value), str(refusal.value)


def test_the_manifest_holds_the_redactor_the_generator_loads(world, tmp_path):
    # The generator loads the record's redactor by its path, and the redactor
    # decides what may be committed. A change of its rules has to invalidate the
    # proposal, so its hash belongs to the manifest beside the generator's.
    world.generate(tmp_path)

    manifest = json.loads(
        (tmp_path / "devtools/wb-research/proposals/2026-01-01-demo/manifest.json").read_text(encoding="utf-8")
    )
    assert "scripts/wb-research/redact.py" in manifest["generator"]
    assert manifest["generator"]["scripts/wb-research/redact.py"] == generate_proposal.sha256_of(
        generate_proposal.ROOT / "scripts/wb-research/redact.py"
    )


def test_a_number_that_looks_like_a_phone_is_refused(world, tmp_path):
    # `dest` is the id the site's own code sends, and the contract names that one
    # value. A rule that accepted any digits would take a phone number for it: the
    # redactor cannot tell them apart, because `dest=9123456789` is not a shape it
    # knows — so the contract has to name the value, not the shape.
    world.patch_candidate(query={"dest": "9123456789"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the contract does not allow" in str(refusal.value), str(refusal.value)


def test_a_page_beyond_the_bounds_of_the_endpoint_is_refused(world, tmp_path):
    # The endpoint answers sixty pages, so a page beyond that is a request it never
    # answers with goods. A number the site chose is named by its bounds.
    world.patch_candidate(query={"page": "61"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the contract does not allow" in str(refusal.value), str(refusal.value)


def test_a_page_below_the_first_page_is_refused(world, tmp_path):
    # The pagination of the catalog is one-based, and the record saw that `page=0`
    # is answered with another envelope — the search result, without products. So
    # the lower bound of the page is one, and a request for the page zero is a
    # request the endpoint does not answer with goods.
    world.patch_candidate(query={"page": "0"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the contract does not allow" in str(refusal.value), str(refusal.value)


def test_the_storefront_request_may_not_carry_the_page_the_crawler_asks_for(world, tmp_path):
    # The page is the crawler's own parameter: the storefront's first request does
    # not send it. A record whose baseline sends it is not the storefront's first
    # request — and the generated test would compare the crawler's request with
    # that baseline and fail on a record the tool accepted.
    world.patch_observation(url=f"{ENDPOINT}?resultset=catalog&sort=popular&query=%3Credacted%3Aquery-param%3E&page=2")
    world.patch_candidate(query={"page": "2"})

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "the storefront's own request carries" in str(refusal.value), str(refusal.value)


def test_a_key_written_twice_in_the_record_is_refused(world, tmp_path):
    # PyYAML keeps the last value of a duplicated key and says nothing about it, so
    # a record that names `sort` twice reads as a record that names it once — and
    # every check after that sees a request nobody sent.
    (world.directory / "investigation.yaml").write_text(
        "id: 2026-01-01-demo\n"
        "status: concluded\n"
        "api_candidates:\n"
        "  - id: storefront-catalog-search\n"
        "    method: GET\n"
        f"    endpoint: {ENDPOINT}\n"
        "    query:\n"
        "      resultset: catalog\n"
        "      sort: popular\n"
        "      sort: priceup\n"
        "    headers: {}\n"
        "    observed_status: 200\n"
        "    response_content_type: text/plain\n"
        "    response_schema: {top_level_keys: [metadata, products, total]}\n"
        "validation:\n"
        "  - claim: the catalog is read with resultset=catalog\n"
        "    state: validated\n"
        "    evidence: [baseline-1]\n",
        encoding="utf-8",
    )

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "twice" in str(refusal.value), str(refusal.value)


def test_a_claim_that_carries_personal_data_is_refused(world, tmp_path):
    # The claim is published in the proposal, and the record's own redactor already
    # knows the rule for a note: an e-mail address, a phone number, an account id
    # and a token are replaced in it. A claim the redactor would change is refused
    # — the wording of a claim is what makes it traceable, so the record rewords
    # it, the tool does not.
    world.patch_claim("the request of alice@example.com returned the catalog")

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "redactor" in str(refusal.value), str(refusal.value)


def test_the_endpoint_of_another_candidate_may_not_carry_a_secret(world, tmp_path):
    # The proposal names the endpoint of every candidate the record holds, not only
    # the one it makes a contract of: a candidate that did not become a contract is
    # still a line of a committed file. A value the record's own redactor would
    # replace is refused there as well.
    world.add_candidate("https://example.test/search?token=secret")

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "may not be published" in str(refusal.value), str(refusal.value)


def test_a_value_appended_to_a_placeholder_is_refused(world, tmp_path):
    # A placeholder is the whole value the redactor left behind: a placeholder
    # with something appended to it is a value the redactor would replace, and it
    # is refused like any other.
    world.add_candidate("https://example.test/search?token=<redacted:token>alice@example.com")

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "may not be published" in str(refusal.value), str(refusal.value)


def test_the_parameters_of_another_candidate_are_not_published(world, tmp_path):
    # The redactor has no rule for a name it has never seen, and `customer=Alice`
    # is an ordinary word under such a name — a person's name all the same. The
    # parameters of a candidate that is not a contract are not the contract's, so
    # the proposal names the place and not the parameters.
    world.add_candidate("https://example.test/search?customer=Alice")

    written = world.generate(tmp_path)
    proposal = next(path for path in written if path.name == "proposal.md").read_text(encoding="utf-8")
    assert "Alice" not in proposal, proposal
    assert "https://example.test/search" in proposal, proposal


def test_a_parameter_sent_twice_is_refused(world, tmp_path):
    # A parameter that occurs twice says two things about the request, and a query
    # read as a map keeps only the last value: the proposal would advertise one
    # request and the fixture would pin another.
    world.patch_observation(
        url=f"{ENDPOINT}?resultset=catalog&sort=popular&sort=priceup&query=%3Credacted%3Aquery-param%3E"
    )

    with pytest.raises(generate_proposal.Refused) as refusal:
        world.generate(tmp_path)
    assert "sent twice" in str(refusal.value), str(refusal.value)
