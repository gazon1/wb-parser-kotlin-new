from __future__ import annotations

import importlib.util
from datetime import UTC, datetime, timedelta
from pathlib import Path


def _load_wbparser():
    path = Path(__file__).resolve().parents[1] / "parser" / "wbparser.py"
    spec = importlib.util.spec_from_file_location("wbparser_cli", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
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
