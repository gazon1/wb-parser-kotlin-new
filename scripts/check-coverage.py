#!/usr/bin/env python3
"""Check that every module's line coverage is at or above its recorded floor.

The floor lives in `config/gates/coverage-floor.txt`, one `<module> <percent>`
row per module. It used to live in the build script instead, and the two
disagreed: the file said 44 while `minBound(45)` in `wbparser/build.gradle.kts`
said 45. A floor that exists in two places is a floor nobody can trust — the
build enforced one number and the repository recorded another.

So the file is the floor, this script is the gate, and the build script only
generates the report.

## Refusing to skip

If a module's report is missing, this fails. "No report" is not "no coverage" —
it is "the coverage task did not run", which is the same shape of problem as a
test task that was UP-TO-DATE and never executed.

Usage
-----
    python3 scripts/check-coverage.py [--floor-file FILE] [--report MODULE=PATH]

    --floor-file FILE   read the floors from this file instead of the committed one
    --report MODULE=PATH  read this module's report from this path instead of the
                        standard build output; repeatable

Exit codes
----------
    0 — every module met or beat its floor
    1 — a module is below its floor
    2 — a report was missing or unreadable, or the floor file is absent
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FLOOR_FILE = ROOT / "config" / "gates" / "coverage-floor.txt"

#: module → where Gradle writes that module's kover XML report.
REPORT_FILES = {
    "wbparser": "wbparser/build/reports/kover/report.xml",
    "app": "app/build/reports/kover/report.xml",
    # The repository-wide report. The root project is the merging module: it has no
    # sources of its own, and the `kover` dependencies it declares make its report the
    # union of every module's classes and of the coverage every suite produced.
    #
    # Without it, the integration module's suites were invisible: a module's own report
    # contains only that module's classes, so the code the integration suite exercised
    # most was reported as uncovered.
    "merged": "build/reports/kover/report.xml",
}


def rel(path: Path) -> str:
    """Path relative to the repo when possible, absolute otherwise."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def parse_floor_file(path: Path | None = None) -> dict[str, int]:
    """Read `<module> <percent>` rows.

    `path` is a parameter so the parser can be tested against a fixture; the
    gate itself always uses the committed file.
    """
    target = path or FLOOR_FILE
    if not target.exists():
        raise FileNotFoundError(f"{target} does not exist")
    floors: dict[str, int] = {}
    for lineno, line in enumerate(target.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "#" in stripped:
            stripped = stripped.split("#", 1)[0].strip()
        if not stripped:
            continue
        parts = stripped.split()
        if len(parts) != 2:
            raise ValueError(f"{target}:{lineno}: expected `<module> <percent>`, got {stripped!r}")
        try:
            percent = int(parts[1])
        except ValueError as exc:
            raise ValueError(f"{target}:{lineno}: percentage is not an integer: {parts[1]!r}") from exc
        if not 0 <= percent <= 100:
            raise ValueError(f"{target}:{lineno}: percentage is out of range: {percent}")
        floors[parts[0]] = percent
    return floors


def line_coverage(report: Path) -> float:
    """The line coverage of a kover XML report, as a percentage.

    The report's own top-level counter is the total across every class in the
    module; the per-class counters are its children and are not summed, because
    they overlap.
    """
    try:
        tree = ET.parse(report)
    except ET.ParseError as exc:
        raise ValueError(f"{rel(report)}: not valid XML ({exc})") from exc
    for counter in tree.getroot().findall("counter"):
        if counter.get("type") != "LINE":
            continue
        missed = int(counter.get("missed", "0"))
        covered = int(counter.get("covered", "0"))
        total = missed + covered
        if total == 0:
            raise ValueError(f"{rel(report)}: the report contains no lines at all")
        return covered / total * 100
    raise ValueError(f"{rel(report)}: the report has no LINE counter")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--floor-file",
        type=Path,
        default=None,
        help="read the floors from this file instead of the committed one (for tests)",
    )
    parser.add_argument(
        "--report",
        action="append",
        metavar="MODULE=PATH",
        default=None,
        help="read this module's kover report from this path instead of the build output; "
        "repeatable. It exists so the gate's own failure path can be exercised without "
        "a build: a gate that has never been seen to fail is not known to fail.",
    )
    args = parser.parse_args()

    reports = dict(REPORT_FILES)
    for override in args.report or []:
        module, separator, path = override.partition("=")
        if not separator or not module or not path:
            print(f"❌ --report expects MODULE=PATH, got {override!r}", file=sys.stderr)
            return 2
        reports[module] = path

    try:
        floors = parse_floor_file(args.floor_file)
    except (FileNotFoundError, ValueError) as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 2

    if not floors:
        print(f"❌ {rel(FLOOR_FILE)} records no module", file=sys.stderr)
        return 2

    problems: list[str] = []
    errors: list[str] = []

    for module, floor in sorted(floors.items()):
        relative = reports.get(module)
        if relative is None:
            errors.append(f"{module}: no kover report is known for this module")
            continue

        report = ROOT / relative
        if not report.is_file():
            errors.append(
                f"{module}: {rel(report)} does not exist — the coverage task did not run"
            )
            continue

        try:
            actual = line_coverage(report)
        except ValueError as exc:
            errors.append(f"{module}: {exc}")
            continue

        if actual + 1e-9 < floor:
            problems.append(f"{module}: {actual:.1f}% line coverage, floor is {floor}%")
        else:
            print(f"  ·  {module}: {actual:.1f}% line coverage (floor {floor}%)")

    if errors:
        # "The report is missing" and "the coverage is too low" are different answers,
        # and they get different exit codes: the first means the gate could not
        # evaluate the module, the second means the module did not meet its floor.
        print("", file=sys.stderr)
        for error in errors:
            print(f"❌ {error}", file=sys.stderr)
        print(
            "\nNo report is not no coverage — it is no measurement. The coverage task\n"
            "must run before this gate can say anything about the module.",
            file=sys.stderr,
        )
        return 2

    if problems:
        print("", file=sys.stderr)
        for problem in problems:
            print(f"❌ {problem}", file=sys.stderr)
        print(
            "\nCoverage is measured, not asserted. This check reads the report the\n"
            "build produced; when it is below the floor, either add a test or raise\n"
            "the floor deliberately with scripts/raise-kover-floor.py.",
            file=sys.stderr,
        )
        return 1

    print(f"✅ {len(floors)} module(s) at or above their coverage floor")
    return 0


if __name__ == "__main__":
    sys.exit(main())
