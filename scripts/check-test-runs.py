#!/usr/bin/env python3
"""check-test-runs.py — fail when a test run executed fewer tests than the floor.

## The defect this catches

Gradle reports `BUILD SUCCESSFUL` whether a suite executed 227 tests or none.
An `UP-TO-DATE` test task does not rewrite its results directory at all, so a
second consecutive `./check.sh` reuses yesterday's XML and reports the same
count — correct, but only because the first run happened. Remove a test source
set from a task, mis-scope an include pattern, or let a JVM crash after the
first class, and every other gate still passes.

This gate reads the JUnit XML the run actually produced and compares it with a
committed floor. It is the only check here that asks "did the tests run", and
it must run **after** the test steps — it reads files they create.

## Why a floor and not an equality

An exact count would fail every legitimate test addition. A floor fails only
when a run executes *fewer* than has ever been recorded, which is the direction
that hides breakage. A run with more tests is always an improvement.

## The blind spot a floor has, and why it is acceptable here

A floor compares a run against a number recorded earlier, so a brand-new test
class that never executes is invisible: it is not in the floor, and its absence
lowers nothing. Closing that would need per-class bookkeeping across every
module; this project has 227 tests in three modules and no test-selection tags,
so the remaining exposure is "someone adds a test and the task stops selecting
it", which fails the build for a different reason (compile error) in every
current case.

## Refusing to skip

If a module's XML directory is missing, this fails. It does not treat "no
results" as "no tests", because that is how a green build hides a suite that
never started.

Usage
-----
    python3 scripts/check-test-runs.py [--update] [--max-age SECONDS]

Exit codes
----------
    0 — every recorded source set met or beat its floor
    1 — a source set ran fewer tests than its floor
    2 — results were missing, unreadable, or the floor file is absent
"""

from __future__ import annotations

import argparse
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FLOOR_FILE = ROOT / "config" / "gates" / "test-runs-floor.txt"

#: module → where Gradle writes that module's JUnit XML.
RESULT_DIRS = {
    "domain": "domain/build/test-results/test",
    "infrastructure": "infrastructure/build/test-results/test",
    "tests": "tests/build/test-results/test",
}

_TESTCASE_RE = re.compile(r"<(?:testcase|testCase)\b")


def rel(path: Path) -> str:
    """Path relative to the repo when possible, absolute otherwise.

    `relative_to` raises when the path is outside the repository — which is
    exactly the case for the unit tests' temporary fixtures. An error message
    that dies while formatting itself replaces a useful diagnostic with
    "ValueError", which is the worst possible moment to lose information.
    """
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def parse_floor_file(path: Path | None = None) -> dict[str, int]:
    """Read `<module> <count>` rows.

    `path` is a parameter so the parser can be tested against a fixture; the
    gate itself always uses the committed file. A parser that can only be
    exercised by editing the real configuration is a parser nobody exercises.
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
            raise ValueError(f"{target}:{lineno}: expected `<module> <count>`, got {stripped!r}")
        try:
            floors[parts[0]] = int(parts[1])
        except ValueError as exc:
            raise ValueError(f"{target}:{lineno}: count is not an integer: {parts[1]!r}") from exc
    return floors


def count_in_dir(directory: Path) -> tuple[int, int]:
    """Return (test count, file count) across every JUnit XML in `directory`.

    The file count matters separately: a module whose suite is written as one
    giant XML and one written as one file per class produce the same test count
    and very different failure granularities, so a floor that dropped to zero
    files is worth reporting even at the same test count.
    """
    if not directory.is_dir():
        raise FileNotFoundError(f"{rel(directory)} does not exist")

    total = 0
    files = 0
    for xml in sorted(directory.glob("*.xml")):
        try:
            tree = ET.parse(xml)
        except ET.ParseError as exc:
            raise ValueError(f"{rel(xml)}: not valid JUnit XML ({exc})") from exc
        # Count leaf <testcase> elements wherever they appear. <testcase> is
        # only ever a leaf, so a straight element count is exact; this also
        # covers a suite nested inside <testsuites><testsuite>.
        total += sum(1 for _ in tree.getroot().iter("testcase"))
        files += 1

    if files == 0:
        raise ValueError(f"{rel(directory)} contains no JUnit XML")
    return total, files


def results_age_seconds(directory: Path) -> float | None:
    newest = 0.0
    for xml in directory.glob("*.xml"):
        newest = max(newest, xml.stat().st_mtime)
    if newest == 0.0:
        return None
    return time.time() - newest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--update",
        action="store_true",
        help="rewrite the floor file from the current results instead of comparing",
    )
    parser.add_argument(
        "--max-age",
        type=int,
        default=21600,
        help="maximum age in seconds of the XML (default 6h). A UP-TO-DATE task does "
        "not rewrite its results, so a window is used rather than a run stamp.",
    )
    args = parser.parse_args()

    try:
        floors = parse_floor_file()
    except (FileNotFoundError, ValueError) as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 2

    measured: dict[str, int] = {}
    problems: list[str] = []

    for module, relative in RESULT_DIRS.items():
        directory = ROOT / relative
        floor = floors.get(module)

        if floor is None:
            problems.append(f"{module}: no floor recorded in {FLOOR_FILE.relative_to(ROOT)}")
            continue

        # A floor of 0 is a real statement: this module currently has no tests.
        # Gradle writes no results directory at all in that case, and treating
        # the absence as an error would make the gate impossible to satisfy
        # while the honest record is "this module is untested". So the absence is
        # accepted *only* against a floor of 0, and it is reported as the gap it
        # is rather than passed over quietly.
        if not directory.is_dir():
            if floor == 0:
                print(f"  ⚠️  {module}: no tests (floor 0) — see ADR 2026-09-09-test-module-boundary")
                measured[module] = 0
                continue
            print(
                f"❌ {module}: {directory.relative_to(ROOT)} does not exist but the floor is {floor}",
                file=sys.stderr,
            )
            problems.append(f"{module}: no results, floor is {floor}")
            continue

        try:
            count, files = count_in_dir(directory)
        except ValueError as exc:
            print(f"❌ {module}: {exc}", file=sys.stderr)
            problems.append(f"{module}: no readable results")
            continue

        age = results_age_seconds(directory)
        if age is not None and age > args.max_age:
            print(
                f"❌ {module}: results are {int(age // 3600)}h old (limit {args.max_age // 3600}h). "
                "A stale directory means the test task did not run — re-run the tests.",
                file=sys.stderr,
            )
            problems.append(f"{module}: results are stale")

        measured[module] = count
        if count < floor:
            problems.append(
                f"{module}: ran {count} tests, floor is {floor} ({files} result files)"
            )
        else:
            print(f"  ·  {module}: {count} tests (floor {floor}, {files} files)")

    if args.update:
        lines = [
            "# Executed-test floors, one per module: `<module> <count>`.",
            "# Managed by scripts/check-test-runs.py --update. Raise it in the same commit",
            "# that adds tests; lowering it is allowed only when tests are deleted.",
            "",
        ]
        for module in sorted(measured):
            lines.append(f"{module} {measured[module]}")
        FLOOR_FILE.parent.mkdir(parents=True, exist_ok=True)
        FLOOR_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"✅ floor file updated from the current run")
        return 0

    if problems:
        print("", file=sys.stderr)
        for problem in problems:
            print(f"❌ {problem}", file=sys.stderr)
        print(
            "\nA green test task is not evidence that the tests ran. This check reads the\n"
            "JUnit XML the run produced; if the numbers are lower than the floor, the\n"
            "suite did not execute in full.",
            file=sys.stderr,
        )
        return 1

    total = sum(measured.values())
    print(f"✅ {len(measured)} source set(s), {total} tests executed, all floors met")
    return 0


if __name__ == "__main__":
    sys.exit(main())