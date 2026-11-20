"""Tests for the contract-validation tool.

The tool decides what a sample of one proves and what it does not, so the rules
are what is tested here: one variation per experiment, a plan that runs only
what the operator approved, a refusal that is never a validation, and header
values that never reach a file the repository holds.

Everything runs offline. The browser is replaced by a function that returns the
answer the test asks for, because a live site is not a test dependency.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

VALIDATE = Path(__file__).resolve().parents[1] / "wb-research" / "validate.py"
INVESTIGATION = "2026-11-21-wb-catalog-contract"
ENDPOINT = "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"
PAGE = "https://www.wildberries.ru/catalog/131954/list.aspx?search=колонки"
BASE_QUERY = {"page": "1", "query": "колонки", "resultset": "catalog", "sort": "popular"}
HEADERS = ["deviceid", "x-requested-with"]


def load_validator():
    spec = importlib.util.spec_from_file_location("wb_research_validate", VALIDATE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validate = load_validator()


def baseline(repeat: int = 2) -> dict:
    return {"name": "baseline", "kind": "replay", "repeat": repeat}


def variant(name: str, **fields) -> dict:
    return {"name": name, "repeat": 2, **fields}


def answer(status: int = 200, ids=(1, 2), total: int = 10, error: str | None = None) -> dict:
    """What the page script reports for one request."""
    return {
        "status": status,
        "contentType": "application/json" if status == 200 else "text/plain",
        "bodyBytes": 233 if status == 200 else 0,
        "isJson": status == 200,
        "topLevelKeys": ["products", "total"] if status == 200 else None,
        "productCount": len(ids) if status == 200 else None,
        "total": total if status == 200 else None,
        "productIds": list(ids) if status == 200 else [],
        "droppedByBrowser": [],
        "error": error,
    }


@pytest.fixture
def world(tmp_path, monkeypatch):
    """An investigation, an approved capture config and a local request file."""
    monkeypatch.setattr(validate, "INVESTIGATIONS", tmp_path / "investigations")
    monkeypatch.setattr(validate, "RAW_ROOT", tmp_path / "raw")
    monkeypatch.setattr(validate.time, "sleep", lambda seconds: None)
    config = tmp_path / "capture-config.json"
    config.write_text(
        json.dumps(
            {
                "allowedOrigins": ["www.wildberries.ru"],
                "allowedPages": ["https://www.wildberries.ru/catalog/"],
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(validate, "CONFIG_PATH", config)

    def make(experiments: list[dict] | None = None, request: dict | None = None, query: dict | None = None):
        directory = tmp_path / "investigations" / INVESTIGATION
        (directory / "evidence").mkdir(parents=True, exist_ok=True)
        record = {
            "id": INVESTIGATION,
            "api_candidates": [
                {
                    "id": "storefront-catalog-search",
                    "method": "GET",
                    "endpoint": ENDPOINT,
                    "query": BASE_QUERY,
                    "headers": {"deviceid": "<redacted:deviceid>", "x-requested-with": "XMLHttpRequest"},
                }
            ],
        }
        (directory / "investigation.yaml").write_text(yaml.safe_dump(record, sort_keys=False), encoding="utf-8")
        plan = {
            "schema": validate.PLAN_SCHEMA,
            "id": INVESTIGATION,
            "candidate": "storefront-catalog-search",
            "page": PAGE,
            "request": {"query": query or {}, "headers": HEADERS, "credentials": "include", **(request or {})},
            "min_interval_seconds": 0,
            "experiments": experiments or [baseline()],
        }
        (directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
        raw = tmp_path / "raw" / INVESTIGATION
        raw.mkdir(parents=True, exist_ok=True)
        (raw / "request.json").write_text(
            json.dumps(
                {
                    "headers": {"deviceid": "site_test_value", "x-requested-with": "XMLHttpRequest"},
                    "query": {"query": "колонки"},
                }
            ),
            encoding="utf-8",
        )
        return SimpleNamespace(directory=directory, raw=raw)

    return make


def fake_browser(monkeypatch, results: list[dict]) -> dict:
    """A browser that answers the probes in order and records their requests."""
    calls: dict[str, object] = {"probes": [], "reloads": 0, "opened": []}

    def probe(request, session):
        calls["probes"].append(request)
        return results[len(calls["probes"]) - 1]

    def open_page(page, session, config):
        calls["opened"].append(page)

    def reload_page(session):
        calls["reloads"] += 1
        return True

    monkeypatch.setattr(validate, "probe", probe)
    monkeypatch.setattr(validate, "open_page", open_page)
    monkeypatch.setattr(validate, "reload_page", reload_page)
    return calls


def read_summary(directory: Path) -> dict:
    return yaml.safe_load((directory / validate.RECORD_FILE).read_text(encoding="utf-8"))


def test_the_reference_cannot_vary(world, monkeypatch):
    world([variant("first", vary={"sort": "priceup"})])
    fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="cannot vary"):
        validate.approve(INVESTIGATION)


def test_a_variant_that_changes_two_things_is_refused(world, monkeypatch):
    world([baseline(), variant("both", vary={"sort": "priceup"}, remove=["page"])])
    fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="more than one thing"):
        validate.approve(INVESTIGATION)


def test_a_variant_that_changes_nothing_is_refused(world, monkeypatch):
    world([baseline(), variant("noop", vary={"sort": "popular"})])
    fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="does not change"):
        validate.approve(INVESTIGATION)


def test_an_unknown_field_to_compare_is_refused(world, monkeypatch):
    world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["colour"])])
    fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="unknown fields"):
        validate.approve(INVESTIGATION)


def test_an_unapproved_plan_does_not_run(world, monkeypatch):
    place = world([baseline()])
    plan = yaml.safe_load((place.directory / "experiments.yaml").read_text(encoding="utf-8"))
    plan["status"] = "draft"
    (place.directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
    calls = fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="not approved"):
        validate.run(INVESTIGATION, None, False)

    assert calls["probes"] == []


def test_a_changed_scope_needs_a_new_approval(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)

    plan = yaml.safe_load((place.directory / "experiments.yaml").read_text(encoding="utf-8"))
    plan["experiments"][1]["vary"] = {"sort": "benefit"}
    (place.directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
    calls = fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="approve it again"):
        validate.run(INVESTIGATION, None, False)

    assert calls["probes"] == []


def test_a_changed_base_query_value_needs_a_new_approval(world, monkeypatch):
    place = world([baseline()])
    validate.approve(INVESTIGATION)

    plan = yaml.safe_load((place.directory / "experiments.yaml").read_text(encoding="utf-8"))
    plan["request"]["query"] = {"sort": "priceup"}
    (place.directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
    calls = fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="approve it again"):
        validate.run(INVESTIGATION, None, False)

    assert calls["probes"] == []


def test_a_page_outside_the_config_is_refused(world, monkeypatch):
    place = world([baseline()])
    plan = yaml.safe_load((place.directory / "experiments.yaml").read_text(encoding="utf-8"))
    plan["page"] = "https://example.com/catalog/"
    (place.directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
    fake_browser(monkeypatch, [])

    with pytest.raises(SystemExit, match="not approved"):
        validate.approve(INVESTIGATION)


def test_a_missing_header_value_stops_the_run(world, monkeypatch):
    place = world([baseline()])
    validate.approve(INVESTIGATION)
    (place.raw / "request.json").write_text(
        json.dumps({"headers": {"x-requested-with": "XMLHttpRequest"}}), encoding="utf-8"
    )
    fake_browser(monkeypatch, [answer()])

    with pytest.raises(SystemExit, match="deviceid"):
        validate.run(INVESTIGATION, None, False)


def test_a_request_file_inside_the_repository_is_refused(world, monkeypatch):
    place = world([baseline()])
    plan = yaml.safe_load((place.directory / "experiments.yaml").read_text(encoding="utf-8"))
    plan["request_file"] = str(Path(validate.REPO_ROOT) / "headers.json")
    (place.directory / "experiments.yaml").write_text(yaml.safe_dump(plan, sort_keys=False), encoding="utf-8")
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer()])

    with pytest.raises(SystemExit, match="outside the repository"):
        validate.run(INVESTIGATION, None, False)


def test_a_baseline_reproduced_and_a_variant_validated(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    calls = fake_browser(
        monkeypatch,
        [answer(ids=(1, 2)), answer(ids=(1, 2)), answer(ids=(2, 1)), answer(ids=(2, 1))],
    )

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_OK

    summary = read_summary(place.directory)
    assert summary["baseline_reproduced"] is True
    assert summary["experiments"][0]["state"] == validate.REPRODUCED
    assert summary["experiments"][1]["state"] == validate.VALIDATED
    assert summary["checks"][0]["met"] == [True, True]
    assert summary["not_validated"] == []
    assert len(calls["probes"]) == 4


def test_the_replay_changes_one_parameter_and_keeps_the_rest(world, monkeypatch):
    world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    calls = fake_browser(monkeypatch, [answer(), answer(), answer(ids=(2, 1)), answer(ids=(2, 1))])

    validate.run(INVESTIGATION, None, False)

    first, third = calls["probes"][0], calls["probes"][2]
    assert "sort=popular" in first["url"]
    assert "sort=priceup" in third["url"]
    assert "page=1" in third["url"] and "resultset=catalog" in third["url"]
    assert third["headers"] == {"deviceid": "site_test_value", "x-requested-with": "XMLHttpRequest"}


def test_a_single_sample_does_not_validate(world, monkeypatch):
    place = world(
        [baseline(repeat=1), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"], repeat=1)]
    )
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(ids=(1, 2)), answer(ids=(2, 1))])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_FAILED

    summary = read_summary(place.directory)
    assert summary["experiments"][1]["state"] == validate.OBSERVED
    assert [item["experiment"] for item in summary["not_validated"]] == ["sorted"]
    assert "one sample" in summary["not_validated"][0]["reason"]


def test_a_stable_contradiction_is_rejected(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(), answer()])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_FAILED

    summary = read_summary(place.directory)
    assert summary["experiments"][1]["state"] == validate.REJECTED


def test_a_blocked_variant_is_not_a_validation(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(status=403), answer(status=403)])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_BLOCKED

    summary = read_summary(place.directory)
    blocked = summary["experiments"][1]
    assert blocked["outcome"] == validate.BLOCKED
    assert blocked["state"] == validate.INCONCLUSIVE
    assert "refused" in blocked["reason"]
    assert summary["experiments"][1]["state"] != validate.VALIDATED
    assert [item["experiment"] for item in summary["not_validated"]] == ["sorted"]


def test_a_failed_probe_is_not_a_result(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(error="fetch_failed")])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_FAILED

    summary = read_summary(place.directory)
    assert summary["experiments"][1]["outcome"] == validate.FAILED
    assert summary["experiments"][1]["state"] == validate.INCONCLUSIVE


def test_a_reload_is_a_reload_and_not_another_experiment(world, monkeypatch):
    world([baseline(), {"name": "after-reload", "kind": "reload", "expect": "same", "repeat": 2}])
    validate.approve(INVESTIGATION)
    calls = fake_browser(monkeypatch, [answer(), answer(), answer(), answer()])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_OK
    assert calls["reloads"] == 2


def test_the_headers_values_never_reach_a_committed_file(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(ids=(2, 1)), answer(ids=(2, 1))])
    validate.run(INVESTIGATION, None, False)

    written = [
        (place.directory / validate.RECORD_FILE).read_text(encoding="utf-8"),
        (place.directory / validate.EVIDENCE_FILE).read_text(encoding="utf-8"),
        (place.directory / validate.REPORT_FILE).read_text(encoding="utf-8"),
    ]
    for text in written:
        assert "site_test_value" not in text
    evidence = json.loads((place.directory / validate.EVIDENCE_FILE).read_text(encoding="utf-8"))
    sent = evidence["captures"][0]["request"]["headersSent"]
    assert "deviceid" in sent
    assert "x-requested-with" in sent


def test_the_evidence_keeps_the_names_the_browser_dropped(world, monkeypatch):
    place = world([baseline()], request={"headers": ["deviceid", "referer"]})
    (place.raw / "request.json").write_text(
        json.dumps(
            {
                "headers": {"deviceid": "site_test_value", "referer": "https://www.wildberries.ru/catalog/"},
                "query": {"query": "колонки"},
            }
        ),
        encoding="utf-8",
    )
    validate.approve(INVESTIGATION)
    result = answer()
    result["droppedByBrowser"] = ["referer"]
    fake_browser(monkeypatch, [result, result])
    validate.run(INVESTIGATION, None, False)

    evidence = json.loads((place.directory / validate.EVIDENCE_FILE).read_text(encoding="utf-8"))
    request = evidence["captures"][0]["request"]
    assert request["headersDroppedByBrowser"] == ["referer"]
    assert request["headersSent"] == ["deviceid"]


def test_a_redacted_query_value_comes_from_the_local_file(world, monkeypatch):
    place = world([baseline()], query={"query": "<redacted:query-param>"})
    validate.approve(INVESTIGATION)
    calls = fake_browser(monkeypatch, [answer(), answer()])

    validate.run(INVESTIGATION, None, False)

    assert "query=%D0%BA%D0%BE%D0%BB%D0%BE%D0%BD%D0%BA%D0%B8" in calls["probes"][0]["url"]
    report = (place.directory / validate.REPORT_FILE).read_text(encoding="utf-8")
    assert "колонки" not in report


def test_a_redacted_query_value_without_a_local_value_is_refused(world, monkeypatch):
    place = world([baseline()], query={"query": "<redacted:query-param>"})
    validate.approve(INVESTIGATION)
    (place.raw / "request.json").write_text(
        json.dumps({"headers": {"deviceid": "site_test_value", "x-requested-with": "XMLHttpRequest"}}),
        encoding="utf-8",
    )
    fake_browser(monkeypatch, [answer(), answer()])

    with pytest.raises(SystemExit, match="no value"):
        validate.run(INVESTIGATION, None, False)


def test_a_dry_run_opens_no_browser(world, monkeypatch):
    world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    calls = fake_browser(monkeypatch, [answer()])

    assert validate.run(INVESTIGATION, None, True) == validate.EXIT_OK
    assert calls["probes"] == []
    assert calls["opened"] == []


def test_the_report_says_what_was_not_established(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(), answer()])

    validate.run(INVESTIGATION, None, False)

    report = (place.directory / validate.REPORT_FILE).read_text(encoding="utf-8")
    assert "What was not established" in report
    assert "`sorted`" in report
    assert "rejected" in report
    assert "No bypass" in report


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__]))


def test_a_variant_without_an_expectation_is_observed_not_judged(world, monkeypatch):
    place = world([baseline(), variant("page-0", vary={"page": "0"}, repeat=1)])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(ids=(), total=0)])

    assert validate.run(INVESTIGATION, None, False) == validate.EXIT_OK

    summary = read_summary(place.directory)
    assert summary["experiments"][1]["state"] == validate.OBSERVED
    assert summary["experiments"][1]["comparisons"][0]["status"] == "changed"
    assert summary["not_validated"] == []


def test_the_summary_keeps_a_hash_and_the_evidence_keeps_the_ids(world, monkeypatch):
    place = world([baseline()])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(ids=(7, 8)), answer(ids=(7, 8))])

    validate.run(INVESTIGATION, None, False)

    summary_observation = read_summary(place.directory)["experiments"][0]["observations"][0]
    assert "productIds" not in summary_observation["ordering"]
    assert summary_observation["ordering"]["orderHash"]
    evidence = json.loads((place.directory / validate.EVIDENCE_FILE).read_text(encoding="utf-8"))
    assert evidence["captures"][0]["ordering"]["productIds"] == [7, 8]


def test_the_summary_has_no_yaml_anchors(world, monkeypatch):
    place = world([baseline(), variant("sorted", vary={"sort": "priceup"}, expect="changed", compare=["order"])])
    validate.approve(INVESTIGATION)
    fake_browser(monkeypatch, [answer(), answer(), answer(ids=(2, 1)), answer(ids=(2, 1))])

    validate.run(INVESTIGATION, None, False)

    text = (place.directory / validate.RECORD_FILE).read_text(encoding="utf-8")
    assert "&id" not in text
    assert "*id" not in text
