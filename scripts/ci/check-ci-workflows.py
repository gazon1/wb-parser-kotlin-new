#!/usr/bin/env python3
"""check-ci-workflows.py — prove that the CI surfaces are wired, not decoration.

Why this exists
---------------
A workflow that runs is a guarantee. A workflow that is *present* is not: the
moment it stops being triggered — a renamed trigger, a deleted file, a job that
skips itself on the wrong condition — the guarantee is gone, and nothing in
the build says so. The build cannot say so: CodeQL, Scorecard, the nightly
run and the lint auto-fix are all outside the Gradle graph.

This repository already had that failure mode once: a gate lived only in CI and
another lived only locally, each green and invisible to the other surface
(scripts/check-gate-wiring.py, Part G). The workflows checked here are the same
kind of surface, so they get the same treatment: a gate that fails when one of
them is unwired.

What it checks
--------------
1. **CodeQL** (.github/workflows/codeql.yml) — initialises the analysis for
   `java-kotlin`, analyses it, and is allowed to write the results
   (`security-events: write`). An analysis that cannot upload its results is
   an analysis nobody reads.
2. **Scorecard** (.github/workflows/scorecard.yml) — runs the action, writes a
   results file, and carries `id-token: write`, without which the action
   refuses to publish (ossf/scorecard-action v2 requirement).
3. **The lint auto-fix** (.github/workflows/lint-autofix.yml) — calls the same
   script the local loop calls (scripts/ci/lint-autofix.sh), and that script
   exists. A workflow that calls a script that is not there fails on the
   runner; a script that nothing calls is dead code.
4. **The nightly run and its issue** (.github/workflows/ci.yml) — has a
   `schedule` trigger with a `cron`, and a job that opens a GitHub issue. A
   scheduled run that fails silently is a scheduled run nobody reads.

Usage
-----
    python3 scripts/ci/check-ci-workflows.py
    python3 scripts/ci/check-ci-workflows.py --self-test

Exit codes
----------
    0 — every surface is wired
    1 — a finding
    2 — the check could not run
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

WORKFLOWS = ROOT / ".github" / "workflows"

#: The workflows this repository promises to run, and what each of them must
#: contain. A missing file is a finding, not a skipped check: "the workflow is
#: not there" and "the workflow is there and does something else" are the same
#: defect from the repository's point of view.
REQUIRED = {
    "codeql.yml": {
        "why": "CodeQL is the static analysis of the Kotlin sources",
        "needles": [
            "github/codeql-action/init",
            "github/codeql-action/analyze",
            "java-kotlin",
            "security-events: write",
        ],
    },
    "scorecard.yml": {
        "why": "Scorecard measures the security posture of the repository",
        "needles": [
            "ossf/scorecard-action",
            "results_file",
            "id-token: write",
        ],
    },
    "lint-autofix.yml": {
        "why": "the linters' own corrections are applied without a human",
        "needles": [
            "scripts/ci/lint-autofix.sh",
        ],
    },
}

#: The shared corrector. Both surfaces call it: the workflow above and the
#: local recipe (`just lint::autofix`).
AUTOFIX_SCRIPT = ROOT / "scripts" / "ci" / "lint-autofix.sh"

#: The nightly run, and the issue that carries its failure.
CI_YML = WORKFLOWS / "ci.yml"


def read(path: Path) -> str:
    """The file's text, or the empty string when it is not there.

    An empty string makes every needle missing, which is the right reading: a
    workflow that is not there cannot contain anything.
    """
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")


def check_workflows() -> list[str]:
    findings: list[str] = []
    for name, spec in REQUIRED.items():
        path = WORKFLOWS / name
        if not path.exists():
            findings.append(
                f"{name} does not exist. {spec['why']} — and a guarantee that "
                "silently stopped running is indistinguishable from one that never ran."
            )
            continue
        text = path.read_text(encoding="utf-8")
        for needle in spec["needles"]:
            if needle not in text:
                findings.append(
                    f"{name} does not mention `{needle}`. {spec['why']}, and this "
                    f"is the line that does it."
                )
    return findings


def check_autofix_script() -> list[str]:
    findings: list[str] = []
    if not AUTOFIX_SCRIPT.exists():
        findings.append(
            "scripts/ci/lint-autofix.sh does not exist, but the auto-fix workflow "
            "calls it. The corrector is the deliverable; without it the workflow "
            "fails on the runner."
        )
    return findings


def check_nightly() -> list[str]:
    """The scheduled run, and the issue that carries its failure.

    A red cron run is unread: it scrolls past, and the next person to look at
    the repository finds a green badge and a stale week. The issue is where the
    failure lands instead.
    """
    findings: list[str] = []
    text = read(CI_YML)
    if not text:
        findings.append(
            ".github/workflows/ci.yml does not exist — the scheduled run and the "
            "issue that carries its failure have nowhere to live."
        )
        return findings

    if not re.search(r"^\s*schedule:\s*$", text, re.M):
        findings.append(
            "ci.yml has no `schedule` trigger. The tests are supposed to run on "
            "their own, and not only when somebody remembers to push."
        )
    if not re.search(r"^\s*-\s*cron:\s*[\"']?\S+", text, re.M):
        findings.append(
            "ci.yml has a `schedule` trigger without a `cron` entry — GitHub "
            "rejects a schedule that has no time."
        )
    if "gh issue create" not in text:
        findings.append(
            "ci.yml never opens an issue. A scheduled failure has to land "
            "somewhere a person will see it, not in a scrollback buffer."
        )
    return findings


def check(root: Path) -> list[str]:
    """Run every check against `root`. Used by the self-test on a fixture."""
    global ROOT, WORKFLOWS, AUTOFIX_SCRIPT, CI_YML
    real = (ROOT, WORKFLOWS, AUTOFIX_SCRIPT, CI_YML)
    try:
        ROOT = root
        WORKFLOWS = root / ".github" / "workflows"
        AUTOFIX_SCRIPT = root / "scripts" / "ci" / "lint-autofix.sh"
        CI_YML = WORKFLOWS / "ci.yml"
        return check_workflows() + check_autofix_script() + check_nightly()
    finally:
        ROOT, WORKFLOWS, AUTOFIX_SCRIPT, CI_YML = real


# ---------------------------------------------------------------------------
# Positive control
# ---------------------------------------------------------------------------

GOOD_WORKFLOWS = {
    "codeql.yml": (
        "name: CodeQL\n"
        "on:\n"
        "  schedule:\n"
        "    - cron: \"17 3 * * 1\"\n"
        "jobs:\n"
        "  analyze:\n"
        "    permissions:\n"
        "      security-events: write\n"
        "    steps:\n"
        "      - uses: github/codeql-action/init@v4\n"
        "        with:\n"
        "          languages: java-kotlin\n"
        "      - uses: github/codeql-action/analyze@v4\n"
    ),
    "scorecard.yml": (
        "name: Scorecard\n"
        "jobs:\n"
        "  analysis:\n"
        "    permissions:\n"
        "      id-token: write\n"
        "    steps:\n"
        "      - uses: ossf/scorecard-action@v2\n"
        "        with:\n"
        "          results_file: results.sarif\n"
    ),
    "lint-autofix.yml": (
        "name: Lint auto-fix\n"
        "jobs:\n"
        "  autofix:\n"
        "    steps:\n"
        "      - run: bash scripts/ci/lint-autofix.sh\n"
    ),
    "ci.yml": (
        "name: CI\n"
        "on:\n"
        "  schedule:\n"
        "    - cron: \"0 3 * * *\"\n"
        "jobs:\n"
        "  nightly-alert:\n"
        "    steps:\n"
        "      - run: gh issue create --title nightly\n"
    ),
}


def write_fixture(root: Path, workflows: dict[str, str], with_script: bool = True) -> None:
    workflows_dir = root / ".github" / "workflows"
    workflows_dir.mkdir(parents=True, exist_ok=True)
    # The cases run one after another in one directory. A case that removes a
    # workflow has to remove the file for real — otherwise the file of the
    # previous case is still there, and the case tests nothing.
    for stale in workflows_dir.glob("*.yml"):
        stale.unlink()
    for name, content in workflows.items():
        (workflows_dir / name).write_text(content, encoding="utf-8")
    script = root / "scripts" / "ci" / "lint-autofix.sh"
    script.parent.mkdir(parents=True, exist_ok=True)
    if with_script:
        script.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    elif script.exists():
        # The fixtures are written into one directory, one case after another;
        # a case that removes the script has to remove it for real.
        script.unlink()


def run_self_test() -> int:
    """Prove the check fires on a broken tree and stays quiet on a good one.

    Both halves matter. A check that reports findings on a good tree teaches
    people to ignore it; a check that reports nothing on a broken tree is the
    defect this gate exists to prevent.
    """
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # A tree that satisfies every requirement.
        write_fixture(root, GOOD_WORKFLOWS)
        clean = check(root)
        if clean:
            print(
                "self-test FAILED — the check reports findings on a tree that satisfies "
                "every requirement:",
                file=sys.stderr,
            )
            for line in clean:
                print(f"  · {line}", file=sys.stderr)
            return 1

        # The same tree with each defect introduced one at a time.
        cases = {
            "codeql.yml is gone": {
                "workflows": {k: v for k, v in GOOD_WORKFLOWS.items() if k != "codeql.yml"},
                "expect": "codeql.yml does not exist",
            },
            "codeql.yml lost its upload permission": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "codeql.yml": GOOD_WORKFLOWS["codeql.yml"].replace(
                        "security-events: write", "contents: read"
                    ),
                },
                "expect": "security-events: write",
            },
            "scorecard.yml lost the id-token permission": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "scorecard.yml": GOOD_WORKFLOWS["scorecard.yml"].replace(
                        "id-token: write", "contents: read"
                    ),
                },
                "expect": "id-token: write",
            },
            "the auto-fix script is gone": {
                "workflows": GOOD_WORKFLOWS,
                "script": False,
                "expect": "scripts/ci/lint-autofix.sh does not exist",
            },
            "ci.yml has no schedule": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace("schedule:", "pull_request:"),
                },
                "expect": "no `schedule` trigger",
            },
            "ci.yml never opens an issue": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace("gh issue create", "echo"),
                },
                "expect": "never opens an issue",
            },
        }

        for name, case in cases.items():
            write_fixture(
                root,
                case.get("workflows", GOOD_WORKFLOWS),
                with_script=case.get("script", True),
            )
            findings = check(root)
            if not any(case["expect"] in f for f in findings):
                print(
                    f"self-test FAILED — the check did not detect: {name}",
                    file=sys.stderr,
                )
                for line in findings:
                    print(f"  · {line}", file=sys.stderr)
                return 1

    print("✅ self-test: the check fires on every defect it exists to catch")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the check detects a deliberately unwired repository",
    )
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    findings = check(ROOT)
    if findings:
        print("", file=sys.stderr)
        for finding in findings:
            print(f"❌ {finding}", file=sys.stderr)
        return 1

    print("✅ the CI workflows are wired")
    return 0


if __name__ == "__main__":
    sys.exit(main())
