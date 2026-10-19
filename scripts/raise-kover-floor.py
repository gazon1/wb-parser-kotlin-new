#!/usr/bin/env python3
"""Raise the coverage floor to what the build actually produced.

The floor is a ratchet: it may go up whenever the coverage does, and it may go
down only when the code that was covered is gone — which is a deliberate act,
not an accident of a refactor.

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
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


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
    "# - 50% / 25% — the floors as measured after the advisory-lock and crawl_jobs work;",
    "#   `app` entered the record at 25%, the share of the application module's own",
    "#   lines that its unit tests reach.",
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
        report = ROOT / REPORT_FILES[module]
        if not report.is_file():
            print(f"❌ {module}: {report} does not exist — run the coverage task first", file=sys.stderr)
            return 2
        try:
            measured[module] = line_coverage(report)
        except ValueError as exc:
            print(f"❌ {exc}", file=sys.stderr)
            return 2

    drops: list[str] = []
    for module, actual in sorted(measured.items()):
        floor = floors[module]
        # The floor is an integer percentage; the measured value is a float.
        # Rounding down would let the floor sit above the measurement, so the
        # floor is the measurement truncated to a whole percent.
        target = int(actual)
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
