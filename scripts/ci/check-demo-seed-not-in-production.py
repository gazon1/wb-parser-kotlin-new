#!/usr/bin/env python3
"""Check that nothing that shapes a deployed instance turns the demo seed on.

The demo seed fills `scraped_items` with synthetic data. It exists so that the
storefront can be run without a crawl, and it is guarded by five barriers at
run time (see `DemoSeedRunner`). The barriers are the last line of defence; this
check is the first one, and it is the one that can be read.

The claim the crawler makes about the demo seed is narrow: filling a working
database with test data is extremely difficult, and it takes all five barriers at
once. This check is what keeps the *configuration* from making that easy — if a
deployment template turned the demo profile on, one barrier would be gone before
the other four were even consulted.

What is checked is the set of files that shape a deployed instance:

  * `app/src/main/resources/application.yml` — the shipped configuration. Every
    `catalog.demo-seed.enabled` in it has to be off, or a placeholder whose
    default is off.
  * `docker-compose.yml`, `.env.example`, `Dockerfile*` — what an operator runs
    or copies.
  * `scripts/deploy/**` and `.just/deploy/**` — what a deployment does.

The templates of the reverse proxy live in a different repository, and this check
does not reach into it: a gate whose behaviour depends on what happens to be
checked out next door is a gate that behaves differently on different machines.
The proxy's templates are checked where they live.

Usage:
    python3 scripts/ci/check-demo-seed-not-in-production.py
    python3 scripts/ci/check-demo-seed-not-in-production.py --self-test
    python3 scripts/ci/check-demo-seed-not-in-production.py --root DIR
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

# The files that shape a deployed instance.
DEPLOYED_FILES = [
    "app/src/main/resources/application.yml",
    "docker-compose.yml",
    ".env.example",
    "Dockerfile.app",
    "Dockerfile",
]

# The directories whose contents shape a deployment.
DEPLOYED_DIRECTORIES = [
    "scripts/deploy",
    ".just/deploy",
]

# A demo seed that is switched on, in any spelling.
ENABLED_PATTERNS = [
    re.compile(r"CATALOG_DEMO_SEED_ENABLED\s*[:=]\s*true", re.IGNORECASE),
    re.compile(r"catalog[.\-]demo[.\-]seed[.\-]enabled\s*:\s*true", re.IGNORECASE),
    re.compile(r"demo[_\-]seed[_\-]enabled\s*[:=]\s*true", re.IGNORECASE),
]

# The demo Spring profile being switched on.
DEMO_PROFILE_PATTERNS = [
    re.compile(r"SPRING_PROFILES_ACTIVE\s*[:=]\s*[^\n]*\bdemo\b"),
    re.compile(r"spring[.\-]profiles[.\-]active\s*:\s*[^\n]*\bdemo\b", re.IGNORECASE),
]


def check(root: Path) -> list[str]:
    """The findings of the check against `root`. An empty list means it passed."""
    findings: list[str] = []

    for relative in DEPLOYED_FILES:
        path = root / relative
        if not path.is_file():
            continue
        findings += scan_file(path)

    for relative in DEPLOYED_DIRECTORIES:
        directory = root / relative
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                findings += scan_file(path)

    findings += check_shipped_configuration(root / "app/src/main/resources/application.yml")

    return findings


def scan_file(path: Path) -> list[str]:
    """The findings in one file."""
    findings: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return findings

    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("#") or stripped.startswith("//"):
            continue
        for pattern in ENABLED_PATTERNS:
            if pattern.search(line):
                findings.append(f"{path}:{number}: the demo seed is switched on: {stripped}")
        for pattern in DEMO_PROFILE_PATTERNS:
            if pattern.search(line):
                findings.append(f"{path}:{number}: the demo profile is activated: {stripped}")

    return findings


def check_shipped_configuration(path: Path) -> list[str]:
    """The findings in the shipped `application.yml`.

    Every `enabled:` under a `demo-seed:` block has to be off. The value may be
    written as `false` or as a placeholder whose default is `false` — the
    placeholder is what lets an operator switch it on deliberately, in the demo
    environment, without the shipped configuration doing it.
    """
    if not path.is_file():
        return []

    findings: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()

    inside_demo_seed = False
    demo_seed_indent = 0
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())

        if stripped.startswith("demo-seed:"):
            inside_demo_seed = True
            demo_seed_indent = indent
            continue

        if inside_demo_seed:
            if indent <= demo_seed_indent and stripped.endswith(":"):
                inside_demo_seed = False
                continue
            if stripped.startswith("enabled:"):
                value = stripped.split(":", 1)[1].strip()
                if not is_off(value):
                    findings.append(
                        f"{path}:{number}: the shipped configuration switches the demo seed on: {stripped}",
                    )

    return findings


def is_off(value: str) -> bool:
    """Whether a value means "off"."""
    if value == "false":
        return True
    # A placeholder whose default is off: `${SOMETHING:false}`.
    match = re.fullmatch(r"\$\{[^:}]+:([^}]*)\}", value)
    return match is not None and match.group(1).strip() == "false"


def self_test() -> int:
    """Prove the check can fail.

    A check whose passing is indistinguishable from a check that cannot run is
    the defect class this repository keeps running into, so the control builds a
    broken fixture in a temporary directory and requires the check to reject it.
    """
    with tempfile.TemporaryDirectory() as temporary:
        broken_root = Path(temporary) / "broken"
        (broken_root / "app/src/main/resources").mkdir(parents=True)
        (broken_root / "app/src/main/resources/application.yml").write_text(
            "catalog:\n  demo-seed:\n    enabled: true\n",
            encoding="utf-8",
        )

        findings = check(broken_root)
        if not findings:
            print(
                "self-test FAILED: a shipped configuration with the demo seed on was accepted",
                file=sys.stderr,
            )
            return 1

    findings = check(ROOT)
    if findings:
        print("self-test FAILED: the real tree is not clean:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1

    print("✅ self-test: a deployment that enables the demo seed is rejected")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="the tree to check")
    parser.add_argument("--self-test", action="store_true", help="prove the check can fail")
    arguments = parser.parse_args()

    if arguments.self_test:
        return self_test()

    findings = check(arguments.root)
    if findings:
        print("❌ a deployed instance would run with the demo seed enabled:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1

    print("✅ nothing that shapes a deployed instance enables the demo seed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
