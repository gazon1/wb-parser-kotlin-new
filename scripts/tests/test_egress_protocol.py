#!/usr/bin/env python3
"""Unit tests for the measurement protocol: the runner and the gate.

Why these tests exist
---------------------
The protocol is a list of rules, and the first block broke four of them without
anybody noticing at the time: the rules were wishes. Two things make them
mechanical now, and both are easy to break quietly:

- the runner shuffles the order with a recorded seed, enforces the pause between
  the runs on one address, and writes the report FROM the data — a change in the
  aggregation would silently change every number the report shows;
- the gate holds the committed report to the raw JSON next to it — a change in the
  table parsing would make the gate stop finding anything, and a gate that cannot
  find anything reports success forever.

The tests below are the pure part: no browser, no network, no database. The
gate's own `--self-test` proves the end-to-end behaviour on a deliberately broken
fixture.

Written on `unittest.TestCase` by the convention of the neighbouring tests: the
same file runs under the gate (`python3 -m unittest discover -s scripts/tests`)
and under pytest. It imports nothing outside the standard library.
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
REPO = SCRIPTS.parent


def load(name: str, filename: str):
    """Import a script by path — they are not a package, they are files next to each other."""
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader, f"cannot load {filename}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


runner = load("measure_egress", "measure_egress.py")
gate = load("check_egress_block", "check-egress-block.py")


def a_run(label: str, address: str = "IP1", **extra) -> dict:
    """One run of a plan."""
    run = {"label": label, "profile": f"profile-{label}", "address": address, "delay": 3.0}
    run.update(extra)
    return run


def a_record(label: str, address: str = "IP1", **extra) -> dict:
    """One raw record, as the runner writes it."""
    record = {
        "label": label,
        "profile": f"profile-{label}",
        "address": address,
        "proxy_class": "direct",
        "delay": 3.0,
        "requests": 40,
        "refused": 1,
        "errored": 0,
        "succeeded": 39,
        "refusal_ratio": 0.025,
        "warmup_ok": True,
        "warmup_attempts": 1,
        "repeat": 1,
        "started_at": "2026-10-21T10:00:00Z",
        "finished_at": "2026-10-21T10:02:00Z",
    }
    record.update(extra)
    return record


class ThePlanTest(unittest.TestCase):
    """A plan that does not say enough is refused before anything is measured."""

    def test_a_plan_without_runs_is_refused(self):
        with self.assertRaises(runner.PlanError):
            runner.validate({"block": "b", "count": 40, "runs": []})

    def test_a_plan_without_a_count_is_refused(self):
        with self.assertRaises(runner.PlanError):
            runner.validate({"block": "b", "runs": [a_run("A1")]})

    def test_a_run_without_an_address_is_refused(self):
        with self.assertRaises(runner.PlanError):
            runner.validate({"block": "b", "count": 40, "runs": [{"label": "A1", "profile": "p"}]})

    def test_two_runs_with_the_same_label_are_refused(self):
        with self.assertRaises(runner.PlanError):
            runner.validate({"block": "b", "count": 40, "runs": [a_run("A1"), a_run("A1")]})

    def test_a_valid_plan_passes(self):
        runner.validate({"block": "b", "count": 40, "runs": [a_run("A1"), a_run("A2", "IP2")]})


class TheOrderTest(unittest.TestCase):
    """The order is shuffled, and the seed is what makes it reproducible."""

    def test_the_same_seed_gives_the_same_order(self):
        runs = [a_run(f"A{index}") for index in range(7)]
        self.assertEqual(
            [run["label"] for run in runner.order_for(runs, 42)],
            [run["label"] for run in runner.order_for(runs, 42)],
        )

    def test_a_different_seed_gives_a_different_order(self):
        runs = [a_run(f"A{index}") for index in range(7)]
        self.assertNotEqual(
            [run["label"] for run in runner.order_for(runs, 42)],
            [run["label"] for run in runner.order_for(runs, 43)],
        )


class TheEvaluationTest(unittest.TestCase):
    """The protocol's rules, checked against what was done."""

    def setUp(self):
        self.plan = {
            "block": "b",
            "count": 40,
            "repeats": 3,
            "combat_class": "direct",
            "runs": [a_run("A1"), a_run("A2", "IP2")],
        }

    def test_three_repeats_on_one_address_pass_the_rules(self):
        records = []
        for repeat in range(1, 4):
            records.append(
                a_record("A1", repeat=repeat, started_at=f"2026-10-21T{repeat:02d}:00:00Z")
            )
            records.append(
                a_record("A2", "IP2", repeat=repeat, started_at=f"2026-10-21T{repeat:02d}:30:00Z")
            )
        findings = runner.evaluate(records, self.plan, [["A2", "A1"], ["A2", "A1"], ["A2", "A1"]])
        failed = [finding["rule"] for finding in findings if not finding["ok"]]
        self.assertEqual(failed, [])

    def test_the_parts_of_one_concurrent_run_are_not_a_missed_pause(self):
        """A6 is two profiles on one address at the same time: ONE run.

        The gap between its two parts is zero by construction — and it is not a
        pause that was skipped. A gate that measured the gap between the PARTS
        would fail every correct A6.
        """
        records = []
        for repeat in range(1, 4):
            records.append(
                a_record("A6", repeat=repeat, started_at=f"2026-10-21T{repeat:02d}:00:00Z")
            )
            records.append(
                a_record("A6", repeat=repeat, started_at=f"2026-10-21T{repeat:02d}:00:00Z")
            )
        findings = runner.evaluate(records, self.plan, [["A6-a"], ["A6-a"], ["A6-a"]])
        pause = next(finding for finding in findings if finding["rule"].startswith("пауза 30–60"))
        self.assertTrue(pause["ok"], pause["detail"])

    def test_a_paired_run_is_one_run(self):
        records = [
            a_record("A6", repeat=1, started_at="2026-10-21T10:00:00Z"),
            a_record("A6", repeat=1, started_at="2026-10-21T10:00:00Z"),
        ]
        instances = runner.run_instances(records)
        self.assertEqual(len(instances), 1)
        self.assertEqual(instances[0]["addresses"], ["IP1"])

    def test_the_volume_is_measured_per_part(self):
        """A paired run makes `count` requests per profile; the run's total is twice that."""
        plan = {
            "block": "b",
            "count": 40,
            "repeats": 1,
            "combat_class": "direct",
            "runs": [
                {
                    "label": "A6",
                    "mode": "concurrent",
                    "parts": [
                        {"profile": "p1", "address": "IP1", "delay": 3.0},
                        {"profile": "p2", "address": "IP1", "delay": 3.0},
                    ],
                }
            ],
        }
        records = [
            a_record("A6", repeat=1, started_at="2026-10-21T10:00:00Z"),
            a_record("A6", repeat=1, started_at="2026-10-21T10:00:00Z"),
        ]
        findings = runner.evaluate(records, plan, [["A6"]])
        volume = next(finding for finding in findings if finding["rule"].startswith("фиксированный"))
        self.assertTrue(volume["ok"], volume["detail"])

    def test_one_repeat_fails_the_repeat_rule(self):
        findings = runner.evaluate([a_record("A1")], self.plan, [["A1"]])
        repeats = next(finding for finding in findings if finding["rule"].startswith("не менее трёх"))
        self.assertFalse(repeats["ok"])

    def test_a_fixed_order_fails_the_order_rule(self):
        findings = runner.evaluate([a_record("A1")], self.plan, [["A1", "A2"]])
        order = next(finding for finding in findings if finding["rule"].startswith("рандомизированный"))
        self.assertFalse(order["ok"])

    def test_a_short_pause_fails_the_pause_rule(self):
        records = [
            a_record("A1", started_at="2026-10-21T10:00:00Z"),
            a_record("A1", started_at="2026-10-21T10:10:00Z"),
        ]
        findings = runner.evaluate(records, self.plan, [["A1"]])
        pause = next(finding for finding in findings if finding["rule"].startswith("пауза 30–60"))
        self.assertFalse(pause["ok"])

    def test_two_days_fail_the_window_rule(self):
        records = [
            a_record("A1", started_at="2026-10-21T10:00:00Z"),
            a_record("A1", started_at="2026-10-22T10:00:00Z"),
        ]
        findings = runner.evaluate(records, self.plan, [["A1"]])
        window = next(finding for finding in findings if finding["rule"] == "одно и то же окно суток")
        self.assertFalse(window["ok"])

    def test_a_run_with_a_cold_session_is_not_a_measurement(self):
        """A cold session is refused by WB — the run measures the challenge, not the address."""
        records = [a_record("A1", warmup_ok=False)]
        findings = runner.evaluate(records, self.plan, [["A1"]])
        warmup = next(
            finding for finding in findings if finding["rule"].startswith("сессия была прогрета")
        )
        self.assertFalse(warmup["ok"])

    def test_a_class_that_is_not_the_combat_one_fails_the_class_rule(self):
        records = [a_record("A1", proxy_class="datacenter")]
        findings = runner.evaluate(records, self.plan, [["A1"]])
        klass = next(finding for finding in findings if finding["rule"].startswith("прокси того же"))
        self.assertFalse(klass["ok"])


class TheReportTest(unittest.TestCase):
    """The report is written from the data, so its numbers are the data's."""

    def test_the_report_shows_the_sums_over_the_repeats(self):
        records = [a_record("A1", refused=1), a_record("A1", refused=3, errored=1, succeeded=36)]
        rows = runner.aggregate(records)
        self.assertEqual(rows[0]["requests"], 80)
        self.assertEqual(rows[0]["refused"], 4)
        self.assertEqual(rows[0]["errored"], 1)

    def test_the_report_is_written_and_holds_the_verdict(self):
        plan = {
            "block": "b",
            "count": 40,
            "repeats": 1,
            "combat_class": "direct",
            "runs": [a_run("A1")],
            "catalog": 15621,
        }
        records = [a_record("A1")]
        findings = runner.evaluate(records, plan, [["A1"]])
        rows = runner.aggregate(records)
        with tempfile.TemporaryDirectory() as work:
            target = Path(work) / "b.md"
            runner.write_report(target, plan, records, rows, findings, [["A1"]], 7, 30)
            text = target.read_text(encoding="utf-8")
        self.assertIn("| A1 |", text)
        self.assertIn("НЕ прошёл гейт протокола", text)


class TheGateTest(unittest.TestCase):
    """The gate holds a committed report to the raw data next to it."""

    def write_block(self, work: Path, report: str, records: list[dict], meta: dict | None = None) -> Path:
        block = work / "b"
        raw = block / "raw" / "b"
        raw.mkdir(parents=True, exist_ok=True)
        for index, record in enumerate(records):
            (raw / f"{record['label']}-{index}.json").write_text(
                json.dumps(record), encoding="utf-8"
            )
        if meta is not None:
            (raw / "block.json").write_text(json.dumps(meta), encoding="utf-8")
        target = block / "b.md"
        target.write_text(report, encoding="utf-8")
        return target

    A_REPORT = """# Блок замера b — результаты

| Прогон | Профиль | Адрес | delay | запросов | отказов | доля | транспортных | успешных |
|---|---|---|---|---|---|---|---|---|
| A1 | p | IP1 | 3.0 | 40 | 1 | 2.5 % | 0 | 39 |

| Правило | Выполнено | Комментарий |
|---|---|---|
| Фиксированный объём 40 запросов на прогон | да | |
| ≥3 повторов на прогон | нет | один блок |

**Блок НЕ прошёл гейт протокола.**
"""

    def test_an_honest_report_passes(self):
        with tempfile.TemporaryDirectory() as work:
            report = self.write_block(Path(work), self.A_REPORT, [a_record("A1")])
            findings, _ = gate.check_block(report)
        self.assertEqual(findings, [])

    def test_a_number_that_does_not_match_the_raw_data_is_a_finding(self):
        tampered = self.A_REPORT.replace("| 40 | 1 | 2.5 % |", "| 40 | 9 | 22.5 % |")
        with tempfile.TemporaryDirectory() as work:
            report = self.write_block(Path(work), tampered, [a_record("A1")])
            findings, _ = gate.check_block(report)
        self.assertTrue(any("says" in finding for finding in findings))

    def test_a_claim_of_yes_without_the_data_is_a_finding(self):
        claiming = self.A_REPORT.replace(
            "| ≥3 повторов на прогон | нет | один блок |",
            "| ≥3 повторов на прогон | да | |",
        )
        with tempfile.TemporaryDirectory() as work:
            report = self.write_block(Path(work), claiming, [a_record("A1")])
            findings, _ = gate.check_block(report)
        self.assertTrue(any("does not support it" in finding for finding in findings))

    def test_a_verdict_that_contradicts_the_rules_is_a_finding(self):
        claiming = self.A_REPORT.replace("**Блок НЕ прошёл гейт протокола.**", "**Блок прошёл гейт протокола.**")
        with tempfile.TemporaryDirectory() as work:
            report = self.write_block(Path(work), claiming, [a_record("A1")])
            findings, _ = gate.check_block(report)
        self.assertTrue(any("claims the block passed" in finding for finding in findings))

    def test_an_inconsistent_raw_record_is_a_finding(self):
        broken = a_record("A1")
        broken["succeeded"] = 1  # 40 requests, 1 refused, 1 succeeded — the rest is missing
        with tempfile.TemporaryDirectory() as work:
            report = self.write_block(Path(work), self.A_REPORT, [broken])
            findings, _ = gate.check_block(report)
        self.assertTrue(any("inconsistent" in finding for finding in findings))


class TheCommittedBlocksTest(unittest.TestCase):
    """The blocks that are committed must pass the gate that guards them."""

    def test_the_committed_blocks_pass(self):
        measurements = REPO / "docs" / "operations" / "egress-measurements"
        findings, _ = gate.check_all(measurements)
        self.assertEqual(findings, [])


if __name__ == "__main__":
    unittest.main()
