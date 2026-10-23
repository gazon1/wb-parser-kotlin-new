#!/usr/bin/env python3
"""Check that every module's line coverage is at or above its recorded floor.

The floor lives in `config/gates/coverage-floor.txt`, one `<module> <percent>`
row per module. It used to live in the build script instead, and the two
disagreed: the file said 44 while `minBound(45)` in `wbparser/build.gradle.kts`
said 45. A floor that exists in two places is a floor nobody can trust — the
build enforced one number and the repository recorded another.

So the file is the floor, this script is the gate, and the build script only
generates the report.

## What a row is measured on

Every row is measured on the **merged** report — the union of every module's
classes and of the coverage every suite produced — over the packages that row is
about. A module's own report holds only that module's classes and only the
coverage that module's own suites produced, and the suites that exercise the
application hardest live in the integration module. Reading the app's own
report therefore reported its catalog API, its routes and its security chain as
0% covered while the same classes were at 57-100% in the merged report: thirty-five
classes reported as untested, and not one of them untested.

The floor must measure the code. So the row says which packages it is about, and
the measurement is taken over those packages in the whole repository's report.

## Refusing to skip

If a module's report is missing, this fails. "No report" is not "no coverage" —
it is "the coverage task did not run", which is the same shape of problem as a
test task that was UP-TO-DATE and never executed.

## What a stale report looks like

A measurement of an old build is not a measurement of the code. Two ways to be
wrong, and both of them are silent: the gate reads a report, compares a
percentage with a floor, and reports a number that describes a repository which
no longer exists.

**The report predates the run it describes.** The report is the coverage of a
test run, so it cannot be older than that run by much. The gap between the
report and the newest JUnit XML is what `--max-age` bounds: a report that is
hours behind the tests that just ran does not contain them, and reading it
measures a repository that has already moved on.

The bound is a gap, not an age. A report that is a week old is fine when the
tests it describes are also a week old — the measurement is still the truth
about that code. What makes a report stale is not the passage of time but the
code moving under it.

**The union is behind one of its parts.** The merged report is the union of the
modules' reports: it holds every module's classes and the coverage every suite
produced. A union of this run therefore covers at least as many lines of a
module's packages as that module's own report does — the module's own suites
are one of the contributors to the union.

A union that shows *less* of a part than the part itself is not the union of
this run: it was built before that part was regenerated, and it is missing
coverage that exists. This is not a theoretical worry — it is what a stale
merged report looks like, and it reads as "the browser module is at 90.4%"
when the browser module is at 92.1% and the merged report simply never saw it.

So the gate compares the union with its parts, and a union that is behind is a
failure with the same meaning as a missing report: there is no measurement.

Usage
-----
    python3 scripts/check-coverage.py [--floor-file FILE] [--report MODULE=PATH]
                                     [--max-age SECONDS]

    --floor-file FILE   read the floors from this file instead of the committed one
    --report MODULE=PATH  read this module's report from this path instead of the
                        standard build output; repeatable
    --max-age SECONDS  how much older than the last test run a report may be
                        (default 1h). The gap, not the age: a report that is
                        behind the tests that just ran does not describe them.

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
    "browser": "browser/build/reports/kover/report.xml",
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

#: module → where Gradle writes that module's JUnit XML.
#:
#: The same directories `check-test-runs.py` reads. They are the record of the run
#: the coverage report describes, and the report must not be behind them.
RESULT_DIRS = {
    "app": "app/build/test-results/test",
    "browser": "browser/build/test-results/test",
    "wbparser": "wbparser/build/test-results/test",
    "tests": "tests/build/test-results/test",
}

#: The report every module's row is measured on.
#:
#: A module's own report holds only that module's classes, and only the coverage that
#: module's *own* suites produced. The integration module's suites live elsewhere, and
#: they are the ones that exercise the application's classes hardest — so the app's own
#: report showed its catalog API, its routes and its security chain as 0% covered while
#: the merged report showed the same classes at 57-100%. Thirty-five classes were
#: reported as untested and were not.
#:
#: The floor must measure the code, not the module that happens to own it. So every row
#: is measured on the merged report, over the packages that row is about.
MERGED_REPORT = REPORT_FILES["merged"]

#: module → the packages its row covers in the merged report. `None` means all of them.
MODULE_PACKAGES: dict[str, tuple[str, ...] | None] = {
    "app": ("ru/wbparser/app",),
    "browser": ("ru/wbparser/browser",),
    "wbparser": ("ru/wbparser",),
    "merged": None,
}

#: module → packages its row excludes: a package under another module's prefix.
#:
#: `ru/wbparser/app` is under `ru/wbparser`, so the crawler's row has to say that the
#: application's packages are not its own. The same holds for the browser stack: it
#: lives in its own module, and its packages are under `ru/wbparser` only because the
#: package root is shared.
MODULE_EXCLUDED: dict[str, tuple[str, ...]] = {
    "wbparser": ("ru/wbparser/app", "ru/wbparser/browser"),
}

#: The parts the merged report is the union of: module → its own report.
#:
#: The union is the union of these. A part that was regenerated after the union
#: knows more than the union does, and the difference is coverage the union is
#: missing — the shape a stale merged report takes.
UNION_PARTS = {
    "app": REPORT_FILES["app"],
    "browser": REPORT_FILES["browser"],
    "wbparser": REPORT_FILES["wbparser"],
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


def line_coverage(
    report: Path,
    packages: tuple[str, ...] | None = None,
    excluded: tuple[str, ...] = (),
) -> float:
    """The line coverage of a kover XML report, as a percentage.

    Without [packages] the report's own top-level LINE counter is used: it is the total
    across every class in the report, and the per-class counters are its children and are
    not summed, because they overlap.

    With [packages] the coverage is measured over the classes of those packages only,
    summing their per-class LINE counters. That is how a module's row is measured on the
    merged report: the row is about one module's packages, and the report is the whole
    repository's.
    """
    try:
        tree = ET.parse(report)
    except ET.ParseError as exc:
        raise ValueError(f"{rel(report)}: not valid XML ({exc})") from exc

    if packages is not None:
        return _package_coverage(tree, report, packages, excluded)

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


def _package_coverage(
    tree: ET.ElementTree,
    report: Path,
    packages: tuple[str, ...],
    excluded: tuple[str, ...],
) -> float:
    """The line coverage of the classes in [packages], excluding [excluded]."""
    missed, covered = _package_counts(tree, report, packages, excluded)
    total = missed + covered
    if total == 0:
        raise ValueError(f"{rel(report)}: the report contains no lines for {', '.join(packages)}")
    return covered / total * 100


def _package_counts(
    tree: ET.ElementTree,
    report: Path,
    packages: tuple[str, ...],
    excluded: tuple[str, ...],
) -> tuple[int, int]:
    """The (missed, covered) line counters of the classes in [packages].

    The counts, not the percentage: two reports over the same packages hold the
    same classes, so their covered counts are directly comparable, while their
    percentages are comparable only through a division.
    """
    missed = 0
    covered = 0
    for package in tree.getroot().iter("package"):
        name = package.get("name", "")
        if not any(name == prefix or name.startswith(prefix + "/") for prefix in packages):
            continue
        if any(name == prefix or name.startswith(prefix + "/") for prefix in excluded):
            continue
        for klass in package.findall("class"):
            for counter in klass.findall("counter"):
                if counter.get("type") != "LINE":
                    continue
                missed += int(counter.get("missed", "0"))
                covered += int(counter.get("covered", "0"))
    return missed, covered


def newest_test_result_mtime() -> float:
    """The newest JUnit XML the last test run wrote, as a timestamp.

    Zero when there is none: a repository that has never run its tests has
    nothing for the report to be behind.
    """
    newest = 0.0
    for source_set in RESULT_DIRS.values():
        directory = ROOT / source_set
        if not directory.is_dir():
            continue
        for xml in directory.glob("*.xml"):
            newest = max(newest, xml.stat().st_mtime)
    return newest


def staleness_error(report: Path, newest_test_mtime: float, max_age: int) -> str | None:
    """The error when the report does not describe the last test run, if it does not.

    The report is the coverage of a test run, so it cannot be behind that run by
    much. The bound is a gap, not an age: a report that is a week old is honest
    when the tests it describes are also a week old. What makes a report stale is
    the code moving under it, not the passage of time.
    """
    gap = newest_test_mtime - report.stat().st_mtime
    if gap <= max_age:
        return None
    return (
        f"{rel(report)} is {int(gap // 60)}m older than the last test run "
        f"(limit {max_age // 60}m) — it does not describe that run"
    )


def union_shortfall(
    merged: Path,
    part: Path,
    packages: tuple[str, ...],
    excluded: tuple[str, ...] = (),
) -> int | None:
    """How many lines the union [merged] covers less of [part]'s packages than [part] does.

    `None` when the union is not behind: the part is older than the union (so it
    says nothing about this run), or the union covers at least as much.

    A part older than the union is skipped deliberately. A part that was built
    from an older run may well cover more lines than the union of the current
    one — coverage goes down when code is added without tests — and comparing
    against it would report a loss that never happened.
    """
    if not part.is_file():
        return None
    if part.stat().st_mtime < merged.stat().st_mtime:
        return None
    try:
        merged_tree = ET.parse(merged)
        part_tree = ET.parse(part)
    except ET.ParseError as exc:
        raise ValueError(f"{rel(part)}: not valid XML ({exc})") from exc

    _, merged_covered = _package_counts(merged_tree, merged, packages, excluded)
    part_missed, part_covered = _package_counts(part_tree, part, packages, excluded)
    if part_missed + part_covered == 0:
        return None
    if merged_covered >= part_covered:
        return None
    return part_covered - merged_covered


def module_report(module: str, overrides: dict[str, str] | None = None) -> str | None:
    """The report path a module's row is measured on, relative to the repository root.

    Every known module is measured on the merged report: the row is about a module's
    packages, and a module's own report would not see the coverage the other modules'
    suites gave them.
    """
    if overrides and module in overrides:
        return overrides[module]
    if module in MODULE_PACKAGES:
        return MERGED_REPORT
    return REPORT_FILES.get(module)


def module_coverage(
    module: str,
    overrides: dict[str, str] | None = None,
) -> float:
    """The measured line coverage of one module's row."""
    relative = module_report(module, overrides)
    if relative is None:
        raise ValueError(f"{module}: no kover report is known for this module")
    report = Path(relative) if Path(relative).is_absolute() else ROOT / relative
    return line_coverage(
        report,
        packages=MODULE_PACKAGES.get(module),
        excluded=MODULE_EXCLUDED.get(module, ()),
    )


def union_errors(measured_reports: set[Path]) -> list[str]:
    """The errors that come from the union being behind one of its parts.

    Only meaningful when a row was measured on the merged report: the union is
    the merged report, and the parts are the modules' own reports. A gate run
    entirely on fixtures has no union to be behind.
    """
    merged = ROOT / MERGED_REPORT
    if merged not in measured_reports:
        return []

    errors: list[str] = []
    for module, relative in sorted(UNION_PARTS.items()):
        packages = MODULE_PACKAGES.get(module)
        if packages is None:
            continue
        try:
            shortfall = union_shortfall(
                merged,
                ROOT / relative,
                packages,
                MODULE_EXCLUDED.get(module, ()),
            )
        except ValueError as exc:
            errors.append(f"{module}: {exc}")
            continue
        if shortfall is not None:
            errors.append(
                f"{module}: the merged report covers {shortfall} line(s) less of "
                f"{', '.join(packages)} than {rel(ROOT / relative)} does — the union "
                "is behind its own part, so it does not describe this run"
            )
    return errors


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
    parser.add_argument(
        "--max-age",
        type=int,
        default=3600,
        help="how much older than the last test run a report may be, in seconds "
        "(default 1h). The gap, not the age: a report that is behind the tests that "
        "just ran does not describe them, while a report that is as old as its tests "
        "is still an honest measurement.",
    )
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    for override in args.report or []:
        module, separator, path = override.partition("=")
        if not separator or not module or not path:
            print(f"❌ --report expects MODULE=PATH, got {override!r}", file=sys.stderr)
            return 2
        overrides[module] = path

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
    measured_reports: set[Path] = set()
    newest_test_mtime = newest_test_result_mtime()

    for module, floor in sorted(floors.items()):
        relative = module_report(module, overrides)
        if relative is None:
            errors.append(f"{module}: no kover report is known for this module")
            continue

        report = Path(relative)
        if not report.is_absolute():
            report = ROOT / relative
        if not report.is_file():
            errors.append(
                f"{module}: {rel(report)} does not exist — the coverage task did not run"
            )
            continue

        measured_reports.add(report)

        stale = staleness_error(report, newest_test_mtime, args.max_age)
        if stale is not None:
            errors.append(f"{module}: {stale}")
            continue

        try:
            actual = module_coverage(module, overrides)
        except ValueError as exc:
            errors.append(f"{module}: {exc}")
            continue

        if actual + 1e-9 < floor:
            problems.append(f"{module}: {actual:.1f}% line coverage, floor is {floor}%")
        else:
            print(f"  ·  {module}: {actual:.1f}% line coverage (floor {floor}%)")

    errors.extend(union_errors(measured_reports))

    if errors:
        # "The report is missing" and "the coverage is too low" are different answers,
        # and they get different exit codes: the first means the gate could not
        # evaluate the module, the second means the module did not meet its floor.
        print("", file=sys.stderr)
        for error in errors:
            print(f"❌ {error}", file=sys.stderr)
        print(
            "\nNo report is not no coverage — it is no measurement. The coverage task\n"
            "must run before this gate can say anything about the module. A report\n"
            "that is too old, or a union that is behind its own part, is the same\n"
            "answer: there is nothing here to measure.",
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
