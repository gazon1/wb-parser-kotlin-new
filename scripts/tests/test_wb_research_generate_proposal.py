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
            "url": f"https://www.wildberries.ru/x?{query}",
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

    captures = []
    for experiment in experiments():
        for item in experiment["observations"]:
            ids = {"baseline": [1, 2], "after-reload": [1, 2], "sort-priceup": [3, 4], "page-2": [5, 6]}.get(
                item["experiment"], []
            )
            captures.append({**item, "ordering": {**item["ordering"], "productIds": ids}})
    (directory / "evidence" / "validation.json").write_text(
        json.dumps({"schema": "wb-research/validation/v1", "captures": captures}), encoding="utf-8"
    )

    def write(state: str = "validated", with_validation: bool = True, status: str = "concluded") -> Path:
        record = {
            "id": "2026-01-01-demo",
            "status": status,
            "api_candidates": [
                {
                    "id": CANDIDATE,
                    "method": "GET",
                    "endpoint": ENDPOINT,
                    "query": {"resultset": "catalog"},
                    "headers": {"deviceid": "<redacted:deviceid>", "referer": "https://www.wildberries.ru/"},
                    "observed_status": 200,
                    "response_content_type": "text/plain",
                    "response_schema": {"top_level_keys": ["metadata", "products", "total"]},
                }
            ],
            "validation": [
                {"claim": "the catalog is read with resultset=catalog", "state": state, "evidence": ["baseline-1"]}
            ],
        }
        (directory / "investigation.yaml").write_text(yaml.safe_dump(record, allow_unicode=True), encoding="utf-8")
        validation = directory / "validation.yaml"
        if with_validation:
            validation.write_text(
                yaml.safe_dump({"candidate": CANDIDATE, "experiments": experiments()}, allow_unicode=True),
                encoding="utf-8",
            )
        else:
            validation.unlink(missing_ok=True)
        return investigations

    write()
    return SimpleWorld(investigations, directory, write)


class SimpleWorld:
    def __init__(self, investigations: Path, directory: Path, write) -> None:
        self.investigations = investigations
        self.directory = directory
        self.write = write

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


def test_the_fixture_carries_the_order_and_no_captured_body(world, tmp_path):
    written = world.generate(tmp_path)

    fixture = json.loads(next(path for path in written if path.name == "baseline-1.json").read_text(encoding="utf-8"))
    assert fixture["ordering"]["productIds"] == [1, 2]
    assert fixture["bodySource"] == "synthesized-from-the-recorded-shape"
    assert fixture["body"]["products"] == [{"id": 1, "name": "Товар 1"}, {"id": 2, "name": "Товар 2"}]


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
