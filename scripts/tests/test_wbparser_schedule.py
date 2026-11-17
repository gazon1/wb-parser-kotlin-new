from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path


def _load_wbparser():
    path = Path(__file__).resolve().parents[1] / "parser" / "wbparser.py"
    spec = importlib.util.spec_from_file_location("wbparser_cli", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


mod = _load_wbparser()
for model in (
    mod.Target,
    mod.Job,
    mod.Item,
    mod.ItemsPage,
    mod.Stats,
    mod.TargetRun,
    mod.RecordedError,
    mod.Upcoming,
    mod.Schedule,
):
    model.model_rebuild(_types_namespace=vars(mod))

NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
CRON = "0 0 */2 * * *"


def target(**kw):
    base = {"id": "t1", "name": "target", "active": True}
    base.update(kw)
    return mod.Target(**base)


class ScheduleResponseTest(unittest.TestCase):
    def test_the_answer_the_server_writes_parses(self):
        payload = {
            "cron": CRON,
            "freshnessMinutes": 360,
            "activeTargets": 1,
            "targets": [target()],
            "due": [target(id="due", last_crawled_at=NOW - timedelta(minutes=90))],
            "upcoming": [
                {"at": "2026-11-17T11:30:00Z", "target": target(id="up", last_crawled_at=NOW - timedelta(minutes=30))}
            ],
            "nextRun": "2026-11-17T12:00:00Z",
            "earliestDue": None,
            "limit": 15,
        }
        schedule = mod.Schedule.model_validate(payload)

        self.assertEqual(schedule.cron, CRON)
        self.assertEqual(schedule.freshness_minutes, 360)
        self.assertEqual(schedule.active_targets, 1)
        self.assertEqual(schedule.limit, 15)
        self.assertEqual([t.id for t in schedule.due], ["due"])
        self.assertEqual(schedule.upcoming[0].target.id, "up")
        self.assertEqual(schedule.next_run, datetime(2026, 11, 17, 12, 0, tzinfo=UTC))
        self.assertEqual(schedule.earliest_due, None)

        data = mod.Schedule.model_validate(payload).model_dump(by_alias=True, mode="json")
        self.assertEqual(data["freshnessMinutes"], 360)
        self.assertEqual(data["nextRun"], "2026-11-17T12:00:00Z")
        self.assertEqual(data["due"][0]["lastCrawledAt"], "2026-11-17T08:30:00Z")


class EmitSerializationTest(unittest.TestCase):
    def test_mixed_payload_contract(self):
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
        self.assertNotIn("[green]", text)
        self.assertEqual(data["freshnessMinutes"], 360)
        self.assertIsNone(data["when"])
        self.assertEqual(data["status"], "DateDesc")
        self.assertEqual(data["nextRun"], "2026-11-17T12:00:00Z")
        self.assertEqual(data["mixed"][0]["totalItems"], 1)
        self.assertEqual(data["mixed"][0]["asOf"], None)
        self.assertEqual(data["mixed"][0]["newestScrapedAt"], "2026-11-17T09:00:00Z")
        self.assertEqual(data["mixed"][1], {"plain": None})
        self.assertEqual(json.loads(text), data)


def _target(name: str, **kwargs):
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


class PartitionByDueTest(unittest.TestCase):
    def test_uses_latest_of_lease_and_backoff(self) -> None:
        now = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
        leased = target(lease_until=now + timedelta(hours=1), next_attempt_at=now + timedelta(hours=2))

        due, upcoming = mod._partition_by_due([leased], now, freshness_minutes=360)

        self.assertEqual(due, [])
        self.assertEqual(upcoming, [(now + timedelta(hours=2), leased)])

    def test_uses_latest_of_lease_and_freshness(self) -> None:
        now = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
        leased = target(last_crawled_at=now - timedelta(minutes=30), lease_until=now + timedelta(minutes=10))

        due, upcoming = mod._partition_by_due([leased], now, freshness_minutes=360)

        self.assertEqual(due, [])
        self.assertEqual(upcoming, [(now + timedelta(minutes=330), leased)])


if __name__ == "__main__":
    unittest.main()
