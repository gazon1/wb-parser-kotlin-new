import importlib.util
import json
import sys
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from pydantic_core import to_jsonable_python

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
):
    model.model_rebuild(_types_namespace=vars(m))
NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
CRON = "0 0 */2 * * *"


def target(**kw):
    base = {"id": "t1", "name": "target", "active": True}
    base.update(kw)
    return m.Target(**base)


class ClaimableAtTest(unittest.TestCase):
    def test_no_previous_crawl_is_due_now(self):
        self.assertIsNone(m.claimable_at(target(), NOW, 60))

    def test_freshness_expired_is_due_now(self):
        t = target(last_crawled_at=NOW - timedelta(minutes=61))
        self.assertIsNone(m.claimable_at(t, NOW, 60))

    def test_freshness_active_is_upcoming(self):
        t = target(last_crawled_at=NOW - timedelta(minutes=30))
        self.assertEqual(m.claimable_at(t, NOW, 60), NOW + timedelta(minutes=30))

    def test_retry_in_future_is_upcoming(self):
        t = target(last_crawled_at=NOW - timedelta(minutes=120), next_attempt_at=NOW + timedelta(minutes=15))
        self.assertEqual(m.claimable_at(t, NOW, 60), NOW + timedelta(minutes=15))

    def test_retry_and_freshness_in_future_take_later(self):
        # Freshness ends at 11:00, retry allowed at 12:00 -> claimable at 12:00.
        t = target(last_crawled_at=NOW - timedelta(minutes=60), next_attempt_at=NOW + timedelta(hours=2))
        self.assertEqual(m.claimable_at(t, NOW, 120), NOW + timedelta(hours=2))

    def test_freshness_later_than_retry_takes_freshness(self):
        t = target(last_crawled_at=NOW - timedelta(minutes=30), next_attempt_at=NOW + timedelta(minutes=10))
        self.assertEqual(m.claimable_at(t, NOW, 60), NOW + timedelta(minutes=30))

    def test_both_expired_is_due_now(self):
        t = target(last_crawled_at=NOW - timedelta(minutes=90), next_attempt_at=NOW - timedelta(minutes=5))
        self.assertIsNone(m.claimable_at(t, NOW, 60))

    def test_boundary_equal_now_is_due(self):
        t1 = target(last_crawled_at=NOW - timedelta(minutes=60))
        t2 = target(last_crawled_at=NOW - timedelta(minutes=120), next_attempt_at=NOW)
        self.assertIsNone(m.claimable_at(t1, NOW, 60))
        self.assertIsNone(m.claimable_at(t2, NOW, 60))


class PartitionDueTest(unittest.TestCase):
    def test_inactive_target_ignored(self):
        due, upcoming, earliest = m._partition_due([target(active=False)], NOW, 60)
        self.assertEqual(due, [])
        self.assertEqual(upcoming, [])
        self.assertIsNone(earliest)

    def test_no_active_targets(self):
        due, upcoming, earliest = m._partition_due([], NOW, 60)
        self.assertEqual((due, upcoming, earliest), ([], [], None))

    def test_one_due_one_upcoming(self):
        due_t = target(id="due")
        up_t = target(id="up", last_crawled_at=NOW - timedelta(minutes=30))
        due, upcoming, earliest = m._partition_due([up_t, due_t], NOW, 60)
        self.assertEqual([t.id for t in due], ["due"])
        self.assertEqual([t.id for _, t in upcoming], ["up"])
        self.assertEqual(earliest, NOW)  # due has priority for nearest run

    def test_upcoming_sorted_by_time(self):
        t_late = target(id="late", last_crawled_at=NOW - timedelta(minutes=10))
        t_mid = target(id="mid", last_crawled_at=NOW - timedelta(minutes=30))
        t_retry = target(
            id="retry", last_crawled_at=NOW - timedelta(minutes=120), next_attempt_at=NOW + timedelta(minutes=45)
        )
        due, upcoming, earliest = m._partition_due([t_late, t_retry, t_mid], NOW, 60)
        self.assertEqual(due, [])
        self.assertEqual(
            [(t.id, when) for when, t in upcoming],
            [
                ("mid", NOW + timedelta(minutes=30)),
                ("retry", NOW + timedelta(minutes=45)),
                ("late", NOW + timedelta(minutes=50)),
            ],
        )
        self.assertEqual(earliest, NOW + timedelta(minutes=30))


class NextRunTest(unittest.TestCase):
    def test_targets_not_known_is_unknown(self):
        self.assertIsNone(m._next_run(CRON, NOW, NOW, targets_known=False))

    def test_known_but_no_active_targets_no_run_expected(self):
        self.assertIsNone(m._next_run(CRON, NOW, None, targets_known=True))

    def test_known_active_due_now_returns_next_tick(self):
        # tick at 12:00, due now -> next tick
        self.assertEqual(m._next_run(CRON, NOW, NOW, targets_known=True), datetime(2026, 11, 17, 12, 0, tzinfo=UTC))

    def test_tick_later_than_availability(self):
        earliest = NOW + timedelta(minutes=30)  # 10:30, next tick 12:00
        self.assertEqual(
            m._next_run(CRON, NOW, earliest, targets_known=True), datetime(2026, 11, 17, 12, 0, tzinfo=UTC)
        )

    def test_tick_earlier_than_availability_walks_forward(self):
        earliest = datetime(2026, 11, 17, 13, 0, tzinfo=UTC)  # after 12:00 tick
        self.assertEqual(
            m._next_run(CRON, NOW, earliest, targets_known=True), datetime(2026, 11, 17, 14, 0, tzinfo=UTC)
        )

    def test_cron_with_seconds(self):
        self.assertEqual(
            m._next_run("30 0 */2 * * *", NOW, NOW, targets_known=True),
            datetime(2026, 11, 17, 10, 0, 30, tzinfo=UTC),
        )

    def test_cron_with_question_mark(self):
        self.assertEqual(
            m._next_run("0 0 */2 * * ?", NOW, NOW, targets_known=True),
            datetime(2026, 11, 17, 12, 0, tzinfo=UTC),
        )

    def test_invalid_cron_is_unknown(self):
        self.assertIsNone(m._next_run("not a cron", NOW, NOW, targets_known=True))

    def test_unschedulable_cron_is_unknown(self):
        # Feb 30 never occurs; croniter raises on next computation.
        self.assertIsNone(m._next_run("0 0 0 30 2 *", NOW, NOW, targets_known=True))


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
        payload = {
            "mixed": [inner, {"plain": None}],
            "freshnessMinutes": 360,
            "nextRun": datetime(2026, 11, 17, 12, 0, tzinfo=UTC),
            "when": None,
            "status": m.Sort.date_desc,
        }
        data = to_jsonable_python(payload, by_alias=True)
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
