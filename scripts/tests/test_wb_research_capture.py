"""The collector decides what is evidence and what is noise.

A filter that quietly drops an API call is worse than no filter, so the rules
are tested here: what is kept, what is dropped, what is dropped loudly, and
what happens when the browser's two views of the same session do not line up.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
from pathlib import Path

import pytest

CAPTURE = Path(__file__).resolve().parents[1] / "wb-research" / "capture.py"


def load_collector():
    spec = importlib.util.spec_from_file_location("wb_research_capture", CAPTURE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


capture = load_collector()


@pytest.fixture(autouse=True)
def no_real_browser(monkeypatch):
    """The page action is never run for real: these tests are about the rules."""
    monkeypatch.setattr(capture, "run_page_action", lambda action: None)


CONFIG = {
    "allowedOrigins": ["www.wildberries.ru", "*.wbcontent.net"],
    "allowedPages": ["https://www.wildberries.ru/", "https://www.wildberries.ru/catalog/"],
    "noiseHosts": ["a.wb.ru"],
    "keptResourceTypes": ["Fetch", "XHR"],
    "maxBodyFetches": 50,
}


def entry(url: str, resource_type: str = "Fetch") -> dict:
    return {
        "_resourceType": resource_type,
        "request": {"method": "GET", "url": url},
        "response": {"status": 200, "content": {"mimeType": "application/json", "size": 10}},
    }


def test_an_api_call_on_an_approved_host_is_evidence():
    kept, dropped, _hosts = capture.filter_entries(
        [entry("https://www.wildberries.ru/__internal/u-search?resultset=catalog")], CONFIG
    )
    assert len(kept) == 1
    assert not dropped


def test_an_approved_host_still_drops_a_request_that_is_not_an_api_call():
    _kept, dropped, hosts = capture.filter_entries([entry("https://www.wildberries.ru/icon.svg", "Other")], CONFIG)
    assert dropped == {"type-not-kept": 1}
    assert hosts["type-not-kept"] == {"www.wildberries.ru"}


def test_a_host_marked_as_noise_is_dropped_quietly():
    _kept, dropped, _hosts = capture.filter_entries([entry("https://a.wb.ru/e/pageview", "Ping")], CONFIG)
    assert dropped == {"noise-host": 1}


def test_a_kind_of_request_added_to_the_config_is_an_api_call_on_an_unapproved_host_too():
    # The finding of the review: the kinds that count as an API call were
    # defined twice, so a kind added to the config was an API call on an
    # approved host and ordinary traffic on an unapproved one — and the
    # capture would not stop for the host.
    config = {**CONFIG, "keptResourceTypes": ["Fetch", "XHR", "Document"]}
    _kept, dropped, hosts = capture.filter_entries([entry("https://sentry.example.ru/x", "Document")], config)
    assert dropped == {"host-not-approved": 1}
    assert hosts["host-not-approved"] == {"sentry.example.ru"}


def test_an_api_call_to_an_unapproved_host_is_dropped_loudly():
    _kept, dropped, hosts = capture.filter_entries(
        [entry("https://sentry.example.ru/api/183/envelope", "Fetch")], CONFIG
    )
    assert dropped == {"host-not-approved": 1}
    assert hosts["host-not-approved"] == {"sentry.example.ru"}


def test_an_unapproved_host_that_is_not_an_api_call_is_only_other_traffic():
    _kept, dropped, _hosts = capture.filter_entries([entry("https://cdn.example.ru/font.woff2", "Font")], CONFIG)
    assert dropped == {"other-host": 1}


def test_a_wildcard_origin_covers_its_subdomains_only():
    assert capture.host_matches("static.wbcontent.net", ["*.wbcontent.net"])
    assert not capture.host_matches("wbcontent.net", ["*.wbcontent.net"])


def test_the_hosts_of_a_run_can_be_approved_on_the_command_line(tmp_path):
    config_path = tmp_path / "capture-config.json"
    config_path.write_text(json.dumps({"allowedOrigins": ["www.wildberries.ru"]}), encoding="utf-8")
    config = capture.load_config(config_path, ["api.wildberries.ru"])
    assert "api.wildberries.ru" in config["allowedOrigins"]
    assert config["keptResourceTypes"] == ["Fetch", "XHR"]


def test_the_request_ids_are_joined_by_position():
    entries = [entry("https://www.wildberries.ru/a"), entry("https://www.wildberries.ru/b")]
    listed = [
        {"method": "GET", "url": "https://www.wildberries.ru/a", "requestId": "1"},
        {"method": "GET", "url": "https://www.wildberries.ru/b", "requestId": "2"},
    ]
    assert capture.join_request_ids(entries, listed) == {0: "1", 1: "2"}


def test_a_capture_that_does_not_line_up_with_the_request_list_has_no_ids():
    entries = [entry("https://www.wildberries.ru/a")]
    listed = [{"method": "GET", "url": "https://www.wildberries.ru/other", "requestId": "1"}]
    assert capture.join_request_ids(entries, listed) is None


def test_a_shorter_request_list_has_no_ids():
    entries = [entry("https://www.wildberries.ru/a"), entry("https://www.wildberries.ru/b")]
    listed = [{"method": "GET", "url": "https://www.wildberries.ru/a", "requestId": "1"}]
    assert capture.join_request_ids(entries, listed) is None


def test_two_requests_that_look_the_same_are_not_joined():
    # The finding of the review: the join was by position, so two requests with
    # the same method and URL could be told apart only by the order of the
    # list — and a body attributed to the wrong one of them would be evidence
    # of the wrong request.
    entries = [entry("https://www.wildberries.ru/a"), entry("https://www.wildberries.ru/a")]
    listed = [
        {"method": "GET", "url": "https://www.wildberries.ru/a", "requestId": "1"},
        {"method": "GET", "url": "https://www.wildberries.ru/a", "requestId": "2"},
    ]

    assert capture.join_request_ids(entries, listed) == {}


def test_an_ordinary_request_is_still_joined_when_another_one_is_a_twin():
    entries = [entry("https://www.wildberries.ru/a"), entry("https://www.wildberries.ru/a")]
    listed = [
        {"method": "GET", "url": "https://www.wildberries.ru/a", "requestId": "1"},
        {"method": "GET", "url": "https://www.wildberries.ru/b", "requestId": "2"},
    ]

    # The second entry does not match the second listed request, so the join
    # cannot be verified at all.
    assert capture.join_request_ids(entries, listed) is None


def test_a_request_that_the_list_holds_twice_is_not_joined():
    # The finding of the review: the twins were counted in the recording only.
    # A request made before the recording started and the same request made
    # after it look alike, and the body of the first one was read for the
    # second.
    entries = [entry("https://www.wildberries.ru/api/search")]
    listed = [
        {"method": "GET", "url": "https://www.wildberries.ru/api/search", "requestId": "old"},
        {"method": "GET", "url": "https://www.wildberries.ru/api/search", "requestId": "new"},
    ]

    assert capture.join_request_ids(entries, listed) == {}


def test_the_shape_of_a_json_body_is_its_top_level_keys():
    shape = capture.response_shape({"responseBody": json.dumps({"products": [], "metadata": {"page": 1}})})
    assert shape["available"] is True
    assert shape["isJson"] is True
    assert shape["jsonType"] == "object"
    assert shape["topLevelKeys"] == ["metadata", "products"]


def test_the_shape_of_a_body_that_is_not_json_has_no_keys():
    shape = capture.response_shape({"responseBody": "not json at all"})
    assert shape["available"] is True
    assert shape["isJson"] is False
    assert shape["topLevelKeys"] is None


def test_the_shape_of_a_json_array_has_no_keys_but_says_it_is_an_array():
    shape = capture.response_shape({"responseBody": json.dumps([1, 2, 3])})
    assert shape["jsonType"] == "array"
    assert shape["topLevelKeys"] is None


def test_a_body_that_could_not_be_read_is_unavailable_and_not_empty():
    # The finding of the review: the browser's own error text was written into
    # the annotation, and it quotes the URL and the request. The reason is a
    # code; the text of a reason belongs to the redactor.
    shape = capture.response_shape({"responseBodyError": "No resource with given identifier found"})
    assert shape["available"] is False
    assert shape["reason"] == "browser-error"
    assert "No resource" not in json.dumps(shape)


def test_a_request_without_a_body_says_so_by_a_code():
    shape = capture.response_shape({})

    assert shape["available"] is False
    assert shape["reason"] == "no-body"


def test_the_export_writes_a_redacted_fixture_with_the_timing_and_the_shape(tmp_path, monkeypatch):
    investigation = "2026-11-19-wb-catalog-request-9467"
    raw_dir = tmp_path / investigation
    raw_dir.mkdir()
    (raw_dir / "initial-load.report.json").write_text(json.dumps({"notes": ["step: initial-load"]}), encoding="utf-8")
    har = {
        "log": {
            "entries": [
                {
                    "_resourceType": "Fetch",
                    "startedDateTime": "2026-11-19T13:00:47.027Z",
                    "time": 1139.99,
                    "timings": {"wait": 445.8, "receive": 693.7},
                    "request": {
                        "method": "GET",
                        "url": "https://www.wildberries.ru/__internal/u-search?resultset=catalog",
                        "headers": [
                            {"name": "x-requested-with", "value": "XMLHttpRequest"},
                            {"name": "deviceid", "value": "site_9ee9449a3a1a448393d750b3dd2c22e8"},
                            {"name": "Cookie", "value": "wb_session=abc123def456"},
                        ],
                    },
                    "response": {
                        "status": 200,
                        "headers": [{"name": "content-type", "value": "application/json"}],
                        "content": {"mimeType": "application/json", "size": 51715},
                    },
                    "_capture": {
                        "available": True,
                        "isJson": True,
                        "bodyBytes": 51715,
                        "topLevelKeys": ["metadata", "products"],
                    },
                }
            ]
        }
    }
    (raw_dir / "initial-load.har").write_text(json.dumps(har), encoding="utf-8")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")

    assert capture.export_investigation(investigation) == ["initial-load"]

    fixture = json.loads(
        (
            tmp_path / "investigations" / "2026-11-19-wb-catalog-request-9467" / "evidence" / "initial-load.json"
        ).read_text(encoding="utf-8")
    )
    assert fixture["schema"] == "wb-research/evidence/v1"
    assert fixture["notes"] == ["step: initial-load"]
    entry_capture = fixture["captures"][0]
    assert entry_capture["id"] == "initial-load-001"
    assert entry_capture["timing"]["durationMs"] == 1139.99
    assert entry_capture["schema"]["topLevelKeys"] == ["metadata", "products"]
    assert entry_capture["request"]["headers"]["x-requested-with"] == "XMLHttpRequest"
    assert entry_capture["request"]["headers"]["deviceid"] == "<redacted:deviceid>"
    assert entry_capture["request"]["headers"]["cookie"] == "<redacted:cookie>"
    assert "abc123def456" not in json.dumps(fixture)


def test_a_capture_without_a_body_annotation_says_the_body_was_never_read(tmp_path, monkeypatch):
    raw_dir = tmp_path / "plain"
    raw_dir.mkdir()
    har = {
        "log": {
            "entries": [
                {
                    "_resourceType": "Fetch",
                    "request": {"method": "GET", "url": "https://www.wildberries.ru/a", "headers": []},
                    "response": {"status": 200, "headers": [], "content": {"mimeType": "text/plain", "size": 1}},
                }
            ]
        }
    }
    (raw_dir / "plain.har").write_text(json.dumps(har), encoding="utf-8")
    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")

    capture.export_investigation("plain")
    fixture = json.loads(
        (tmp_path / "investigations" / "plain" / "evidence" / "plain.json").read_text(encoding="utf-8")
    )
    assert fixture["captures"][0]["schema"] == {
        "available": False,
        "reason": "the capture did not read the response body",
    }


def test_a_json_key_that_is_an_address_is_not_kept():
    # The finding of the review: the top-level keys were copied from the answer
    # as they were, and a key can be the address itself.
    shape = capture.response_shape({"responseBody": json.dumps({"alice@example.com": {}, "products": []})})

    assert shape["topLevelKeys"] == ["<redacted:key>", "products"]
    assert "alice" not in json.dumps(shape)


def test_a_json_key_that_is_an_account_id_is_not_kept():
    shape = capture.response_shape({"responseBody": json.dumps({"1234567890": {}})})

    assert shape["topLevelKeys"] == ["<redacted:key>"]
    assert "1234567890" not in json.dumps(shape)


def test_a_step_without_a_command_never_starts_the_recording(tmp_path, monkeypatch):
    # The finding of the review: the command was checked after the recording
    # had started, so an empty command left the capture running — and the next
    # capture recorded into it.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(name="step", investigation="demo", command=["--"], settle=0.0)

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_the_capture_of_the_previous_run_does_not_survive(tmp_path, monkeypatch):
    # The finding of the review: the raw HAR of an earlier run stayed on disk,
    # and when the browser failed to save the new one the old file was read as
    # the capture of this run.
    raw_dir = tmp_path / "demo" / "raw"
    raw_dir.mkdir(parents=True)
    stale = raw_dir / "step.har"
    stale.write_text("{}", encoding="utf-8")

    def fake_chrome_use(*args):
        if args[:3] == ("network", "har", "stop"):
            return subprocess.CompletedProcess(args, 1, "", "the browser did not save it")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://www.wildberries.ru/"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not stale.exists()


def test_the_evidence_of_the_previous_run_does_not_survive(tmp_path, monkeypatch):
    # The finding of the review: the filtered file of the previous run is what
    # the export reads, so a capture that was not saved left the old evidence
    # to be exported as if it were the new one.
    raw_dir = tmp_path / "demo"
    raw_dir.mkdir()
    stale = raw_dir / "step.har"
    stale.write_text("{}", encoding="utf-8")

    def fake_chrome_use(*args):
        if args[:3] == ("network", "har", "stop"):
            return subprocess.CompletedProcess(args, 1, "", "the browser did not save it")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://www.wildberries.ru/"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not stale.exists()
    assert capture.export_investigation("demo") == []


def test_the_exported_fixture_of_the_previous_run_does_not_survive(tmp_path, monkeypatch):
    # The finding of the review: the fixture the repository holds was written by
    # an earlier run, and a capture that failed left it standing as if it were
    # the result of this one.
    evidence_dir = tmp_path / "investigations" / "demo" / "evidence"
    evidence_dir.mkdir(parents=True)
    stale = evidence_dir / "step.json"
    stale.write_text("{}", encoding="utf-8")

    def fake_chrome_use(*args):
        if args[:3] == ("network", "har", "stop"):
            return subprocess.CompletedProcess(args, 1, "", "the browser did not save it")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://www.wildberries.ru/"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not stale.exists()


def test_the_report_of_the_previous_run_does_not_survive(tmp_path, monkeypatch):
    # The finding of the review: the report of an earlier run stayed on disk,
    # and a capture that failed before writing one left its counts and its
    # notes standing as if they were the result of this run.
    raw_dir = tmp_path / "demo"
    raw_dir.mkdir()
    stale = raw_dir / "step.report.json"
    stale.write_text("{}", encoding="utf-8")

    def fake_chrome_use(*args):
        if args[:3] == ("network", "har", "stop"):
            return subprocess.CompletedProcess(args, 1, "", "the browser did not save it")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://www.wildberries.ru/"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not stale.exists()


def test_a_capture_that_could_not_clear_the_request_log_is_refused(tmp_path, monkeypatch):
    # The finding of the review: the request log is what the bodies are looked
    # up in, and the join with the recording is positional. A log that still
    # holds the requests of an earlier run shifts that join, and a body may be
    # attributed to the wrong request.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        if args[:3] == ("network", "requests", "--clear"):
            return subprocess.CompletedProcess(args, 1, "", "the browser refused")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://www.wildberries.ru/"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("har" in call for call in calls)


def test_an_investigation_id_that_is_a_path_is_refused():
    # The finding of the review: the id became a directory name as it was, so
    # an id with a `..` in it pointed at another directory than the one named.
    with pytest.raises(SystemExit):
        capture.safe_investigation("../../../scripts/ci")


def test_an_investigation_id_that_is_absolute_is_refused():
    with pytest.raises(SystemExit):
        capture.safe_investigation("/etc")


def test_an_ordinary_investigation_id_survives():
    assert capture.safe_investigation("2026-11-19-wb-network-events-demo") == "2026-11-19-wb-network-events-demo"


def test_an_export_without_a_capture_fails(tmp_path, monkeypatch):
    # The finding of the review: the export always reported success, even when
    # it had written nothing.
    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")

    assert capture.main(["export", "--investigation", "empty"]) == capture.EXIT_FAILED


def test_an_export_with_a_capture_succeeds(tmp_path, monkeypatch):
    raw_dir = tmp_path / "demo"
    raw_dir.mkdir()
    har = {
        "log": {
            "entries": [
                {
                    "_resourceType": "Fetch",
                    "request": {"method": "GET", "url": "https://www.wildberries.ru/a", "headers": []},
                    "response": {"status": 200, "headers": [], "content": {"mimeType": "text/plain", "size": 1}},
                }
            ]
        }
    }
    (raw_dir / "step.har").write_text(json.dumps(har), encoding="utf-8")
    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")

    assert capture.main(["export", "--investigation", "demo"]) == capture.EXIT_OK


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__]))


def test_a_page_action_that_could_change_the_site_is_refused(tmp_path, monkeypatch):
    # The finding of the review: the page action was an arbitrary command, and
    # a capture of a logged-in session must not be able to change the account.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "click", "#add-to-cart"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_shell_is_not_a_page_action(tmp_path, monkeypatch):
    # The finding of the review: a shell runs whatever it is told to, so it is
    # not a page action.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["bash", "-c", "chrome-use click #buy"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_several_read_only_page_actions_are_one_step(tmp_path, monkeypatch):
    # The actions of a step are separated by `--`, and each of them is checked.
    actions = capture.page_actions(
        ["chrome-use", "scroll", "down", "20000", "--", "chrome-use", "scroll", "down", "20000"]
    )

    capture.check_read_only(actions, CONFIG["allowedOrigins"], CONFIG["allowedPages"])

    assert len(actions) == 2


def test_a_program_that_is_called_chrome_use_is_not_the_chrome_use_command(tmp_path, monkeypatch):
    # The finding of the review: the name of the program was enough, so a path
    # to any program of that name was run.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step",
        investigation="demo",
        command=["/tmp/chrome-use", "open", "https://www.wildberries.ru/"],
        settle=0.0,
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_only_open_an_approved_host(tmp_path, monkeypatch):
    # The finding of the review: a navigation is a request, and a request may
    # change what it is asked to change.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "https://example.test/buy"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_not_open_a_host_by_an_upper_case_scheme(tmp_path, monkeypatch):
    # The finding of the review: the scheme of a URL is not case-sensitive, so
    # the check that looks at it was not.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step", investigation="demo", command=["chrome-use", "open", "HTTPS://example.test/buy"], settle=0.0
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_not_open_a_page_the_config_does_not_approve(tmp_path, monkeypatch):
    # The finding of the review: an approved host answers a GET that changes
    # the account as well, so the host alone is not enough — the page is what
    # the config names.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step",
        investigation="demo",
        command=["chrome-use", "open", "https://www.wildberries.ru/basket/add?nm=1"],
        settle=0.0,
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_not_hide_a_page_behind_a_relative_segment(tmp_path, monkeypatch):
    # The browser resolves the segments of a path before it asks for the page,
    # so a check that does not resolve them approves another page.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step",
        investigation="demo",
        command=["chrome-use", "open", "https://www.wildberries.ru/catalog/../basket/add"],
        settle=0.0,
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_open_a_page_the_config_approves(tmp_path, monkeypatch):
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step",
        investigation="demo",
        command=["chrome-use", "open", "https://www.wildberries.ru/catalog/131954/list.aspx?search=x"],
        settle=0.0,
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert any("start" in call for call in calls)


@pytest.mark.parametrize(
    "url",
    [
        "https://www.wildberries.ru/catalog/%2e%2e/account/delete",
        "https://www.wildberries.ru/catalog/%2E%2E/account/delete",
        "https://www.wildberries.ru/catalog/.%2e/account/delete",
        "https://www.wildberries.ru/catalog/%2e./account/delete",
    ],
)
def test_a_page_action_may_not_hide_a_page_behind_an_encoded_dot_segment(url, tmp_path, monkeypatch):
    # The finding of the review: a browser resolves `%2e%2e` as `..`, so a
    # check that only knows the written segments approves another page.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(name="step", investigation="demo", command=["chrome-use", "open", url], settle=0.0)

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)


def test_a_page_action_may_not_open_another_port_of_an_approved_host(tmp_path, monkeypatch):
    # The finding of the review: the origin of a URL is the scheme, the host
    # and the port — the same host on another port is another service.
    calls = []

    def fake_chrome_use(*args):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(capture, "RAW_ROOT", tmp_path)
    monkeypatch.setattr(capture, "EVIDENCE_ROOT", tmp_path / "investigations")
    monkeypatch.setattr(capture, "chrome_use", fake_chrome_use)
    args = argparse.Namespace(
        name="step",
        investigation="demo",
        command=["chrome-use", "open", "https://www.wildberries.ru:8443/catalog/131954/list.aspx"],
        settle=0.0,
    )

    with pytest.raises(SystemExit):
        capture.capture_step(args, CONFIG)

    assert not any("start" in call for call in calls)
