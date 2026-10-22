#!/usr/bin/env python3
"""Raise the coverage floor to what the build actually produced.

The floor is a ratchet: it may go up whenever the coverage does, and it may go
down only when the code that was covered is gone — which is a deliberate act,
not an accident of a refactor.

## The floor keeps a percentage point of headroom

The floor is a whole percentage at least one point *below* the measured
coverage, not the measurement itself. The margin matters, and it is not
cosmetic:

- The application module measured exactly 25.0000% (65 of 260 lines), so a
  floor of 25% had no margin at all. Adding one uncovered line to the module
  would have dropped it to 24.9% and turned the gate red over a change that
  lost nothing covered.
- The crawler module measured 50.05% (935 of 1868 lines), so a floor of 50%
  had a margin of a single line. One line is not a margin — it is a rounding
  error waiting for a slow CI machine.

A floor that trips on an unrelated edit is a tripwire, not a ratchet, and it
teaches people to ignore the gate. One percentage point is about two lines in
the application module and about twenty in the crawler module: enough room for
ordinary work, small enough that a real loss of coverage is still caught.

Usage
-----
    python3 scripts/raise-kover-floor.py [--allow-drop REASON]

    --allow-drop REASON   lower the floor to the measured coverage, and record
                          the reason in the floor file. Without this flag a drop
                          is refused: the floor only moves down on purpose.

Exit codes
----------
    0 — the floor file was rewritten (or was already at the measured coverage)
    1 — a module is below its floor and no reason was given
    2 — a report was missing or unreadable, or the floor file is absent
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: How far below the measurement the floor is kept, in percentage points.
HEADROOM_POINTS = 1.0


def _load_gate():
    """Import the gate script, whose file name has a hyphen in it.

    The two scripts share the floor file format and the report parsing, and two
    copies of a format is exactly the disagreement this change set out to end.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location("check_coverage", ROOT / "scripts" / "check-coverage.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("scripts/check-coverage.py could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_GATE = _load_gate()
FLOOR_FILE = _GATE.FLOOR_FILE
REPORT_FILES = _GATE.REPORT_FILES
line_coverage = _GATE.line_coverage
parse_floor_file = _GATE.parse_floor_file
module_coverage = _GATE.module_coverage

HEADER = [
    "# Minimum line coverage percentage, one per module: `<module> <percent>`.",
    "# The floor is the gate: scripts/check-coverage.py compares it with the report",
    "# the build produced. Raise it when coverage grows: ./scripts/raise-kover-floor.py",
    "#",
    "# The floor used to live in wbparser/build.gradle.kts as `minBound(45)` while this",
    "# file said 44 — two numbers for one guarantee, and the build enforced the one",
    "# nobody had recorded. This file is the only place the floor exists now.",
    "#",
    "# History:",
    "# - 45% — baseline from first instrumentation (2026-10-10).",
    "# - 44% — raise after PRs 37-41: CircuitBreaker, BatchUpsert, MicrometerMetrics (2026-10-14).",
    "# - 49% / 24% — the floors after the advisory-lock and crawl_jobs work,",
    "#   each a percentage point below what the tests reach: `app` 24% against",
    "#   25.0% (65 of 260 lines), `wbparser` 49% against 50.05% (935 of 1868).",
    "#   The headroom is what keeps an unrelated edit from tripping the gate.",
    "# - 70% / 25% — after the crawl-runner coverage work: `wbparser` 70% against",
    "#   71.3% (CrawlRunner now covered against a real PostgreSQL, 0/153 → 117/151),",
    "#   `app` 25% against 26.5%.",
    "# - 71% / 32% / 75% — after the repository-wide report: `merged` 75% against",
    "#   76.8%. The root project is the merging module, so this row counts what every",
    "#   suite actually exercised, including the integration module's. Without it the",
    "#   integration suites were invisible to the gate: a module's own report holds",
    "#   only that module's classes.",
    "# - 86% — the `app` floor after the catalog's answers started naming the instance",
    "#   and the moment of their data: 87.2% measured (2026-10-21). The point of",
    "#   headroom is kept: a floor of 87% would have had none.",
    "# - 86% / 89% / 90% — after the catalog's queries were split by decision and the",
    "#   branch was rebased onto the merge of #129: `app` 86% against 87.7%,",
    "#   `merged` 89% against 90.1%, `wbparser` 90% against 91.3%. The split did not",
    "#   lower the coverage: the parts that hold the text of the SQL are tested",
    "#   without a database, and the parts that read it are tested against a real",
    "#   PostgreSQL.",
    "# - 89% / 91% / 92% — after the runs got a reader, the dead code was removed and",
    "#   the catalog's error answers were covered: `app` 89% against 90.1%,",
    "#   `merged` 91% against 92.4%, `wbparser` 92% against 93.5% (2026-10-22). The",
    "#   history lives here, in the script that writes the file: a file rewritten by",
    "#   the script and edited by hand is a history that disappears one raise later.",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-drop",
        metavar="REASON",
        default=None,
        help="lower the floor to the measured coverage, recording this reason",
    )
    args = parser.parse_args()

    try:
        floors = parse_floor_file()
    except (FileNotFoundError, ValueError) as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 2

    measured: dict[str, float] = {}
    for module in floors:
        report = ROOT / _GATE.module_report(module)
        if report is None or not Path(report).is_file():
            print(f"❌ {module}: {report} does not exist — run the coverage task first", file=sys.stderr)
            return 2
        try:
            # The same measurement the gate uses: every row is measured on the merged
            # report over the packages that row is about. A ratchet that measured
            # something else would move the floor away from what the gate checks.
            measured[module] = module_coverage(module)
        except ValueError as exc:
            print(f"❌ {exc}", file=sys.stderr)
            return 2

    drops: list[str] = []
    for module, actual in sorted(measured.items()):
        floor = floors[module]
        # The floor keeps a percentage point of headroom below the
        # measurement. Rounding the measurement down would put the floor *on*
        # it whenever the measurement is a whole number, and then any
        # unrelated edit would trip the gate.
        target = max(int(math.floor(actual - HEADROOM_POINTS)), 0)
        if target < floor:
            if args.allow_drop is None:
                print(
                    f"❌ {module}: measured {actual:.1f}%, floor is {floor}%. "
                    "Refusing to lower it without a reason — pass --allow-drop '<reason>' "
                    "if the code that was covered is gone.",
                    file=sys.stderr,
                )
                drops.append(module)
                continue
            print(f"  ·  {module}: lowering the floor {floor}% → {target}% ({args.allow_drop})")
        else:
            print(f"  ·  {module}: raising the floor {floor}% → {target}% (measured {actual:.1f}%)")
        floors[module] = target

    if drops:
        return 1

    lines = list(HEADER)
    if args.allow_drop is not None:
        lines.append(f"# Lowered on purpose: {args.allow_drop}")
    lines.append("")
    for module in sorted(floors):
        lines.append(f"{module} {floors[module]}")

    FLOOR_FILE.parent.mkdir(parents=True, exist_ok=True)
    FLOOR_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"✅ {FLOOR_FILE.relative_to(ROOT)} updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
