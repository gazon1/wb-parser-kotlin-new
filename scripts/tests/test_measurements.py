#!/usr/bin/env python3
"""Unit tests for the measurement metadata: the policy, the artifact, the gate.

Why these tests exist
---------------------
The measurement metadata is the difference between a number and a fact. `60` in the
YAML is an assertion; `60, measured on 2026-10-20, ending at the API cap` is a fact
somebody can check. The machinery that carries the fact around is easy to break
quietly:

- the *maximum* policy can become the *last* policy without anybody noticing, and then
  the YAML depends on which measurement happened to be written last,
- the artifact can stop agreeing with the YAML, and the gate stops finding anything,
- the reader of the YAML can silently skip a line it does not understand — and a
  hand-edited limit then passes the very gate that exists to catch it.

The tests below are the pure part: no browser, no network, no database. The gate's
own `--self-test` proves the end-to-end behaviour on a deliberately broken fixture.

Written on `unittest.TestCase` by the convention of the neighbouring tests: the same
file runs under the gate (`python3 -m unittest discover -s scripts/tests`) and under
pytest. It imports nothing outside the standard library — the gate's Python has no
`click` and no `PyYAML`.
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
CATALOG_SCRIPTS = SCRIPTS / "wb-catalog"
REPO = SCRIPTS.parent

# `catalog_measurements` imports `catalog_report` by name — they live side by side,
# not in a package.
sys.path.insert(0, str(CATALOG_SCRIPTS))


def load(name: str, path: Path):
    """Import a script by path — they are not a package, they are files next to each other."""
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader, f"cannot load {path}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


catalog_report = load("catalog_report", CATALOG_SCRIPTS / "catalog_report.py")
catalog_measurements = load("catalog_measurements", CATALOG_SCRIPTS / "catalog_measurements.py")
targets_yaml = load("targets_yaml", CATALOG_SCRIPTS / "targets_yaml.py")
check_measurements = load("check_measurements", SCRIPTS / "check-measurements.py")


def measured_record(
    catalog_id: str = "9467",
    limit: int = 60,
    boundary: str = "api_cap",
    measured_at: str = "2026-10-20T13:34:31+00:00",
    subject_id: int | None = None,
) -> dict:
    """A report record of a measured catalog."""
    record = {
        "id": catalog_id,
        "name": "Карты памяти",
        "status": "measured",
        "measured_at": measured_at,
        "boundary": boundary,
        "known_pages_limit": limit,
        "pages_available": limit,
        "wb_total": 49394,
        "error": None,
    }
    if subject_id is not None:
        record["wb_subject_id"] = subject_id
    return record


class MeasuredLimitsTest(unittest.TestCase):
    """The policy: the maximum over the measurements, never the last one."""

    def test_the_largest_limit_wins(self):
        # The catalog grew between the two passes. The number in the YAML must be the
        # one that is true of both, not the one that happened to be written last.
        report = {
            "catalogs": [
                measured_record(limit=59, measured_at="2026-10-19T10:00:00+00:00"),
                measured_record(limit=60, measured_at="2026-10-20T10:00:00+00:00"),
            ]
        }

        limits = catalog_report.measured_limits(report)

        self.assertEqual(limits["9467"]["known_pages_limit"], 60)

    def test_the_last_measurement_does_not_win_when_it_is_smaller(self):
        report = {
            "catalogs": [
                measured_record(limit=60, measured_at="2026-10-19T10:00:00+00:00"),
                measured_record(limit=59, measured_at="2026-10-20T10:00:00+00:00"),
            ]
        }

        limits = catalog_report.measured_limits(report)

        self.assertEqual(limits["9467"]["known_pages_limit"], 60)

    def test_a_tie_is_broken_by_the_newer_pass(self):
        report = {
            "catalogs": [
                measured_record(limit=60, measured_at="2026-10-19T10:00:00+00:00"),
                measured_record(limit=60, measured_at="2026-10-20T10:00:00+00:00"),
            ]
        }

        limits = catalog_report.measured_limits(report)

        self.assertEqual(limits["9467"]["measured_at"], "2026-10-20T10:00:00+00:00")

    def test_an_unmeasured_record_carries_no_limit(self):
        report = {
            "catalogs": [
                {
                    "id": "9467",
                    "status": "blocked",
                    "error": "challenge",
                    "known_pages_limit": 60,
                }
            ]
        }

        self.assertEqual(catalog_report.measured_limits(report), {})

    def test_merging_keeps_the_length_of_a_pass_that_only_saw_the_subject(self):
        # A pass that observed only the subject id must not erase the length measured
        # by an earlier pass: the two are different facts.
        existing = {"catalogs": [measured_record(limit=60)]}
        fresh = {
            "catalogs": [
                {
                    "id": "9467",
                    "status": "measured",
                    "measured_at": "2026-10-21T10:00:00+00:00",
                    "wb_subject_id": 1260,
                }
            ]
        }

        merged = catalog_report.merge_report(existing, fresh)

        record = merged["catalogs"][0]
        self.assertEqual(record["known_pages_limit"], 60)
        self.assertEqual(record["wb_subject_id"], 1260)

    def test_a_successful_measurement_clears_the_error_of_the_previous_one(self):
        existing = {
            "catalogs": [
                {"id": "9467", "status": "blocked", "error": "challenge", "known_pages_limit": None}
            ]
        }
        fresh = {"catalogs": [measured_record(limit=60)]}

        merged = catalog_report.merge_report(existing, fresh)

        self.assertNotIn("error", merged["catalogs"][0])


class BestMeasurementsTest(unittest.TestCase):
    """The best measurement per catalog — and the subject id that is not part of it."""

    def test_the_subject_id_survives_a_pass_that_measured_a_smaller_limit(self):
        # The subject id is a property of the catalog's products, not of the
        # measurement that produced the limit. A pass that observed it must not lose
        # it because another pass measured a bigger limit.
        records = [
            measured_record(limit=60, measured_at="2026-10-19T10:00:00+00:00"),
            measured_record(limit=59, measured_at="2026-10-20T10:00:00+00:00", subject_id=1260),
        ]

        limits = catalog_measurements.best_measurements(records)

        self.assertEqual(limits["9467"]["known_pages_limit"], 60)
        self.assertEqual(limits["9467"]["wb_subject_id"], 1260)

    def test_the_limit_and_its_date_come_from_the_same_measurement(self):
        records = [
            measured_record(limit=60, measured_at="2026-10-19T10:00:00+00:00"),
            measured_record(limit=59, measured_at="2026-10-20T10:00:00+00:00"),
        ]

        limits = catalog_measurements.best_measurements(records)

        self.assertEqual(limits["9467"]["measured_at"], "2026-10-19T10:00:00+00:00")


class MetadataTest(unittest.TestCase):
    """The metadata of a target: what it is, and where it is written."""

    def test_the_metadata_sits_next_to_the_limit(self):
        page = {
            "id": "9467",
            "name": "Карты памяти",
            "known_pages_limit": 5,
            "wb_search_query": "menu_v3_9467 карты памяти",
        }

        updated = catalog_measurements.page_with_metadata(page, measured_record(subject_id=1260))

        self.assertEqual(
            list(updated.keys()),
            [
                "id",
                "name",
                "known_pages_limit",
                "known_pages_limit_measured_at",
                "known_pages_limit_boundary",
                "known_pages_limit_source",
                "wb_subject_id",
                "wb_search_query",
            ],
        )
        self.assertEqual(updated["known_pages_limit"], 60)
        self.assertEqual(updated["known_pages_limit_boundary"], "api_cap")
        self.assertEqual(updated["known_pages_limit_source"], "measurement")
        self.assertEqual(updated["wb_subject_id"], 1260)

    def test_a_subject_the_measurement_did_not_observe_is_dropped(self):
        page = {"id": "9467", "known_pages_limit": 60, "wb_subject_id": 1260}

        updated = catalog_measurements.page_with_metadata(page, measured_record())

        self.assertNotIn("wb_subject_id", updated)

    def test_the_artifact_keeps_only_what_the_gate_compares(self):
        records = catalog_measurements.artifact_records({"9467": measured_record(subject_id=1260)})

        self.assertEqual(
            sorted(records[0].keys()),
            [
                "boundary",
                "id",
                "known_pages_limit",
                "measured_at",
                "name",
                "pages_available",
                "wb_subject_id",
                "wb_total",
            ],
        )

    def test_the_artifact_is_ordered_by_the_numeric_id(self):
        limits = {
            "9467": measured_record(catalog_id="9467"),
            "7588": measured_record(catalog_id="7588"),
            "132017": measured_record(catalog_id="132017"),
        }

        records = catalog_measurements.artifact_records(limits)

        self.assertEqual([r["id"] for r in records], ["7588", "9467", "132017"])


class CompareTest(unittest.TestCase):
    """The gate's logic: what a disagreement between the YAML and the artifact is."""

    def pages(self, **overrides) -> list:
        page = {
            "id": "9467",
            "known_pages_limit": 60,
            "known_pages_limit_measured_at": "2026-10-20T13:34:31+00:00",
            "known_pages_limit_boundary": "api_cap",
            "known_pages_limit_source": "measurement",
        }
        page.update(overrides)
        return [page]

    def artifact(self, **overrides) -> dict:
        record = measured_record()
        record.update(overrides)
        return {"version": 1, "catalogs": [record]}

    def test_a_consistent_catalog_has_no_findings(self):
        findings = catalog_measurements.compare(self.pages(), self.artifact())

        self.assertEqual(findings, [])

    def test_an_edited_limit_is_a_finding(self):
        findings = catalog_measurements.compare(self.pages(known_pages_limit=5), self.artifact())

        self.assertEqual(len(findings), 1)
        self.assertIn("edited by hand", findings[0])

    def test_an_edited_boundary_is_a_finding(self):
        findings = catalog_measurements.compare(
            self.pages(known_pages_limit_boundary="catalog_end"), self.artifact()
        )

        self.assertEqual(len(findings), 1)
        self.assertIn("boundary", findings[0])

    def test_an_edited_date_is_a_finding(self):
        findings = catalog_measurements.compare(
            self.pages(known_pages_limit_measured_at="2026-01-01T00:00:00+00:00"), self.artifact()
        )

        self.assertEqual(len(findings), 1)
        self.assertIn("measured at", findings[0])

    def test_a_missing_source_is_a_finding(self):
        page = self.pages()
        del page[0]["known_pages_limit_source"]

        findings = catalog_measurements.compare(page, self.artifact())

        self.assertEqual(len(findings), 1)
        self.assertIn("source", findings[0])

    def test_a_measured_catalog_missing_from_the_yaml_is_a_finding(self):
        findings = catalog_measurements.compare([], self.artifact())

        self.assertEqual(len(findings), 1)
        self.assertIn("not in the YAML", findings[0])

    def test_metadata_the_artifact_does_not_have_is_a_finding(self):
        # The metadata was written by hand, or the artifact is stale.
        page = self.pages()
        page[0]["id"] = "9999"

        findings = catalog_measurements.compare(page, self.artifact())

        self.assertEqual(len(findings), 2)
        self.assertTrue(any("not in the YAML" in finding for finding in findings))
        self.assertTrue(any("artifact has no measurement of it" in finding for finding in findings))

    def test_an_empty_artifact_finds_nothing_about_the_limits(self):
        # An empty artifact cannot confirm anything, and a gate that passes against it
        # is a gate that enforces nothing.
        findings = catalog_measurements.compare(self.pages(), {"version": 1, "catalogs": []})

        self.assertEqual(len(findings), 1)
        self.assertIn("no record", findings[0])

    def test_the_same_instant_written_differently_is_the_same_measurement(self):
        # `Z` and `+00:00` are the same instant written differently.
        findings = catalog_measurements.compare(
            self.pages(known_pages_limit_measured_at="2026-10-20T13:34:31Z"), self.artifact()
        )

        self.assertEqual(findings, [])


class TargetsYamlTest(unittest.TestCase):
    """The reader of the catalog YAML — the one that runs without PyYAML."""

    def test_it_reads_the_committed_catalog(self):
        pages = targets_yaml.read_pages(REPO / "wb_catalog_targets.yaml")

        self.assertEqual(len(pages), 132)
        self.assertEqual(pages[0]["id"], "9463")
        self.assertEqual(pages[0]["known_pages_limit"], 60)

    def test_it_agrees_with_pyyaml_when_pyyaml_is_there(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed — the gate's Python does not have it")

        pages = targets_yaml.read_pages(REPO / "wb_catalog_targets.yaml")
        reference = yaml.safe_load((REPO / "wb_catalog_targets.yaml").read_text(encoding="utf-8"))

        self.assertEqual(pages, reference["pages"])

    def test_it_refuses_a_shape_it_does_not_understand(self):
        # A reader that skipped what it did not understand would let a hand-edited
        # limit through the very gate that exists to catch it.
        with self.assertRaises(targets_yaml.YamlSubsetError):
            targets_yaml.parse_pages("pages:\n- id: '1'\n  nested:\n    deeper: 1\n")

    def test_it_refuses_an_unknown_key(self):
        with self.assertRaises(targets_yaml.YamlSubsetError):
            targets_yaml.parse_pages("pages:\n- id: '1'\n  surprise: 1\n")

    def test_it_reads_the_measurement_metadata(self):
        pages = targets_yaml.parse_pages(
            "pages:\n"
            "- id: '9467'\n"
            "  known_pages_limit: 60\n"
            "  known_pages_limit_measured_at: '2026-10-20T13:34:31+00:00'\n"
            "  known_pages_limit_boundary: api_cap\n"
            "  known_pages_limit_source: measurement\n"
        )

        self.assertEqual(pages[0]["known_pages_limit"], 60)
        self.assertEqual(pages[0]["known_pages_limit_boundary"], "api_cap")
        self.assertEqual(pages[0]["known_pages_limit_measured_at"], "2026-10-20T13:34:31+00:00")


class GateTest(unittest.TestCase):
    """The gate itself: it must fail on a broken fixture and pass on a good one."""

    def test_the_self_test_passes(self):
        self.assertEqual(check_measurements.run_self_test(), 0)

    def test_the_gate_passes_on_the_committed_catalog(self):
        findings = check_measurements.check(
            REPO / "wb_catalog_targets.yaml", REPO / "catalog_measurements.json"
        )

        self.assertEqual(findings, [])

    def test_the_gate_fails_on_a_hand_edited_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp)
            yaml_path = fixture / "wb_catalog_targets.yaml"
            text = (REPO / "wb_catalog_targets.yaml").read_text(encoding="utf-8")
            yaml_path.write_text(
                text.replace("  known_pages_limit: 60\n", "  known_pages_limit: 5\n", 1),
                encoding="utf-8",
            )

            findings = check_measurements.check(
                yaml_path, REPO / "catalog_measurements.json"
            )

        self.assertTrue(findings)
        self.assertTrue(any("edited by hand" in finding for finding in findings))


class ArtifactTest(unittest.TestCase):
    """The committed artifact: the evidence the gate compares the YAML against."""

    def test_it_covers_every_measured_target(self):
        artifact = json.loads((REPO / "catalog_measurements.json").read_text(encoding="utf-8"))
        pages = targets_yaml.read_pages(REPO / "wb_catalog_targets.yaml")

        self.assertEqual(len(artifact["catalogs"]), 132)
        self.assertEqual(
            {record["id"] for record in artifact["catalogs"]},
            {str(page["id"]) for page in pages},
        )

    def test_every_record_carries_the_evidence_of_its_limit(self):
        artifact = json.loads((REPO / "catalog_measurements.json").read_text(encoding="utf-8"))

        for record in artifact["catalogs"]:
            self.assertIn(record["boundary"], ("catalog_end", "api_cap"))
            self.assertIsNotNone(record["measured_at"])
            self.assertIsNotNone(record["known_pages_limit"])


if __name__ == "__main__":
    unittest.main()
