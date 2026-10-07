#!/usr/bin/env python3
"""check-baseline-ratchet.py — a detekt baseline may shrink, never grow.

Why this exists
---------------
A detekt baseline is a record of debt somebody accepted. Nothing about the
format stops the next change from appending to it, and nothing about the build
stops that from being invisible: `detekt` reports findings, the baseline removes
them from the report, and the exit code stays zero either way. So "detekt
reports 0 findings" quietly becomes "detekt reports 0 findings *outside the
baseline*", which is a different claim and reads identically in a PR description.

This repository had that moment twice. The root baseline was written with a
space in the element name (`<Current issues />`), which the detekt parser
rejected — and CI reported success because the `lint` job could not resolve
`ktlintCheck`/`detekt` as tasks at all, so it never reached the file. Fixing the
XML without this ratchet would have left the same hole open.

The contract
------------
* Baseline entries are compared against their committed state in git. A change
  that grows a baseline fails, naming the files and the delta.
* Removing entries is always allowed — that is the debt being paid down.
* Growing one is allowed only with `--allow-growth` and an explicit reason, and
  the reason is printed so it lands in the commit log rather than in a review
  comment somebody has to remember to write.
* `git` is required. Without it the check fails loudly rather than skipping. A
  check that silently passes when it cannot run is the exact defect this whole
  change is about.

Usage
-----
    python3 scripts/check-baseline-ratchet.py [--allow-growth] [--reason TEXT]

Exit codes
----------
    0 — no baseline grew, or it shrank
    1 — a baseline grew without an explicit, justified allowance
    2 — the check could not run (no git, unreadable baseline)
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: Where detekt baselines live. A glob, because the layout is per-module: the
#: root holds the shared config, and each subproject gets its own file (a single
#: shared file does not work — parallel subprojects overwrite each other's
#: entries).
BASELINE_GLOBS = ("detekt-baseline.xml", "*/detekt-baseline.xml")

#: A detekt baseline entry. One <ID> element per suppressed finding, so the
#: <ID> element is the ratchet unit — counting lines would break the moment
#: detekt rewraps a long signature.
_ID_RE = re.compile(r"<ID>.*?</ID>", re.DOTALL)


@dataclass
class Baseline:
    path: Path
    count: int
    ids: set[str]


def find_baselines() -> list[Path]:
    found: list[Path] = []
    for pattern in BASELINE_GLOBS:
        found.extend(sorted(ROOT.glob(pattern)))
    # The root pattern and the module pattern can both match a top-level file.
    seen: set[Path] = set()
    unique: list[Path] = []
    for path in found:
        if path not in seen:
            seen.add(path)
            unique.append(path)
    return unique


def parse_baseline(path: Path) -> Baseline:
    """Read a baseline, failing loudly on anything detekt would also reject.

    The XML is validated with a parser rather than a regex on purpose: the
    element-name defect described in the module docstring is invisible to a
    regex and fatal to detekt.
    """
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        raise ValueError(f"{rel(path)}: not valid XML ({exc})") from exc

    root = tree.getroot()
    if root.tag != "SmellBaseline":
        raise ValueError(
            f"{rel(path)}: root element is <{root.tag}>, expected <SmellBaseline>. "
            "detekt will not read this file, so it is suppressing nothing."
        )

    current = root.find("CurrentIssues")
    if current is None:
        # detekt tolerates a missing section and treats the baseline as empty.
        return Baseline(path=path, count=0, ids=set())

    ids = {element.text or "" for element in current.findall("ID")}
    return Baseline(path=path, count=len(ids), ids=ids)


def committed_ids(path: Path) -> set[str]:
    """IDs in the baseline as last committed, or None if git cannot answer."""
    try:
        completed = subprocess.run(
            ["git", "show", f"HEAD:{rel(path)}"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError as exc:  # pragma: no cover - depends on the host
        raise RuntimeError("git is not installed") from exc

    if completed.returncode != 0:
        # A brand-new baseline is allowed once; afterwards it ratchets like the
        # rest. `git show` failing means the file is not in HEAD at all.
        return set()

    return {
        m.group(0)
        for m in _ID_RE.finditer(completed.stdout)
    }


def rel(path: Path) -> str:
    """Path relative to the repo when possible, absolute otherwise.

    `relative_to` raises for paths outside the repository, which is exactly
    what the unit tests' temporary fixtures are. An error message that fails
    while formatting itself turns a real diagnosis into `ValueError`.
    """
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def run_self_test() -> int:
    """Positive control: prove the ratchet detects a baseline that grew.

    A gate that cannot be shown to fail may be enforcing nothing. The control
    appends an entry to a real baseline, requires the check to reject it, and
    restores the file — on every exit path, including an interrupt.
    """
    baselines = [parse_baseline(path) for path in find_baselines()]
    populated = [b for b in baselines if b.count > 0]
    if not populated:
        print("self-test: no baseline with entries to grow", file=sys.stderr)
        return 2

    target = populated[0]
    path = target.path
    original = path.read_text(encoding="utf-8")

    grown = original.replace(
        "</CurrentIssues>",
        "    <ID>SelfTestRule:SelfTest.kt$selfTest()</ID>\n  </CurrentIssues>",
    )
    if grown == original:
        print(
            f"self-test: could not inject an entry into {rel(path)} — the fixture no longer "
            "matches what this control assumes, so the control is testing nothing",
            file=sys.stderr,
        )
        return 2

    try:
        path.write_text(grown, encoding="utf-8")
        before = committed_ids(path)
        after = parse_baseline(path)
        new_norm = {f"<ID>{i}</ID>" for i in after.ids if i}
        before_norm = {
            m.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&") for m in before
        }
        grew = bool(new_norm - before_norm)
    finally:
        path.write_text(original, encoding="utf-8")

    if not grew:
        print(
            "self-test FAILED: an added entry was not detected — the ratchet is not reading "
            "what it writes",
            file=sys.stderr,
        )
        return 1

    # And the reverse: removing entries must never be a failure.
    try:
        path.write_text(
            original.replace(
                re.search(r"\s*<ID>.*?</ID>", original, re.DOTALL).group(0), ""
            ),
            encoding="utf-8",
        )
        after = parse_baseline(path)
        shrunk = after.count < target.count
    finally:
        path.write_text(original, encoding="utf-8")

    if not shrunk:
        print("self-test FAILED: shrinking the baseline was not detected", file=sys.stderr)
        return 1

    print("✅ self-test: growth is rejected and shrinkage is accepted")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the ratchet detects growth and accepts shrinkage, then restore",
    )
    parser.add_argument(
        "--allow-growth",
        action="store_true",
        help="permit a baseline to grow (requires --reason)",
    )
    parser.add_argument(
        "--reason",
        default="",
        help="why the growth is acceptable; printed so it lands in the log",
    )
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    if args.allow_growth and not args.reason.strip():
        print("error: --allow-growth requires --reason", file=sys.stderr)
        return 1

    try:
        baselines = [parse_baseline(path) for path in find_baselines()]
    except ValueError as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 2

    if not baselines:
        print("❌ no detekt-baseline.xml found — the ratchet has nothing to guard", file=sys.stderr)
        return 2

    failed = False
    for baseline in baselines:
        try:
            before = committed_ids(baseline.path)
        except RuntimeError as exc:
            print(f"❌ {exc} — refusing to skip the check", file=sys.stderr)
            return 2

        new_ids = {f"<ID>{i}</ID>" for i in baseline.ids if i}
        before_norm = {
            m.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
            for m in before
        }
        new_norm = {
            m.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
            for m in new_ids
        }

        added = new_norm - before_norm
        removed = before_norm - new_norm

        name = rel(baseline.path)
        if added:
            if args.allow_growth:
                print(f"  ⚠️  {name}: +{len(added)} entry(ies) allowed — {args.reason}")
            else:
                failed = True
                print(f"❌ {name}: baseline grew by {len(added)} entry(ies)", file=sys.stderr)
                for entry in sorted(added)[:5]:
                    summary = entry[4:-5].split(":")[0]
                    print(f"     + {summary}", file=sys.stderr)
                if len(added) > 5:
                    print(f"     … and {len(added) - 5} more", file=sys.stderr)
                print(
                    "     A baseline is accepted debt. Fix the finding, or re-run with\n"
                    "     --allow-growth --reason '…' and put the reason in the commit message.",
                    file=sys.stderr,
                )
        if removed:
            print(f"  ✅ {name}: {len(removed)} entry(ies) retired — debt paid down")

        if not added and not removed:
            print(f"  ·  {name}: unchanged ({baseline.count} entries)")

    if failed:
        return 1

    total = sum(b.count for b in baselines)
    print(f"✅ {len(baselines)} baseline(s), {total} entries, none grown")
    return 0


if __name__ == "__main__":
    sys.exit(main())