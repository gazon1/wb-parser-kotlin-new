import importlib.util
import json
import sys
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "parser" / "wbparser.py"


def load():
    spec = importlib.util.spec_from_file_location("wbparser_cli", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


m = load()
for model in (
    m.Target,
    m.Job,
    m.Item,
    m.ItemsPage,
    m.Stats,
    m.TargetRun,
    m.RecordedError,
    m.Upcoming,
    m.Schedule,
):
    model.model_rebuild(_types_namespace=vars(m))
NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
CRON = "0 0 */2 * * *"


def target(**kw):
    base = {"id": "t1", "name": "target", "active": True}
    base.update(kw)
    return m.Target(**base)


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
        schedule = m.Schedule.model_validate(payload)

        self.assertEqual(schedule.cron, CRON)
        self.assertEqual(schedule.freshness_minutes, 360)
        self.assertEqual(schedule.active_targets, 1)
        self.assertEqual(schedule.limit, 15)
        self.assertEqual([t.id for t in schedule.due], ["due"])
        self.assertEqual(schedule.upcoming[0].target.id, "up")
        self.assertEqual(schedule.next_run, datetime(2026, 11, 17, 12, 0, tzinfo=UTC))
        self.assertEqual(schedule.earliest_due, None)

        data = m.Schedule.model_validate(payload).model_dump(by_alias=True, mode="json")
        self.assertEqual(data["freshnessMinutes"], 360)
        self.assertEqual(data["nextRun"], "2026-11-17T12:00:00Z")
        self.assertEqual(data["due"][0]["lastCrawledAt"], "2026-11-17T08:30:00Z")


class EmitSerializationTest(unittest.TestCase):
    def test_mixed_payload_contract(self):
        inner = m.Stats(
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
            "status": m.Sort.date_desc,
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


if __name__ == "__main__":
    unittest.main()
