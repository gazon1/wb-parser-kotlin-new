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
    "wbparser": ("ru/wbparser",),
    "merged": None,
}

#: module → packages its row excludes: a package under another module's prefix.
#:
#: `ru/wbparser/app` is under `ru/wbparser`, so the crawler's row has to say that the
#: application's packages are not its own.
MODULE_EXCLUDED: dict[str, tuple[str, ...]] = {
    "wbparser": ("ru/wbparser/app",),
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
    total = missed + covered
    if total == 0:
        raise ValueError(f"{rel(report)}: the report contains no lines for {', '.join(packages)}")
    return covered / total * 100


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

        try:
            actual = module_coverage(module, overrides)
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
