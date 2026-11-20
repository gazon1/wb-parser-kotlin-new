from __future__ import annotations

import asyncio
import contextlib
import importlib.util
import inspect
import io
import json
import sys
import threading
import time
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from functools import cache
from pathlib import Path
from unittest import mock

import httpx
import pytest
from typer.testing import CliRunner


@cache
def _load_wbparser():
    path = Path(__file__).resolve().parents[1] / "parser" / "wbparser.py"
    # The script imports its generated sibling by name; running it directly puts the
    # script's directory on `sys.path`, and loading it from here must do the same.
    # Cached, so a test cannot keep appending the same entry to the global path.
    parser_dir = str(path.parent)
    if parser_dir not in sys.path:
        sys.path.insert(0, parser_dir)
    spec = importlib.util.spec_from_file_location("wbparser_cli", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    # Pydantic resolves the script's postponed annotations through sys.modules.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _target(mod, name: str, **kwargs):
    base = {
        "id": "00000000-0000-0000-0000-000000000001",
        "name": name,
        "isActive": True,
        "startUrl": "https://example.invalid",
        "maxDepth": 1,
        "rootCategoryName": None,
        "knownPagesLimit": None,
        "wbCatalogId": "9467",
        "lastCrawledAt": None,
        "consecutiveFailures": 0,
        "nextAttemptAt": None,
        "leaseUntil": None,
    }
    base.update(kwargs)
    return mod.Target.model_validate(base)


NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
CRON = "0 0 */2 * * *"


def _substitute(*argv: str) -> list[str]:
    """A substitute executable: it prints its own argv and succeeds."""
    return [sys.executable, "-c", "import sys; print(' '.join(sys.argv[1:]), flush=True)", *argv]


async def _not_running(service: str) -> bool:
    """The stack is not running — and no compose command is started to find out."""
    return False


def test_the_answer_the_server_writes_parses() -> None:
    mod = _load_wbparser()
    due_target = _target(mod, "due", id="due", lastCrawledAt=datetime(2026, 11, 17, 8, 30, tzinfo=UTC))
    upcoming_target = _target(mod, "up", id="up", lastCrawledAt=datetime(2026, 11, 17, 9, 0, tzinfo=UTC))
    payload = {
        "cron": CRON,
        "freshnessMinutes": 360,
        "activeTargets": 1,
        "targets": [_target(mod, "target")],
        "due": [due_target],
        "upcoming": [{"at": "2026-11-17T11:30:00Z", "target": upcoming_target}],
        "nextRun": "2026-11-17T12:00:00Z",
        "earliestDue": None,
        "limit": 15,
    }
    schedule = mod.Schedule.model_validate(payload)

    assert schedule.cron == CRON
    assert schedule.freshness_minutes == 360
    assert schedule.active_targets == 1
    assert schedule.limit == 15
    assert [t.id for t in schedule.due] == ["due"]
    assert schedule.upcoming[0].target.id == "up"
    assert schedule.next_run == datetime(2026, 11, 17, 12, 0, tzinfo=UTC)
    assert schedule.earliest_due is None

    data = schedule.model_dump(by_alias=True, mode="json")
    assert data["freshnessMinutes"] == 360
    assert data["nextRun"] == "2026-11-17T12:00:00Z"
    assert data["due"][0]["lastCrawledAt"] == "2026-11-17T08:30:00Z"


def test_mixed_payload_contract() -> None:
    mod = _load_wbparser()
    inner = mod.Stats.model_validate(
        {
            "totalItems": 1,
            "totalProducts": 2,
            "totalTargets": 3,
            "withCashback": 4,
            "productsWithCashback": 5,
            "newestScrapedAt": "2026-11-17T09:00:00Z",
            "asOf": "2026-11-17T09:00:00Z",
            "instanceId": "i",
            "dataAsOf": None,
        }
    )
    data = {
        "mixed": [inner, {"plain": None}],
        "freshnessMinutes": 360,
        "nextRun": "2026-11-17T12:00:00Z",
        "when": None,
        "status": mod.Sort.date_desc,
    }
    text = mod._encode_json(data)
    assert "[green]" not in text
    parsed = json.loads(text)
    assert parsed["freshnessMinutes"] == 360
    assert parsed["nextRun"] == "2026-11-17T12:00:00Z"
    assert parsed["when"] is None
    assert parsed["status"] == "DateDesc"
    assert parsed["mixed"][0]["totalItems"] == 1
    assert parsed["mixed"][0]["asOf"] == "2026-11-17T09:00:00Z"
    assert parsed["mixed"][0]["newestScrapedAt"] == "2026-11-17T09:00:00Z"
    assert parsed["mixed"][1] == {"plain": None}


def test_partition_by_due_uses_latest_of_lease_and_backoff() -> None:
    mod = _load_wbparser()
    now = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
    target = _target(
        mod,
        "leased-and-backed-off",
        leaseUntil=now + timedelta(hours=1),
        nextAttemptAt=now + timedelta(hours=2),
    )

    due, upcoming = mod._partition_by_due([target], now, freshness_minutes=360)

    assert due == []
    assert upcoming == [(now + timedelta(hours=2), target)]


def test_partition_by_due_uses_latest_of_lease_and_freshness() -> None:
    mod = _load_wbparser()
    now = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
    target = _target(
        mod,
        "leased-and-fresh",
        lastCrawledAt=now - timedelta(minutes=30),
        leaseUntil=now + timedelta(minutes=10),
    )

    due, upcoming = mod._partition_by_due([target], now, freshness_minutes=360)

    assert due == []
    assert upcoming == [(now + timedelta(minutes=330), target)]


def test_catalog_json_keeps_decimal_precision() -> None:
    mod = _load_wbparser()
    # The last digit is past what a float can hold, so the float path collapses the
    # value to 0.1 and the exact path does not.
    body = b'{"cashbackPercent": 0.1000000000000000000000000001}'

    assert mod._decode_json(body, decimal_numbers=True)["cashbackPercent"] == Decimal("0.1000000000000000000000000001")
    assert Decimal(mod._decode_json(body)["cashbackPercent"]) != Decimal("0.1000000000000000000000000001")


def test_json_output_writes_money_as_numbers() -> None:
    mod = _load_wbparser()
    text = mod._encode_json({"cashbackRubles": Decimal("0.1000000000000000000000000001")})

    assert '"cashbackRubles": 0.1000000000000000000000000001' in text
    assert '"0.1000000000000000000000000001"' not in text


# The responses in `fixtures/` are the application's own Jackson output for the real
# catalog DTOs (`CatalogOpenApiTest`'s mapper), captured rather than written by hand.
# They pin the names, the nullability and the decimal scale the models must accept.
FIXTURES = Path(__file__).resolve().parent / "fixtures"


def test_real_items_response_matches_the_generated_model() -> None:
    mod = _load_wbparser()
    body = (FIXTURES / "catalog-items.json").read_bytes()
    page = mod.ItemsPage.model_validate(mod._decode_json(body, decimal_numbers=True))

    assert page.total_count == 1
    assert page.page_size == 24
    assert str(page.items[0].cashback_percent) == "14.50"
    assert str(page.items[0].cashback_rubles) == "28.86"


def test_real_stats_response_matches_the_generated_model() -> None:
    mod = _load_wbparser()
    body = (FIXTURES / "catalog-stats.json").read_bytes()
    stats = mod.Stats.model_validate(mod._decode_json(body, decimal_numbers=True))

    assert stats.total_products == 2
    assert stats.instance_id == "b2d4f6a8-1c3e-4f5a-9b7c-0d1e2f3a4b5c"


def test_history_json_exercises_command(monkeypatch, capsys) -> None:
    mod = _load_wbparser()
    job = mod.Job.model_validate(
        {
            "id": "11111111-1111-1111-1111-111111111111",
            "status": "Completed",
            "pagesCrawled": 10,
            "itemsSaved": 5,
            "startedAt": "2026-11-17T10:00:00Z",
            "completedAt": "2026-11-17T10:05:00Z",
            "triggeredBy": "schedule",
            "errorMessage": None,
        }
    )

    async def fake_history(api, limit=15):
        return {
            "jobs": [job],
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "nextRun": datetime(2026, 11, 17, 12, 0, tzinfo=UTC),
            "earliestDue": datetime(2026, 11, 17, 11, 0, tzinfo=UTC),
        }

    monkeypatch.setattr(mod, "_fetch_history", fake_history)
    monkeypatch.setattr(mod, "JSON", True)

    mod.history(limit=15)

    payload = json.loads(capsys.readouterr().out)
    assert isinstance(payload["runs"][0], dict)
    assert payload["runs"][0]["id"] == "11111111-1111-1111-1111-111111111111"
    assert payload["runs"][0]["status"] == "Completed"
    assert payload["schedule"] == {
        "cron": "0 0 */4 * * *",
        "freshnessMinutes": 360,
        "nextRun": "2026-11-17T12:00:00Z",
        "earliestDue": "2026-11-17T11:00:00Z",
    }


def test_schedule_json_emits_nested_objects(monkeypatch, capsys) -> None:
    mod = _load_wbparser()
    due = _target(mod, "due", lastCrawledAt=datetime(2026, 11, 17, 8, 0, tzinfo=UTC))
    upcoming_target = _target(mod, "later", lastCrawledAt=datetime(2026, 11, 17, 9, 0, tzinfo=UTC))
    schedule = mod.Schedule.model_validate(
        {
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "activeTargets": 2,
            "targets": [due.model_dump(by_alias=True), upcoming_target.model_dump(by_alias=True)],
            "due": [due.model_dump(by_alias=True)],
            "upcoming": [{"at": "2026-11-17T14:00:00Z", "target": upcoming_target.model_dump(by_alias=True)}],
            "nextRun": "2026-11-17T12:00:00Z",
            "earliestDue": "2026-11-17T10:00:00Z",
            "limit": 15,
        }
    )

    async def fake_schedule(api, limit=15):
        return schedule

    monkeypatch.setattr(mod, "_fetch_schedule", fake_schedule)
    monkeypatch.setattr(mod, "JSON", True)

    mod.schedule(limit=15)

    payload = json.loads(capsys.readouterr().out)
    assert isinstance(payload["due"][0], dict)
    assert payload["due"][0]["name"] == "due"
    assert isinstance(payload["upcoming"][0], dict)
    assert isinstance(payload["upcoming"][0]["target"], dict)
    assert payload["upcoming"][0]["target"]["name"] == "later"
    assert payload["nextRun"] == "2026-11-17T12:00:00Z"


def test_runs_json_emits_nested_objects(monkeypatch, capsys) -> None:
    mod = _load_wbparser()
    job = mod.Job.model_validate(
        {
            "id": "11111111-1111-1111-1111-111111111111",
            "status": "Completed",
            "pagesCrawled": 60,
            "itemsSaved": 6000,
            "startedAt": "2026-11-17T10:00:00Z",
            "completedAt": "2026-11-17T10:05:00Z",
            "triggeredBy": "schedule",
            "errorMessage": None,
        }
    )
    target_run = mod.TargetRun.model_validate(
        {
            "targetId": "22222222-2222-2222-2222-222222222222",
            "targetName": "Cards",
            "egressGroup": "g1",
            "egressIp": "1.2.3.4",
            "outcome": "success",
            "pages": 60,
            "items": 6000,
            "wbTotal": 1000,
            "startedAt": "2026-11-17T10:00:00Z",
        }
    )
    error = mod.RecordedError.model_validate(
        {
            "category": "network",
            "message": "timeout",
            "createdAt": "2026-11-17T09:00:00Z",
        }
    )

    async def fake_runs(api, limit=20, target=None, errors=10):
        return {
            "jobs": [job],
            "targetRuns": [target_run],
            "errors": [error],
        }

    monkeypatch.setattr(mod, "_fetch_runs", fake_runs)
    monkeypatch.setattr(mod, "JSON", True)

    mod.runs(limit=20)

    payload = json.loads(capsys.readouterr().out)
    assert isinstance(payload["jobs"][0], dict)
    assert payload["jobs"][0]["status"] == "Completed"
    assert isinstance(payload["targetRuns"][0], dict)
    assert payload["targetRuns"][0]["outcome"] == "success"
    assert isinstance(payload["errors"][0], dict)
    assert payload["errors"][0]["message"] == "timeout"


# ── the Pane contract ────────────────────────────────────────────────────────


def _host(mod, pane):
    """A one-pane app, so a pane can be exercised without the real TUI."""

    class Host(mod.App):
        def compose(self):
            yield pane

    return Host()


async def _reload(pane) -> None:
    """One reload, waited out — the pane's own public worker manager, not its internals."""
    pane.reload()
    await pane.workers.wait_for_complete()


async def _wait_started(started: threading.Event) -> None:
    """A regression in worker startup must fail the test, not hang it."""
    reached = await asyncio.wait_for(asyncio.to_thread(started.wait, 2), timeout=5)
    assert reached, "the worker never reached fetch()"


def _stats(mod):
    return mod.Stats.model_validate(
        {
            "totalItems": 1,
            "totalProducts": 2,
            "totalTargets": 3,
            "withCashback": 4,
            "productsWithCashback": 5,
            "newestScrapedAt": "2026-11-17T09:00:00Z",
            "asOf": "2026-11-17T09:00:00Z",
            "instanceId": "i",
            "dataAsOf": None,
        }
    )


def test_a_pane_shows_what_it_fetched_and_marks_the_update() -> None:
    mod = _load_wbparser()

    class Probe(mod.Pane):
        def __init__(self) -> None:
            super().__init__()
            self.shown: list = []

        def compose(self):
            yield mod.Static("", id="pane-status")

        async def fetch(self):
            return {"answer": 42}

        def show(self, data):
            self.shown.append(data)

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            assert pane.shown == [{"answer": 42}]
            assert pane.updated_at is not None
            status = pane.query_one("#pane-status", mod.Static).content
            assert "loading" not in status
            assert "updated" in status

    asyncio.run(scenario())


def test_a_failed_fetch_is_shown_and_leaves_the_pane_loadable_again() -> None:
    mod = _load_wbparser()
    calls: list[int] = []

    class Probe(mod.Pane):
        def __init__(self) -> None:
            super().__init__()
            self.shown: list = []

        def compose(self):
            yield mod.Static("", id="pane-status")

        async def fetch(self):
            calls.append(1)
            if len(calls) == 1:
                raise mod.Unusable("cannot reach http://localhost:8082")
            return "recovered"

        def show(self, data):
            self.shown.append(data)

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            status = pane.query_one("#pane-status", mod.Static).content
            assert "cannot reach" in status
            assert "loading" not in status
            assert pane.shown == []

            await _reload(pane)
            assert pane.shown == ["recovered"]

    asyncio.run(scenario())


def test_a_failed_show_is_reported_and_does_not_mark_the_update() -> None:
    mod = _load_wbparser()
    failing = {"draw": True}

    class Probe(mod.Pane):
        def __init__(self) -> None:
            super().__init__()
            self.shown: list = []

        def compose(self):
            yield mod.Static("", id="pane-status")

        async def fetch(self):
            return {"answer": 42}

        def show(self, data):
            if failing["draw"]:
                raise RuntimeError("cannot draw")
            self.shown.append(data)

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            status = pane.query_one("#pane-status", mod.Static).content
            assert "cannot draw" in status
            assert "loading" not in status
            assert pane.updated_at is None
            assert pane.shown == []

            failing["draw"] = False
            await _reload(pane)
            assert pane.shown == [{"answer": 42}]
            assert pane.updated_at is not None

    asyncio.run(scenario())


def test_a_reload_while_one_is_in_flight_starts_no_second_fetch() -> None:
    mod = _load_wbparser()
    started = threading.Event()
    release = threading.Event()
    calls: list[int] = []

    class Probe(mod.Pane):
        def compose(self):
            yield mod.Static("", id="pane-status")

        async def fetch(self):
            calls.append(1)
            started.set()
            # The wait is off the event loop: a fetch that blocked it would freeze the
            # app, and the second reload would never arrive.
            await asyncio.to_thread(release.wait, 5)
            return None

        def show(self, data):
            pass

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            pane.reload()
            await _wait_started(started)
            pane.reload()
            release.set()
            await pane.workers.wait_for_complete()

    try:
        asyncio.run(scenario())
    finally:
        release.set()
    assert calls == [1]


def test_the_stats_pane_draws_what_it_fetched(monkeypatch) -> None:
    mod = _load_wbparser()

    async def fake_stats(api):
        return _stats(mod)

    monkeypatch.setattr(mod, "_fetch_stats", fake_stats)

    async def scenario() -> None:
        pane = mod.StatsPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            assert pane.query_one("#stats-table", mod.DataTable).row_count == 6
            assert pane.updated_at is not None
            status = pane.query_one("#pane-status", mod.Static).content
            assert "updated" in status

    asyncio.run(scenario())


def test_the_stats_pane_keeps_the_last_good_data_when_a_reload_fails(monkeypatch) -> None:
    mod = _load_wbparser()
    calls: list[int] = []

    def fetch():
        calls.append(1)
        if len(calls) == 1:
            return _stats(mod)
        raise mod.Unusable("cannot reach http://localhost:8082")

    async def fake_stats(api):
        return fetch()

    monkeypatch.setattr(mod, "_fetch_stats", fake_stats)

    async def scenario() -> None:
        pane = mod.StatsPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            table = pane.query_one("#stats-table", mod.DataTable)
            last_good = pane.updated_at
            assert table.row_count == 6
            assert last_good is not None

            await _reload(pane)
            assert table.row_count == 6
            assert pane.updated_at == last_good
            status = pane.query_one("#pane-status", mod.Static).content
            assert "cannot reach" in status

    asyncio.run(scenario())


def test_the_app_loads_stats_on_the_tab_and_on_refresh(monkeypatch) -> None:
    mod = _load_wbparser()
    fetches: list[int] = []

    def stats():
        fetches.append(1)
        return _stats(mod)

    async def fake_stats(api):
        return stats()

    async def fake_status(api):
        return ({"status": "UP", "checks": {}, "crawler": {}, "egress": {}}, {"ADMIN_API_KEY": True})

    monkeypatch.setattr(mod, "_fetch_stats", fake_stats)
    monkeypatch.setattr(mod, "_fetch_status", fake_status)
    monkeypatch.setattr(mod, "_running", _not_running)

    async def scenario() -> None:
        app = mod.WbparserApp()
        async with app.run_test() as pilot:
            pane = app.query_one(mod.StatsPane)
            await pilot.pause()
            assert fetches == [], "a hidden pane must not load"

            app.action_tab("tab-stats")
            await pilot.pause()
            await pane.workers.wait_for_complete()
            assert len(fetches) == 1

            app.action_tab("tab-logs")
            await pilot.pause()
            app.action_tab("tab-stats")
            await pilot.pause()
            await pane.workers.wait_for_complete()
            assert len(fetches) == 2

            await pilot.press("r")
            await pilot.pause()
            await pane.workers.wait_for_complete()
            assert len(fetches) == 3

            await pilot.click("#btn-stats")
            await pilot.pause()
            await pane.workers.wait_for_complete()
            assert len(fetches) == 4

            assert pane.query_one("#stats-table", mod.DataTable).row_count == 6

    asyncio.run(scenario())


def test_closing_the_app_while_a_load_is_in_flight_leaves_the_pane_silent() -> None:
    mod = _load_wbparser()
    started = threading.Event()
    release = threading.Event()

    class Probe(mod.Pane):
        def __init__(self) -> None:
            super().__init__()
            self.shown: list = []

        def compose(self):
            yield mod.Static("", id="pane-status")

        async def fetch(self):
            started.set()
            await asyncio.to_thread(release.wait, 5)
            return {"answer": 42}

        def show(self, data):
            self.shown.append(data)

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            pane.reload()
            await _wait_started(started)
        release.set()
        await asyncio.sleep(0.1)
        assert pane.shown == [], "a worker cancelled on unmount must not touch the pane"

    try:
        asyncio.run(scenario())
    finally:
        release.set()


# ── the migrated tabs ────────────────────────────────────────────────────────


def _items_page(mod):
    """One page of products, as the catalog answers it."""
    body = json.loads((FIXTURES / "catalog-items.json").read_text(encoding="utf-8"))
    return mod.ItemsPage.model_validate(body)


def test_the_items_pane_sends_the_filters_it_read(monkeypatch) -> None:
    mod = _load_wbparser()
    sent: list[dict] = []

    async def fake_items(api, **kwargs):
        sent.append(kwargs)
        return _items_page(mod)

    monkeypatch.setattr(mod, "_fetch_items", fake_items)

    async def scenario() -> None:
        pane = mod.ItemsPane()
        async with _host(mod, pane).run_test():
            pane.query_one("#items-q", mod.Input).value = "shoes"
            pane.query_one("#items-target", mod.Input).value = "target-1"
            pane.query_one("#items-sort", mod.Select).value = mod.Sort.price_asc.value
            await _reload(pane)
            assert sent == [
                {
                    "target": "target-1",
                    "query": "shoes",
                    "page": 0,
                    "page_size": 20,
                    "sort": "PriceAsc",
                    "scraped_after": None,
                }
            ], "the filters the user typed are the filters the request carries"

    asyncio.run(scenario())


def test_the_items_pane_sends_the_page_and_the_size_it_read(monkeypatch) -> None:
    mod = _load_wbparser()
    sent: list[dict] = []

    async def fake_items(api, **kwargs):
        sent.append(kwargs)
        return _items_page(mod)

    monkeypatch.setattr(mod, "_fetch_items", fake_items)

    async def scenario() -> None:
        pane = mod.ItemsPane()
        async with _host(mod, pane).run_test():
            pane.query_one("#items-page", mod.Input).value = "3"
            pane.query_one("#items-size", mod.Input).value = "50"
            await _reload(pane)
            assert sent[0]["page"] == 3
            assert sent[0]["page_size"] == 50

            # A page that is not a number falls back to the first page, and a size
            # that is not a number to the default — as it did before the migration.
            pane.query_one("#items-page", mod.Input).value = "not a page"
            pane.query_one("#items-size", mod.Input).value = ""
            await _reload(pane)
            assert sent[1]["page"] == 0
            assert sent[1]["page_size"] == 20

    asyncio.run(scenario())


def test_the_items_pane_keeps_the_page_after_a_load(monkeypatch) -> None:
    mod = _load_wbparser()

    async def fake_items(api, **kwargs):
        return _items_page(mod)

    monkeypatch.setattr(mod, "_fetch_items", fake_items)

    async def scenario() -> None:
        pane = mod.ItemsPane()
        async with _host(mod, pane).run_test():
            pane.query_one("#items-page", mod.Input).value = "3"
            await _reload(pane)
            assert pane.query_one("#items-page", mod.Input).value == "3", "a load does not reset the page"

    asyncio.run(scenario())


def test_the_items_pane_draws_the_page_it_fetched(monkeypatch) -> None:
    mod = _load_wbparser()

    async def fake_items(api, **kwargs):
        return _items_page(mod)

    monkeypatch.setattr(mod, "_fetch_items", fake_items)

    async def scenario() -> None:
        pane = mod.ItemsPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            assert pane.query_one("#items-table", mod.DataTable).row_count == 1
            assert pane.urls == ["https://example.invalid/p/1"]
            assert "1 total" in pane.query_one("#items-info", mod.Static).content

    asyncio.run(scenario())


def test_the_points_share_is_the_points_over_the_paid_price() -> None:
    """WB spends a point as a ruble, so the share is the points divided by the price."""
    mod = _load_wbparser()
    card = _items_page(mod).items[0].model_copy(update={"feedback_points": 300})

    # 300 ₽ of points on the 159.20 ₽ the product is sold at.
    assert mod._points_share(card) == "188.4%"
    # Without a sale price the card shows the regular one.
    assert mod._points_share(card.model_copy(update={"sale_price_kopecks": None})) == "150.8%"
    assert mod._points_share(card.model_copy(update={"feedback_points": None})) == "—"
    assert mod._points_share(card.model_copy(update={"price_kopecks": None, "sale_price_kopecks": None})) == "—"


def test_a_period_names_its_lower_bound() -> None:
    """`today` is local midnight, `last run` is when the newest crawl started, `all` is nothing."""
    mod = _load_wbparser()

    class FakeApi:
        async def get(self, path, model=None, *, role="public", **params):
            assert (path, role, params) == ("/api/admin/jobs", "admin", {"limit": 1})
            return [
                mod.Job.model_validate(
                    {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "status": "Completed",
                        "startedAt": "2026-11-20T09:00:00Z",
                        "completedAt": "2026-11-20T09:05:00Z",
                    }
                )
            ]

    api = FakeApi()
    assert asyncio.run(mod._since(api, mod.Period.all)) is None
    assert asyncio.run(mod._since(api, mod.Period.last_run)) == datetime(2026, 11, 20, 9, 0, tzinfo=UTC)

    today = asyncio.run(mod._since(api, mod.Period.today))
    assert today is not None
    assert (today.hour, today.minute, today.second, today.microsecond) == (0, 0, 0, 0)
    assert today.utcoffset() == datetime.now().astimezone().utcoffset(), "midnight of the operator's own day"


def _fake_tabs(monkeypatch, mod, calls: list[str]) -> None:
    """Every tab answers without a network, and says which tab answered."""

    async def fake_status(api):
        calls.append("status")
        return ({"status": "UP", "checks": {}, "crawler": {}, "egress": {}}, {})

    async def fake_items(api, **kwargs):
        calls.append("items")
        return _items_page(mod)

    async def fake_runs(api):
        calls.append("runs")
        return {"targetRuns": [], "jobs": [], "errors": []}

    async def fake_schedule(api):
        calls.append("schedule")
        return mod.Schedule()

    async def fake_history(api):
        calls.append("history")
        return {"jobs": [], "cron": "0 0 */4 * * *", "freshnessMinutes": 360, "nextRun": None, "earliestDue": None}

    async def fake_stats(api):
        calls.append("stats")
        return _stats(mod)

    monkeypatch.setattr(mod, "_fetch_status", fake_status)
    monkeypatch.setattr(mod, "_fetch_items", fake_items)
    monkeypatch.setattr(mod, "_fetch_runs", fake_runs)
    monkeypatch.setattr(mod, "_fetch_schedule", fake_schedule)
    monkeypatch.setattr(mod, "_fetch_history", fake_history)
    monkeypatch.setattr(mod, "_fetch_stats", fake_stats)


def test_the_app_loads_the_tab_it_shows_and_leaves_the_hidden_tabs_alone(monkeypatch) -> None:
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)

    async def scenario() -> None:
        app = mod.WbparserApp()
        async with app.run_test() as pilot:
            await pilot.pause()
            assert calls == ["status"], "the tab the app opens with is the tab that loads"

            for tab, pane_cls, name in (
                ("tab-items", mod.ItemsPane, "items"),
                ("tab-runs", mod.RunsPane, "runs"),
                ("tab-schedule", mod.SchedulePane, "schedule"),
                ("tab-history", mod.HistoryPane, "history"),
            ):
                app.action_tab(tab)
                await pilot.pause()
                pane = app.query_one(pane_cls)
                await pane.workers.wait_for_complete()
                assert calls[-1] == name, "the tab the operator opened is the tab that loads"
                assert "updated" in pane.query_one("#pane-status", mod.Static).content, "the pane drew what it fetched"
                assert calls.count(name) == 1, "a hidden tab never polls the API"

    asyncio.run(scenario())


def test_the_auto_refresh_reloads_the_active_pane_only(monkeypatch) -> None:
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)

    async def scenario() -> None:
        app = mod.WbparserApp()
        # The cadence is a class attribute, so the instance may shorten it: the test
        # waits for the timer, not for half a minute.
        app.REFRESH_EVERY = 0.05
        async with app.run_test() as pilot:
            app.action_tab("tab-items")
            await pilot.pause()
            await app.query_one(mod.ItemsPane).workers.wait_for_complete()
            calls.clear()

            for _ in range(100):
                await asyncio.sleep(0.02)
                if calls:
                    break
            assert calls == ["items"], "the timer refreshes the active pane and nothing else"

    asyncio.run(scenario())


def test_an_error_in_one_pane_does_not_break_the_other_tabs(monkeypatch) -> None:
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)

    async def failing_status(api):
        raise mod.Unusable("cannot reach http://localhost:8082")

    monkeypatch.setattr(mod, "_fetch_status", failing_status)

    async def scenario() -> None:
        app = mod.WbparserApp()
        async with app.run_test() as pilot:
            await pilot.pause()
            status = app.query_one(mod.StatusPane)
            assert "cannot reach" in status.query_one("#pane-status", mod.Static).content
            assert status.updated_at is None, "a failed load is not an update"

            app.action_tab("tab-items")
            await pilot.pause()
            items = app.query_one(mod.ItemsPane)
            await items.workers.wait_for_complete()
            assert items.query_one("#items-table", mod.DataTable).row_count == 1
            assert items.updated_at is not None

    asyncio.run(scenario())


# ── the panes' buttons and their tables ──────────────────────────────────────


async def _wait_for(predicate, timeout: float = 5.0) -> bool:
    """Wait for a condition the event loop is going to satisfy, or give up."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        await asyncio.sleep(0.02)
    return False


def _rows(table) -> list[list[str]]:
    """The rows of a table, as plain strings."""
    return [[str(cell) for cell in table.get_row_at(index)] for index in range(table.row_count)]


#: The tab, the button that reloads its pane, and the fetch that button must call.
PANE_REFRESH_BUTTONS = [
    ("tab-status", "#btn-refresh-status", "status"),
    ("tab-items", "#btn-items", "items"),
    ("tab-runs", "#btn-runs", "runs"),
    ("tab-schedule", "#btn-schedule", "schedule"),
    ("tab-history", "#btn-history", "history"),
    ("tab-stats", "#btn-stats", "stats"),
]


@pytest.mark.parametrize(("tab", "button", "name"), PANE_REFRESH_BUTTONS)
def test_each_pane_refresh_button_reloads_only_that_pane(monkeypatch, tab, button, name) -> None:
    """A click on a pane's own button reloads that pane — once, and nothing else."""
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)
    monkeypatch.setattr(mod, "_running", _not_running)

    async def scenario() -> None:
        app = mod.WbparserApp()
        # A wide terminal: the items row is wider than the default 80 columns, and a
        # click that lands outside the screen is not a click.
        async with app.run_test(size=(160, 50)) as pilot:
            app.action_tab(tab)
            await pilot.pause()
            pane = app.query_one(mod.WbparserApp.PANES[tab])
            assert await _wait_for(lambda: calls.count(name) >= 1), "opening the tab loads it"
            assert calls.count(name) == 1, "opening a tab loads it once"
            opened = len(calls)

            landed = await pilot.click(button)
            assert landed, "the click landed on the button"
            await pilot.pause()
            assert await _wait_for(lambda: len(calls) > opened), "the button started a load"
            await pane.workers.wait_for_complete()

            assert calls[opened:] == [name], (
                "one click, one request, and it is this pane's own fetch — "
                "the app's button handler must not start a second load"
            )

    asyncio.run(scenario())


def test_opening_a_tab_loads_it_once(monkeypatch) -> None:
    """A tab switch is one load — the tab activation does not double up with the timer."""
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)
    monkeypatch.setattr(mod, "_running", _not_running)

    async def scenario() -> None:
        app = mod.WbparserApp()
        # The timer stays out of the way: this is about the switch itself.
        app.REFRESH_EVERY = 3600.0
        async with app.run_test() as pilot:
            for tab, name in (
                ("tab-items", "items"),
                ("tab-runs", "runs"),
                ("tab-schedule", "schedule"),
                ("tab-history", "history"),
                ("tab-stats", "stats"),
            ):
                app.action_tab(tab)
                await pilot.pause()
                assert await _wait_for(lambda name=name: calls.count(name) >= 1), f"{tab} loads when it opens"
                assert calls.count(name) == 1, f"opening {tab} loads it once, not twice"

    asyncio.run(scenario())


def test_the_auto_refresh_follows_the_tab_the_operator_opened(monkeypatch) -> None:
    """The timer refreshes whichever pane is on screen — and nothing when none is."""
    mod = _load_wbparser()
    calls: list[str] = []
    _fake_tabs(monkeypatch, mod, calls)
    monkeypatch.setattr(mod, "_running", _not_running)

    async def scenario() -> None:
        app = mod.WbparserApp()
        app.REFRESH_EVERY = 0.05
        async with app.run_test() as pilot:
            app.action_tab("tab-items")
            await pilot.pause()
            assert await _wait_for(lambda: calls.count("items") >= 1), "opening the tab loads it"
            calls.clear()
            assert await _wait_for(lambda: calls), "the timer refreshes the active pane"
            assert set(calls) == {"items"}, "the timer refreshes the active pane and nothing else"

            # The operator opens another tab: the next tick refreshes that one.
            app.action_tab("tab-runs")
            await pilot.pause()
            assert await _wait_for(lambda: calls.count("runs") >= 1), "opening the tab loads it"
            calls.clear()
            assert await _wait_for(lambda: calls), "the timer follows the operator"
            assert set(calls) == {"runs"}, "the timer refreshes the tab that is on screen"

            # The log tab is not a pane: there is nothing to refresh, and nothing to ask.
            app.action_tab("tab-logs")
            await pilot.pause()
            calls.clear()
            await asyncio.sleep(0.3)
            assert calls == [], "the log tab never polls the API"

    asyncio.run(scenario())


def _job(mod, **kwargs):
    base = {
        "id": "11111111-1111-1111-1111-111111111111",
        "status": "Completed",
        "pagesCrawled": 10,
        "itemsSaved": 5,
        "startedAt": "2026-11-17T10:00:00Z",
        "completedAt": "2026-11-17T10:05:00Z",
        "triggeredBy": "schedule",
        "errorMessage": None,
    }
    base.update(kwargs)
    return mod.Job.model_validate(base)


def _target_run(mod, **kwargs):
    base = {
        "targetId": "22222222-2222-2222-2222-222222222222",
        "targetName": "Cards",
        "egressGroup": "g1",
        "egressIp": "1.2.3.4",
        "outcome": "success",
        "pages": 60,
        "items": 6000,
        "wbTotal": 1000,
        "startedAt": "2026-11-17T10:00:00Z",
    }
    base.update(kwargs)
    return mod.TargetRun.model_validate(base)


def _recorded_error(mod, **kwargs):
    base = {"category": "network", "message": "timeout", "createdAt": "2026-11-17T09:00:00Z"}
    base.update(kwargs)
    return mod.RecordedError.model_validate(base)


def test_the_runs_pane_draws_the_three_tables(monkeypatch) -> None:
    """The journal: the target runs, the jobs and the unresolved errors."""
    mod = _load_wbparser()
    answers = [
        {
            "targetRuns": [_target_run(mod)],
            "jobs": [_job(mod)],
            "errors": [_recorded_error(mod)],
        },
        {"targetRuns": [], "jobs": [], "errors": []},
    ]

    async def fake_runs(api):
        return answers.pop(0)

    monkeypatch.setattr(mod, "_fetch_runs", fake_runs)

    async def scenario() -> None:
        pane = mod.RunsPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            targets = _rows(pane.query_one("#runs-targets", mod.DataTable))
            jobs = _rows(pane.query_one("#runs-jobs", mod.DataTable))
            errors = _rows(pane.query_one("#runs-errors", mod.DataTable))

            assert len(targets) == 1
            assert targets[0][0] == "Cards"
            assert targets[0][3] == "success"
            assert len(jobs) == 1
            assert jobs[0][0] == "Completed"
            assert jobs[0][4] == "—", "a job without an error says so"
            assert len(errors) == 1
            assert errors[0][0] == "network"
            assert errors[0][1] == "timeout"

            # An empty answer leaves empty tables, not the rows of the last load.
            await _reload(pane)
            assert pane.query_one("#runs-targets", mod.DataTable).row_count == 0
            assert pane.query_one("#runs-jobs", mod.DataTable).row_count == 0
            assert pane.query_one("#runs-errors", mod.DataTable).row_count == 0

    asyncio.run(scenario())


def test_the_schedule_pane_draws_the_due_and_the_upcoming(monkeypatch) -> None:
    """The tick, the freshness window, and the targets that are due."""
    mod = _load_wbparser()
    due = _target(mod, "Shoes", lastCrawledAt="2026-11-17T08:00:00Z")
    later = _target(mod, "Cards")
    schedule = mod.Schedule.model_validate(
        {
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "activeTargets": 2,
            "targets": [due.model_dump(by_alias=True), later.model_dump(by_alias=True)],
            "due": [due.model_dump(by_alias=True)],
            "upcoming": [{"at": "2026-11-17T14:00:00Z", "target": later.model_dump(by_alias=True)}],
            "nextRun": "2026-11-17T12:00:00Z",
            "earliestDue": "2026-11-17T08:00:00Z",
            "limit": 15,
        }
    )
    answers = [schedule, mod.Schedule.model_validate({})]

    async def fake_schedule(api):
        return answers.pop(0)

    monkeypatch.setattr(mod, "_fetch_schedule", fake_schedule)

    async def scenario() -> None:
        pane = mod.SchedulePane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            due_rows = _rows(pane.query_one("#sched-due", mod.DataTable))
            upcoming_rows = _rows(pane.query_one("#sched-upcoming", mod.DataTable))
            summary = pane.query_one("#schedule-summary", mod.Static).content

            assert len(due_rows) == 1
            assert due_rows[0][0] == "Shoes"
            assert due_rows[0][1] == "9467"
            assert len(upcoming_rows) == 1
            assert upcoming_rows[0][0] == "Cards"
            assert "tick: 0 0 */4 * * *" in summary
            assert "due now: 1" in summary
            assert "next run:" in summary

            # An empty answer leaves empty tables, not the rows of the last load.
            await _reload(pane)
            assert pane.query_one("#sched-due", mod.DataTable).row_count == 0
            assert pane.query_one("#sched-upcoming", mod.DataTable).row_count == 0

    asyncio.run(scenario())


def test_the_history_pane_draws_the_jobs_and_the_schedule(monkeypatch) -> None:
    """The last runs, what started each of them, and when the next one is due."""
    mod = _load_wbparser()
    answers = [
        {
            "jobs": [_job(mod)],
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "nextRun": datetime(2026, 11, 17, 12, 0, tzinfo=UTC),
            "earliestDue": None,
        },
        {
            "jobs": [],
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "nextRun": None,
            "earliestDue": None,
        },
    ]

    async def fake_history(api):
        return answers.pop(0)

    monkeypatch.setattr(mod, "_fetch_history", fake_history)

    async def scenario() -> None:
        pane = mod.HistoryPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            rows = _rows(pane.query_one("#history-table", mod.DataTable))
            summary = pane.query_one("#history-summary", mod.Static).content

            assert len(rows) == 1
            assert rows[0][2] == "schedule"
            assert rows[0][3] == "Completed"
            assert rows[0][4] == "10"
            assert "tick: 0 0 */4 * * *" in summary
            assert "freshness: 360 min" in summary
            assert "next run:" in summary

            # An empty answer leaves an empty table, not the rows of the last load.
            await _reload(pane)
            assert pane.query_one("#history-table", mod.DataTable).row_count == 0

    asyncio.run(scenario())


def test_the_items_pane_keeps_its_rows_and_urls_when_a_reload_fails(monkeypatch) -> None:
    """The rows on screen and the URLs behind them stay in step when a load fails."""
    mod = _load_wbparser()
    attempts = {"n": 0}

    async def fake_items(api, **kwargs):
        attempts["n"] += 1
        if attempts["n"] == 1:
            return _items_page(mod)
        raise mod.Unusable("cannot reach http://localhost:8082")

    monkeypatch.setattr(mod, "_fetch_items", fake_items)

    async def scenario() -> None:
        pane = mod.ItemsPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            table = pane.query_one("#items-table", mod.DataTable)
            assert table.row_count == 1
            urls = list(pane.urls)
            assert urls == ["https://example.invalid/p/1"], "the row on screen has its url"
            updated = pane.updated_at

            await _reload(pane)
            assert table.row_count == 1, "a failed reload keeps the last good rows"
            assert pane.urls == urls, "the rows on screen and the urls behind them stay in step"
            assert pane.updated_at == updated, "a failed reload is not an update"
            assert "cannot reach" in pane.query_one("#pane-status", mod.Static).content

    asyncio.run(scenario())


def test_the_status_pane_keeps_its_data_when_the_answer_is_malformed(monkeypatch) -> None:
    """A malformed answer must not leave the status pane half-drawn."""
    mod = _load_wbparser()
    good = (
        {
            "status": "UP",
            "checks": {"database": "UP"},
            "crawler": {"state": "not_started"},
            "egress": {"groups": [{"group": "g1", "address": "1.2.3.4", "healthy": True}]},
        },
        {"ADMIN_API_KEY": True},
    )
    # `checks` is a list, not a map: the pane fails while building the rows.
    malformed = (
        {"status": "UP", "checks": ["not", "a", "map"], "crawler": {}, "egress": {}},
        {},
    )
    answers = [good, malformed]

    async def fake_status(api):
        return answers.pop(0)

    monkeypatch.setattr(mod, "_fetch_status", fake_status)

    async def scenario() -> None:
        pane = mod.StatusPane()
        async with _host(mod, pane).run_test():
            await _reload(pane)
            summary = pane.query_one("#status-summary", mod.Static).content
            assert "UP" in summary
            assert pane.query_one("#status-checks", mod.DataTable).row_count == 1
            assert pane.query_one("#status-creds", mod.DataTable).row_count == 1
            assert pane.query_one("#status-egress", mod.DataTable).row_count == 1
            updated = pane.updated_at

            await _reload(pane)
            status = pane.query_one("#pane-status", mod.Static).content
            assert "has no attribute 'items'" in status, "the malformed answer is reported"
            assert pane.query_one("#status-summary", mod.Static).content == summary, (
                "the summary on screen is the last good one"
            )
            assert pane.query_one("#status-checks", mod.DataTable).row_count == 1, (
                "the rows on screen are the last good ones"
            )
            assert pane.query_one("#status-creds", mod.DataTable).row_count == 1
            assert pane.query_one("#status-egress", mod.DataTable).row_count == 1
            assert pane.updated_at == updated, "a failed draw is not an update"

    asyncio.run(scenario())


# ── the HTTP layer ───────────────────────────────────────────────────────────


class _Recorder(httpx.AsyncBaseTransport):
    """A transport that answers from a handler and keeps the requests it saw."""

    def __init__(self, handler):
        self.handler = handler
        self.requests: list[httpx.Request] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        answer = self.handler(request)
        # The handler may be a plain function or a coroutine function; both are answers.
        if inspect.isawaitable(answer):
            return await answer
        return answer


class _TransportCase(unittest.TestCase):
    """A case with a transport in place of the HTTP layer, and the keys it needs."""

    KEYS = {"ADMIN_API_KEY": "admin-secret", "CATALOG_API_KEY": "catalog-secret"}

    def setUp(self) -> None:
        self.mod = _load_wbparser()
        self.clients: list[httpx.AsyncClient] = []

    def _transport(self, handler):
        """A transport for `handler`, and the patch that puts it into the client."""
        recorder = _Recorder(handler)
        real = httpx.AsyncClient

        def factory(**kwargs):
            client = real(transport=recorder, **kwargs)
            self.clients.append(client)
            return client

        return recorder, mock.patch.object(self.mod.httpx, "AsyncClient", factory)

    def _keys(self):
        return mock.patch.object(self.mod, "_env", lambda: dict(self.KEYS))


class ApiHttpTest(_TransportCase):
    """
    The HTTP layer: what a role sends, what a failure says, and how long a client lives.

    These are `unittest` cases on purpose: the gate runs the suite with pytest, which
    collects both shapes, and these are the cases that need a patch around the
    transport.
    """

    def _message(self, handler, *, path="/api/health/ready", role="public", model=None) -> str:
        """The message of the `Unusable` one GET raises, or an empty string if it did not."""
        _recorder, patch = self._transport(handler)
        with patch, self._keys():

            async def scenario() -> str:
                async with self.mod.Api() as api:
                    try:
                        await api.get(path, model, role=role)
                    except self.mod.Unusable as error:
                        return str(error)
                    return ""

            return asyncio.run(scenario())

    def test_each_role_carries_its_own_key(self) -> None:
        mod = self.mod
        recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, self._keys():

            async def scenario() -> None:
                async with mod.Api() as api:
                    await api.get("/api/health/ready")
                    await api.get("/api/admin/targets", role="admin")
                    await api.get("/api/catalog/v1/stats", role="catalog")

            asyncio.run(scenario())

        self.assertEqual(
            [request.headers.get("x-api-key") for request in recorder.requests],
            [None, "admin-secret", "catalog-secret"],
        )
        # One client per role: the admin key travels only on the admin client.
        self.assertEqual(len(self.clients), 3)

    def test_only_the_catalog_decodes_decimals(self) -> None:
        mod = self.mod
        body = b'{"cashbackPercent": 0.1000000000000000000000000001}'
        _recorder, patch = self._transport(lambda request: httpx.Response(200, content=body))
        with patch, self._keys():

            async def scenario() -> tuple:
                async with mod.Api() as api:
                    catalog = await api.get("/api/catalog/v1/stats", role="catalog")
                    public = await api.get("/api/health/ready")
                return catalog, public

            catalog, public = asyncio.run(scenario())

        self.assertIsInstance(catalog["cashbackPercent"], Decimal)
        self.assertIsInstance(public["cashbackPercent"], float)

    def test_a_refused_key_is_reported(self) -> None:
        message = self._message(lambda request: httpx.Response(401))
        self.assertIn("401", message)
        self.assertIn("/api/health/ready", message)

    def test_a_forbidden_key_is_reported(self) -> None:
        message = self._message(lambda request: httpx.Response(403))
        self.assertIn("403", message)

    def test_a_client_error_is_reported(self) -> None:
        message = self._message(lambda request: httpx.Response(404, text="no such thing"))
        self.assertIn("HTTP 404 on /api/health/ready", message)
        self.assertIn("no such thing", message)

    def test_a_server_error_is_reported(self) -> None:
        message = self._message(lambda request: httpx.Response(500))
        self.assertIn("HTTP 500 on /api/health/ready", message)

    def test_an_unreachable_stack_says_how_to_start_it(self) -> None:
        def handler(request):
            raise httpx.ConnectError("connection refused")

        self.assertIn("cannot reach", self._message(handler))

    def test_a_stack_that_hangs_up_says_it_is_still_starting(self) -> None:
        def handler(request):
            raise httpx.ReadTimeout("timed out")

        self.assertIn("not answering yet", self._message(handler))

    def test_a_broken_protocol_says_it_is_still_starting(self) -> None:
        def handler(request):
            raise httpx.RemoteProtocolError("server disconnected")

        self.assertIn("not answering yet", self._message(handler))

    def test_a_broken_read_says_it_is_still_starting(self) -> None:
        def handler(request):
            raise httpx.ReadError("the read failed")

        self.assertIn("not answering yet", self._message(handler))

    def test_a_response_that_is_not_json_is_reported(self) -> None:
        message = self._message(lambda request: httpx.Response(200, content=b"<html>nope</html>"))
        self.assertIn("not JSON", message)

    def test_a_response_that_does_not_match_the_model_is_reported(self) -> None:
        message = self._message(
            lambda request: httpx.Response(200, json={"totalProducts": "many"}),
            model=self.mod.Stats,
        )
        self.assertIn("does not match the model", message)

    def test_every_client_is_closed_when_the_scope_ends(self) -> None:
        mod = self.mod
        _recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, self._keys():

            async def scenario() -> None:
                async with mod.Api() as api:
                    await api.get("/api/health/ready")
                    await api.get("/api/admin/targets", role="admin")
                self.assertTrue(self.clients)
                self.assertTrue(all(client.is_closed for client in self.clients))

            asyncio.run(scenario())

    def test_every_client_is_closed_when_the_scope_fails(self) -> None:
        mod = self.mod
        _recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, self._keys():

            async def scenario() -> None:
                with self.assertRaises(RuntimeError):
                    async with mod.Api() as api:
                        await api.get("/api/health/ready")
                        raise RuntimeError("boom")
                self.assertTrue(self.clients)
                self.assertTrue(all(client.is_closed for client in self.clients))

            asyncio.run(scenario())

    def test_every_client_is_closed_when_the_scope_is_cancelled(self) -> None:
        mod = self.mod
        _recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, self._keys():

            async def scenario() -> None:
                async with mod.Api() as api:
                    await api.get("/api/health/ready")
                    raise asyncio.CancelledError

            with contextlib.suppress(asyncio.CancelledError):
                asyncio.run(scenario())
            self.assertTrue(self.clients)
            self.assertTrue(all(client.is_closed for client in self.clients))

    def test_independent_requests_run_together(self) -> None:
        mod = self.mod
        arrived: list[str] = []
        recorder, patch = self._transport(None)
        with patch, self._keys():

            async def scenario() -> dict:
                release = asyncio.Event()

                async def handler(request):
                    arrived.append(request.url.path)
                    if len(arrived) == 3:
                        release.set()
                    # The requests are only answered when all three have arrived, so a
                    # sequential fetch would never get here.
                    await asyncio.wait_for(release.wait(), timeout=2)
                    return httpx.Response(200, json=[])

                recorder.handler = handler
                async with mod.Api() as api:
                    return await mod._fetch_runs(api)

            data = asyncio.run(scenario())

        self.assertEqual(len(arrived), 3)
        self.assertEqual(data["jobs"], [])

    def test_a_failed_request_is_not_reported_as_a_success(self) -> None:
        mod = self.mod

        async def handler(request):
            if request.url.path == "/api/admin/errors":
                return httpx.Response(500, text="boom")
            return httpx.Response(200, json=[])

        _recorder, patch = self._transport(handler)
        with patch, self._keys():

            async def scenario() -> str:
                async with mod.Api() as api:
                    try:
                        await mod._fetch_runs(api)
                    except mod.Unusable as error:
                        return str(error)
                    return ""

            message = asyncio.run(scenario())

        self.assertIn("HTTP 500 on /api/admin/errors", message)

    def test_a_missing_key_is_refused_before_the_request(self) -> None:
        recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, mock.patch.object(self.mod, "_env", lambda: {"CATALOG_API_KEY": "catalog-secret"}):

            async def scenario() -> str:
                async with self.mod.Api() as api:
                    try:
                        await api.get("/api/admin/targets", role="admin")
                    except self.mod.Unusable as error:
                        return str(error)
                    return ""

            message = asyncio.run(scenario())

        self.assertIn("ADMIN_API_KEY is not set", message)
        self.assertEqual(recorder.requests, [])

    def test_an_unknown_role_is_refused_before_the_request(self) -> None:
        recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch, self._keys():

            async def scenario() -> str:
                async with self.mod.Api() as api:
                    try:
                        await api.get("/api/catalog/v1/stats", role="catalogue")
                    except self.mod.Unusable as error:
                        return str(error)
                    return ""

            message = asyncio.run(scenario())

        self.assertIn("unknown HTTP role", message)
        # A typo must not turn into an anonymous request.
        self.assertEqual(recorder.requests, [])

    def test_one_client_that_fails_to_close_does_not_leave_the_others_open(self) -> None:
        mod = self.mod

        class Stubborn:
            async def aclose(self) -> None:
                raise RuntimeError("cannot close")

        class Good:
            def __init__(self) -> None:
                self.closed = False

            async def aclose(self) -> None:
                self.closed = True

        good = Good()
        api = mod.Api()
        api._clients = {"admin": Stubborn(), "catalog": good}

        with self.assertRaises(ExceptionGroup) as raised:
            asyncio.run(api.aclose())

        self.assertTrue(good.closed)
        self.assertEqual(api._clients, {})
        self.assertIn("cannot close", str(raised.exception.exceptions[0]))

    def test_a_failing_cleanup_does_not_replace_the_error_of_the_operation(self) -> None:
        mod = self.mod

        class Stubborn:
            async def aclose(self) -> None:
                raise RuntimeError("cannot close")

        async def scenario() -> None:
            async with mod.Api() as api:
                api._clients = {"admin": Stubborn()}
                raise ValueError("boom")

        with self.assertRaises(ValueError) as raised:
            asyncio.run(scenario())

        self.assertEqual(str(raised.exception), "boom")
        # The cleanup failure is not lost, it just does not hide the real error.
        self.assertIn("cannot close", "\n".join(raised.exception.__notes__))

    def test_a_scope_keeps_the_configuration_it_started_with(self) -> None:
        mod = self.mod
        first = {"ADMIN_API_KEY": "first-admin", "CATALOG_API_KEY": "first-catalog"}
        second = {"ADMIN_API_KEY": "second-admin", "CATALOG_API_KEY": "second-catalog"}
        recorder, patch = self._transport(lambda request: httpx.Response(200, json={}))
        with patch:

            async def scenario() -> None:
                with mock.patch.object(mod, "_env", lambda: dict(first)):
                    first_api = mod.Api()
                with mock.patch.object(mod, "_env", lambda: dict(second)):
                    second_api = mod.Api()
                    async with second_api as other:
                        await other.get("/api/admin/targets", role="admin")
                # The first scope still answers with the configuration it started with.
                async with first_api as api:
                    await api.get("/api/admin/targets", role="admin")

            asyncio.run(scenario())

        self.assertEqual(
            [request.headers.get("x-api-key") for request in recorder.requests],
            ["second-admin", "first-admin"],
        )


# ── the commands ─────────────────────────────────────────────────────────────


class CliTest(_TransportCase):
    """
    The commands, from the shell's point of view: what they print, what they ask for,
    and what they exit with.

    The HTTP layer is replaced by a transport, so the whole path — the Typer command,
    the event loop, the scope, the response — runs without a server.
    """

    def _json(self):
        return mock.patch.object(self.mod, "JSON", True)

    def test_status_prints_the_health_and_the_keys(self) -> None:
        mod = self.mod

        def handler(request):
            if request.url.path == "/api/health/ready":
                return httpx.Response(200, json={"status": "UP", "checks": {"db": "UP"}})
            return httpx.Response(200, json={})

        recorder, patch = self._transport(handler)
        out = io.StringIO()
        with patch, self._keys(), self._json(), contextlib.redirect_stdout(out):
            mod.status()

        payload = json.loads(out.getvalue())
        self.assertEqual(payload["ready"]["status"], "UP")
        self.assertEqual(payload["keys"], {"ADMIN_API_KEY": True, "CATALOG_API_KEY": True})
        # Health, the admin key and the catalog key: three reads, one scope.
        self.assertEqual(len(recorder.requests), 3)

    def test_status_exits_with_one_when_the_stack_is_down(self) -> None:
        mod = self.mod

        def handler(request):
            if request.url.path == "/api/health/ready":
                return httpx.Response(200, json={"status": "DOWN"})
            return httpx.Response(200, json={})

        _recorder, patch = self._transport(handler)
        with (
            patch,
            self._keys(),
            self._json(),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaises(mod.typer.Exit) as raised,
        ):
            mod.status()

        self.assertEqual(raised.exception.exit_code, 1)

    def test_items_asks_for_the_page_and_prints_the_items(self) -> None:
        mod = self.mod
        body = (FIXTURES / "catalog-items.json").read_bytes()
        recorder, patch = self._transport(lambda request: httpx.Response(200, content=body))
        out = io.StringIO()
        with patch, self._keys(), self._json(), contextlib.redirect_stdout(out):
            mod.items(
                target="t-1",
                query="phone",
                page=2,
                page_size=5,
                sort=mod.Sort.price_asc,
                in_stock=True,
                min_price=100.0,
            )

        params = dict(recorder.requests[0].url.params)
        self.assertEqual(params["targetId"], "t-1")
        self.assertEqual(params["q"], "phone")
        self.assertEqual(params["page"], "2")
        self.assertEqual(params["pageSize"], "5")
        self.assertEqual(params["sort"], "PriceAsc")
        self.assertEqual(params["inStock"], "true")
        self.assertEqual(params["minPrice"], "100.0")
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["totalCount"], 1)
        self.assertEqual(payload["items"][0]["title"], "Товар")

    def test_stats_prints_the_counters(self) -> None:
        mod = self.mod
        body = (FIXTURES / "catalog-stats.json").read_bytes()
        _recorder, patch = self._transport(lambda request: httpx.Response(200, content=body))
        out = io.StringIO()
        with patch, self._keys(), self._json(), contextlib.redirect_stdout(out):
            mod.stats()

        payload = json.loads(out.getvalue())
        self.assertEqual(payload["totalProducts"], 2)
        self.assertEqual(payload["totalItems"], 3)
        self.assertEqual(payload["totalTargets"], 1)

    def test_up_waits_until_the_application_answers(self) -> None:
        mod = self.mod
        recorder, patch = self._transport(lambda request: httpx.Response(200, json={"status": "UP"}))
        with (
            patch,
            self._keys(),
            mock.patch.object(mod, "_gradle_command", lambda: _substitute("bootJar")),
            mock.patch.object(mod, "_compose_command", lambda *argv: _substitute(*argv)),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            mod.up(timeout=5)

        self.assertEqual(len(recorder.requests), 1)

    def test_up_exits_with_one_when_the_application_does_not_answer(self) -> None:
        mod = self.mod
        _recorder, patch = self._transport(lambda request: httpx.Response(200, json={"status": "DOWN"}))
        with (
            patch,
            self._keys(),
            mock.patch.object(mod, "_gradle_command", lambda: _substitute("bootJar")),
            mock.patch.object(mod, "_compose_command", lambda *argv: _substitute(*argv)),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaises(mod.typer.Exit) as raised,
        ):
            mod.up(timeout=0)

        self.assertEqual(raised.exception.exit_code, 1)


# ── the command line ──────────────────────────────────────────────────────────


class CliRunnerTest(_TransportCase):
    """
    The CLI as the shell sees it: the arguments, the global option, the exit code.

    The commands are also called directly in `CliTest`; these cases go through Typer,
    so the parsing of the options and the dispatch are part of what is proven.
    """

    def setUp(self) -> None:
        super().setUp()
        self.runner = CliRunner()

    def _json_mode(self):
        """The global option, restored to the module default after the test."""
        return mock.patch.object(self.mod, "JSON", False)

    def test_the_json_option_goes_before_the_command(self) -> None:
        mod = self.mod
        with self._json_mode():
            result = self.runner.invoke(mod.app, ["status", "--json"])

        # `--json` is a global option: after the command it is not an option at all.
        self.assertEqual(result.exit_code, 2)

    def test_the_json_option_prints_one_document(self) -> None:
        mod = self.mod

        def handler(request):
            if request.url.path == "/api/health/ready":
                return httpx.Response(200, json={"status": "UP", "checks": {}})
            return httpx.Response(200, json={})

        _recorder, patch = self._transport(handler)
        with patch, self._keys(), self._json_mode():
            result = self.runner.invoke(mod.app, ["--json", "status"])

        self.assertEqual(result.exit_code, 0)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["ready"]["status"], "UP")
        self.assertEqual(payload["keys"], {"ADMIN_API_KEY": True, "CATALOG_API_KEY": True})

    def test_the_exit_code_is_one_when_the_stack_is_down(self) -> None:
        mod = self.mod

        def handler(request):
            if request.url.path == "/api/health/ready":
                return httpx.Response(200, json={"status": "DOWN"})
            return httpx.Response(200, json={})

        _recorder, patch = self._transport(handler)
        with patch, self._keys(), self._json_mode():
            result = self.runner.invoke(mod.app, ["--json", "status"])

        self.assertEqual(result.exit_code, 1)

    def test_the_item_options_reach_the_server(self) -> None:
        mod = self.mod
        body = (FIXTURES / "catalog-items.json").read_bytes()
        recorder, patch = self._transport(lambda request: httpx.Response(200, content=body))
        with patch, self._keys(), self._json_mode():
            result = self.runner.invoke(
                mod.app, ["--json", "items", "--page", "2", "--page-size", "5", "--query", "phone"]
            )

        self.assertEqual(result.exit_code, 0)
        params = dict(recorder.requests[0].url.params)
        self.assertEqual(params["page"], "2")
        self.assertEqual(params["pageSize"], "5")
        self.assertEqual(params["q"], "phone")
        payload = json.loads(result.stdout)
        self.assertEqual(payload["totalCount"], 1)

    def test_the_up_command_exits_with_one_when_the_stack_does_not_answer(self) -> None:
        mod = self.mod
        _recorder, patch = self._transport(lambda request: httpx.Response(200, json={"status": "DOWN"}))
        with (
            patch,
            self._keys(),
            mock.patch.object(mod, "_gradle_command", lambda: _substitute("bootJar")),
            mock.patch.object(mod, "_compose_command", lambda *argv: _substitute(*argv)),
            self._json_mode(),
        ):
            result = self.runner.invoke(mod.app, ["up", "--timeout", "0"])

        self.assertEqual(result.exit_code, 1)

    def test_the_readiness_is_polled_until_the_timeout(self) -> None:
        mod = self.mod
        calls = {"n": 0}

        def handler(request):
            calls["n"] += 1
            if calls["n"] == 1:
                return httpx.Response(200, json={"status": "DOWN"})
            raise httpx.ConnectError("connection refused")

        _recorder, patch = self._transport(handler)
        with (
            patch,
            self._keys(),
            mock.patch.object(mod, "_gradle_command", lambda: _substitute("bootJar")),
            mock.patch.object(mod, "_compose_command", lambda *argv: _substitute(*argv)),
            self._json_mode(),
        ):
            started = time.monotonic()
            result = self.runner.invoke(mod.app, ["up", "--timeout", "3"])
            elapsed = time.monotonic() - started

        self.assertEqual(result.exit_code, 1)
        # The command kept asking: the first answer was DOWN, the next ones could not
        # be reached, and only the timeout ended the wait.
        self.assertGreaterEqual(calls["n"], 2)
        # The wait ends at the deadline, not two seconds after it.
        self.assertLess(elapsed, 4)
