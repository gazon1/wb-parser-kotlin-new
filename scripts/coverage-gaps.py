#!/usr/bin/env python3
"""List the classes that are not covered, largest gap first.

## Why this exists

The coverage gate answers one question: is the module above its floor? When the
answer is no, the next question is "which lines", and the report that could
answer it is an XML file with one counter per class.

Answering that by hand means writing a script every time: parse the report,
filter by package, sum the per-class counters, sort. That script was written
nine times in one session before it lived here — nine slightly different
scripts, nine different answers, and none of them in the repository for the
next person to reuse.

So the question has one answer, and the answer is a command.

## What it reads

The **merged** report — the union of every module's classes and of the coverage
every suite produced — over the packages each row is about. The same report and
the same package filter the gate measures, so what this prints and what the
gate enforces are the same thing. A module's own report would show its own
classes and only the coverage its own suites produced, which reads as a much
bigger gap than there is.

Usage
-----
    python3 scripts/coverage-gaps.py [--module MODULE] [--limit N] [--min-missed N]

    --module MODULE   only this module's row (app, browser, wbparser, merged)
    --limit N         how many classes to print per row (default 20)
    --min-missed N    only classes with at least N uncovered lines (default 1)

Exit codes
----------
    0 — the report was read and the gaps were listed
    1 — the report was missing or unreadable
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_gate():
    """Import the gate script, whose file name has a hyphen in it.

    The two scripts share the report parsing and the package filters, and two
    copies of a filter is exactly the disagreement this change set out to end:
    the tool has to report on what the gate measures.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location("check_coverage", ROOT / "scripts" / "check-coverage.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("scripts/check-coverage.py could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_GATE = _load_gate()


def class_gaps(
    report: Path,
    packages: tuple[str, ...] | None,
    excluded: tuple[str, ...] = (),
) -> tuple[list[tuple[int, int, str]], int, int]:
    """The classes of [packages] with uncovered lines, and the totals of them all.

    Returns (gaps, missed, covered), where the gaps are (missed, covered, name)
    sorted by the size of the gap, largest first — a class with forty uncovered
    lines and a class with one are not the same piece of work, and the order is
    what makes the list a plan rather than a dump.

    The totals cover *every* class, including the fully covered ones: a total
    built from the gaps alone would leave the covered lines out of the
    denominator and report a module as less covered than the gate measures it.
    """
    try:
        tree = ET.parse(report)
    except ET.ParseError as exc:
        raise ValueError(f"{report}: not valid XML ({exc})") from exc

    gaps: list[tuple[int, int, str]] = []
    missed_total = 0
    covered_total = 0
    for package in tree.getroot().iter("package"):
        name = package.get("name", "")
        if packages is not None and not any(
            name == prefix or name.startswith(prefix + "/") for prefix in packages
        ):
            continue
        if any(name == prefix or name.startswith(prefix + "/") for prefix in excluded):
            continue
        for klass in package.findall("class"):
            for counter in klass.findall("counter"):
                if counter.get("type") != "LINE":
                    continue
                missed = int(counter.get("missed", "0"))
                covered = int(counter.get("covered", "0"))
                missed_total += missed
                covered_total += covered
                if missed:
                    gaps.append((missed, covered, klass.get("name", "?")))
    gaps.sort(reverse=True)
    return gaps, missed_total, covered_total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--module",
        default=None,
        help="only this module's row (app, browser, wbparser, merged)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=20,
        help="how many classes to print per row (default 20)",
    )
    parser.add_argument(
        "--min-missed",
        type=int,
        default=1,
        help="only classes with at least this many uncovered lines (default 1)",
    )
    args = parser.parse_args()

    report = ROOT / _GATE.MERGED_REPORT
    if not report.is_file():
        print(
            f"❌ {report.relative_to(ROOT)} does not exist — run the coverage task first",
            file=sys.stderr,
        )
        return 1

    rows = sorted(_GATE.MODULE_PACKAGES)
    if args.module is not None:
        if args.module not in _GATE.MODULE_PACKAGES:
            print(
                f"❌ unknown module {args.module!r}; known: {', '.join(rows)}",
                file=sys.stderr,
            )
            return 1
        rows = [args.module]

    for module in rows:
        packages = _GATE.MODULE_PACKAGES[module]
        excluded = _GATE.MODULE_EXCLUDED.get(module, ())
        try:
            gaps, missed, covered = class_gaps(report, packages, excluded)
        except ValueError as exc:
            print(f"❌ {exc}", file=sys.stderr)
            return 1

        total = missed + covered
        percentage = covered / total * 100 if total else 0.0
        print(f"=== {module}: {percentage:.1f}% ({covered}/{total} lines) — {missed} uncovered ===")

        shown = [gap for gap in gaps if gap[0] >= args.min_missed][: args.limit]
        if not shown:
            print("  nothing uncovered")
        for gap_missed, gap_covered, name in shown:
            print(f"  {gap_missed:4d} missed / {gap_missed + gap_covered:4d} total  {name}")
        hidden = len([gap for gap in gaps if gap[0] >= args.min_missed]) - len(shown)
        if hidden > 0:
            print(f"  … and {hidden} more")
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
