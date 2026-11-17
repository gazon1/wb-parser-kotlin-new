from __future__ import annotations

import importlib.util
import json
import sys
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
    inner = mod.Stats(
        total_items=1,
        total_products=2,
        total_targets=3,
        with_cashback=4,
        products_with_cashback=5,
        newest_scraped_at=datetime(2026, 11, 17, 9, 0, tzinfo=UTC),
        as_of=None,
    )
    data = {
        "mixed": [inner.model_dump(mode="json", by_alias=True), {"plain": None}],
        "freshnessMinutes": 360,
        "nextRun": "2026-11-17T12:00:00Z",
        "when": None,
        "status": mod.Sort.date_desc,
    }
    text = json.dumps(data)
    assert "[green]" not in text
    assert data["freshnessMinutes"] == 360
    assert data["when"] is None
    assert data["status"] == "DateDesc"
    assert data["nextRun"] == "2026-11-17T12:00:00Z"
    assert data["mixed"][0]["totalItems"] == 1
    assert data["mixed"][0]["asOf"] is None
    assert data["mixed"][0]["newestScrapedAt"] == "2026-11-17T09:00:00Z"
    assert data["mixed"][1] == {"plain": None}
    assert json.loads(text) == data


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
