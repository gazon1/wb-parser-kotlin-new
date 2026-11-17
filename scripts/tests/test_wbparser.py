from __future__ import annotations

import asyncio
import importlib.util
import json
import sys
import threading
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from functools import cache
from pathlib import Path


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
    monkeypatch.setattr(
        mod,
        "_fetch_history",
        lambda limit=15: {
            "jobs": [job],
            "cron": "0 0 */4 * * *",
            "freshnessMinutes": 360,
            "nextRun": datetime(2026, 11, 17, 12, 0, tzinfo=UTC),
            "earliestDue": datetime(2026, 11, 17, 11, 0, tzinfo=UTC),
        },
    )
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
    monkeypatch.setattr(mod, "_fetch_schedule", lambda limit=15: schedule)
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
    monkeypatch.setattr(
        mod,
        "_fetch_runs",
        lambda limit=20, target=None, errors=10: {
            "jobs": [job],
            "targetRuns": [target_run],
            "errors": [error],
        },
    )
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

        def fetch(self):
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

        def fetch(self):
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

        def fetch(self):
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

        def fetch(self):
            calls.append(1)
            started.set()
            release.wait(5)
            return None

        def show(self, data):
            pass

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            pane.reload()
            while not started.is_set():
                await asyncio.sleep(0.01)
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
    monkeypatch.setattr(mod, "_fetch_stats", lambda: _stats(mod))

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

    monkeypatch.setattr(mod, "_fetch_stats", fetch)

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

    monkeypatch.setattr(mod, "_fetch_stats", stats)
    monkeypatch.setattr(
        mod,
        "_fetch_status",
        lambda: ({"status": "UP", "checks": {}, "crawler": {}, "egress": {}}, {"ADMIN_API_KEY": True}),
    )
    monkeypatch.setattr(mod, "_running", lambda service: False)

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

        def fetch(self):
            started.set()
            release.wait(5)
            return {"answer": 42}

        def show(self, data):
            self.shown.append(data)

    async def scenario() -> None:
        pane = Probe()
        async with _host(mod, pane).run_test():
            pane.reload()
            while not started.is_set():
                await asyncio.sleep(0.01)
        release.set()
        await asyncio.sleep(0.1)
        assert pane.shown == [], "a worker cancelled on unmount must not touch the pane"

    try:
        asyncio.run(scenario())
    finally:
        release.set()
