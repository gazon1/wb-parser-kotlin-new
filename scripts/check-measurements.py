#!/usr/bin/env python3
"""
check-measurements.py — the YAML and the measurement artifact must agree.

Why this gate exists
--------------------
`known_pages_limit` used to be a number in `wb_catalog_targets.yaml` with nothing
behind it: no date, no boundary, no source. Nobody could tell whether "60" was still
true, and nobody could tell *which* of the two boundaries produced it — a catalog that
ended by itself, or one that the WB API cap cut short. The second case means the
crawl stores a slice of the category, and the truncation counter fires honestly about
it.

The measurement fixed the number but not the silence around it: the report that
produced "60" lived in `/tmp` and was gone with the machine. So the repository keeps
the measurement itself — `catalog_measurements.json`, the reduced form of the reports
— and this gate is what makes the number in the YAML checkable:

- the limit in the YAML must be the measured limit in the artifact,
- the boundary and the date of the measurement must be the ones in the artifact,
- a target that carries measurement metadata the artifact does not have is a finding:
  the metadata was written by hand, or the artifact is stale.

What it does NOT do
-------------------
It does not decide whether a measurement is *true* — that needs a live WB. It checks
that the YAML and the committed evidence say the same thing, so that a number can
never again be an assertion without evidence behind it.

It runs on a bare Python: the CI `static` job installs no packages, so the YAML is
read by `scripts/wb-catalog/targets_yaml.py`, which imports nothing outside the
standard library. A check that only runs where PyYAML happens to be installed is a
check that runs on one surface only — and the drift between the two surfaces is
exactly what the gate registry exists to prevent.

Usage:
    python3 scripts/check-measurements.py
    python3 scripts/check-measurements.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts" / "wb-catalog"))

from catalog_measurements import compare  # noqa: E402
from targets_yaml import YamlSubsetError, read_pages  # noqa: E402

DEFAULT_YAML = ROOT / "wb_catalog_targets.yaml"
DEFAULT_ARTIFACT = ROOT / "catalog_measurements.json"


def load_artifact(path: Path) -> dict:
    """The committed measurement artifact."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        raise SystemExit(f"❌ cannot read the measurement artifact {path}: {e}")


def check(yaml_path: Path, artifact_path: Path) -> list[str]:
    """The findings about the catalog: the YAML against the measurement artifact."""
    try:
        pages = read_pages(yaml_path)
    except (OSError, YamlSubsetError) as e:
        return [f"cannot read the catalog YAML {yaml_path}: {e}"]

    artifact = load_artifact(artifact_path)
    return compare(pages, artifact)


# --- the positive control -----------------------------------------------------

GOOD_YAML = """pages:
- id: '9467'
  name: Карты памяти
  url: https://www.wildberries.ru/catalog/elektronika/smartfony-i-telefony/karty-pamyati
  search_query: menu_v3_9467 карты памяти
  known_pages_limit: 60
  known_pages_limit_measured_at: '2026-10-20T13:34:31+00:00'
  known_pages_limit_boundary: api_cap
  known_pages_limit_source: measurement
"""

BROKEN_YAML = """pages:
- id: '9467'
  name: Карты памяти
  url: https://www.wildberries.ru/catalog/elektronika/smartfony-i-telefony/karty-pamyati
  search_query: menu_v3_9467 карты памяти
  known_pages_limit: 5
  known_pages_limit_measured_at: '2026-10-20T13:34:31+00:00'
  known_pages_limit_boundary: api_cap
  known_pages_limit_source: measurement
"""

GOOD_ARTIFACT = {
    "version": 1,
    "policy": "maximum-over-measurements",
    "catalogs": [
        {
            "id": "9467",
            "name": "Карты памяти",
            "boundary": "api_cap",
            "measured_at": "2026-10-20T13:34:31+00:00",
            "pages_available": 60,
            "known_pages_limit": 60,
            "wb_total": 49394,
        }
    ],
}


def run_self_test() -> int:
    """Positive control: prove the check fires on a broken fixture.

    The control runs the check against a fixture whose YAML limit was edited by hand,
    and asserts that the check says so — and that the same check is silent about the
    unedited fixture. A gate that cannot fail is a gate that enforces nothing.
    """
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        artifact_path = fixture / "catalog_measurements.json"
        artifact_path.write_text(
            json.dumps(GOOD_ARTIFACT, ensure_ascii=False, indent=2), encoding="utf-8"
        )

        good_yaml = fixture / "good.yaml"
        good_yaml.write_text(GOOD_YAML, encoding="utf-8")
        broken_yaml = fixture / "broken.yaml"
        broken_yaml.write_text(BROKEN_YAML, encoding="utf-8")

        good_findings = check(good_yaml, artifact_path)
        broken_findings = check(broken_yaml, artifact_path)

    problems: list[str] = []
    if good_findings:
        problems.append(
            "the check reported a finding on a consistent catalog: " + "; ".join(good_findings)
        )
    if not broken_findings:
        problems.append(
            "the check reported nothing on a catalog whose limit was edited by hand — "
            "it cannot fail"
        )
    elif not any("limit" in finding for finding in broken_findings):
        problems.append(
            "the check did not name the edited limit: " + "; ".join(broken_findings)
        )

    if problems:
        print("self-test FAILED — the check did not detect what it exists to catch:", file=sys.stderr)
        for problem in problems:
            print(f"  · {problem}", file=sys.stderr)
        return 1

    print("✅ self-test: the check accepts a consistent catalog and rejects an edited limit")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yaml", type=Path, default=DEFAULT_YAML, help="catalog YAML to check")
    parser.add_argument(
        "--artifact", type=Path, default=DEFAULT_ARTIFACT, help="measurement artifact to check against"
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the check detects a deliberately broken fixture",
    )
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    findings = check(args.yaml, args.artifact)
    if findings:
        print("", file=sys.stderr)
        for finding in findings:
            print(f"❌ {finding}", file=sys.stderr)
        print(
            "\nThe YAML and the measurement artifact must agree. Re-measure and re-sync:\n"
            "  python3 scripts/wb-catalog/sync_measurement_metadata.py --report <report.json>",
            file=sys.stderr,
        )
        return 1

    print("✅ the catalog YAML agrees with the measurement artifact")
    return 0


if __name__ == "__main__":
    sys.exit(main())
