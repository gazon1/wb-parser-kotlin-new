#!/usr/bin/env python3
"""Check that the Docker images carry the JDK the build asks for.

The build names one JDK version — `jvmToolchain(22)` in the root build — and the
images are supposed to carry that same version. The two statements are one
statement, but they live in two files, and nothing connected them.

When they disagree, the build inside the image cannot find the toolchain it asked
for. The toolchain tries to provision the missing JDK, which does not work in the
container, and the image build dies with a message that says nothing about the
cause: the base image was bumped by a dependency update, and the repository's own
rule ("the JDK inside them is pinned to the version the build asks for") was broken
by a one-line change that looked harmless.

So the rule is checked here: every `eclipse-temurin` base image must carry the
version the build asks for. A base-image bump that breaks the build is a pull
request to review — and this gate is what makes "the review" mean something.

A recorded decision changes what the gate says, but not what it checks
----------------------------------------------------------------------
A major bump of the base image is a decision, and the decision is recorded in
`config/gates/dependency-decisions.tsv` — by the dependency, not by the number
of the pull request that carried it. `eclipse-temurin 22 → 25` was deferred once
already: the image was taken with JDK 25, the build could not use it, and the
repository went back to 22.

Dependabot opens a new pull request for the same bump every time, so the gate
sees the same divergence again and again. A gate that fails without saying why
cannot be told apart from a gate that found a new defect — so when the decision
table holds a `deferred` decision for this dependency and this bump, the gate
explains itself ("решение отложено: …") instead of failing. A divergence with
no recorded decision is still a failure: nobody decided that.

Usage:
    python3 scripts/ci/check-docker-jdk.py
    python3 scripts/ci/check-docker-jdk.py --self-test
    python3 scripts/ci/check-docker-jdk.py --root DIR
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

SCRIPTS = Path(__file__).resolve().parent.parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import dependency_decision  # noqa: E402

# The build's one number.
TOOLCHAIN = re.compile(r"jvmToolchain\((\d+)\)")

# A base image and the JDK it carries.
BASE_IMAGE = re.compile(r"^FROM\s+eclipse-temurin:(\d+)", re.MULTILINE)

# The dependency the base image is: the name dependabot gives it.
DEPENDENCY = "eclipse-temurin"

# The files that can carry a JDK. Every one of them is checked, and a file that
# stops carrying one simply stops being checked — the "at least one" rule below
# is what keeps the check from becoming a no-op.
DOCKERFILES = ["Dockerfile.app", "Dockerfile.debug"]


def without_comments(text: str) -> str:
    """The code without the comments.

    The build explains itself in comments, and one of those explanations mentions
    the version the toolchain *used* to be named with. A check that reads the
    comment believes the build asks for an old JDK — and a gate that cries wolf is
    a gate that gets switched off.
    """
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def recorded(dependency: str, old: str, new: str, table: Path | None):
    """The decision recorded for this dependency and this bump.

    `(decision, reason)` — or `("", "")` when nothing is recorded.
    """
    path = table if table is not None else dependency_decision.TABLE
    found = dependency_decision.decision_for(dependency, (old, new), path)
    if found == dependency_decision.NONE:
        return "", ""
    decision, _, reason = found.partition("\t")
    return decision, reason.strip()


def check(root: Path, table: Path | None = None) -> tuple[list[str], list[str]]:
    """The findings and the explanations.

    A finding is a divergence nobody decided about. An explanation is a
    divergence the decisions table already holds an answer for.
    """
    findings: list[str] = []
    explanations: list[str] = []

    build = root / "build.gradle.kts"
    if not build.exists():
        return [f"{build} is missing — the JDK version the build asks for is unknown"], explanations

    toolchain = TOOLCHAIN.search(without_comments(build.read_text()))
    if toolchain is None:
        return (
            [f"{build} does not name a toolchain — the JDK version the build asks for is unknown"],
            explanations,
        )
    wanted = toolchain.group(1)

    found_any = False
    for name in DOCKERFILES:
        path = root / name
        if not path.exists():
            continue
        for version in BASE_IMAGE.findall(path.read_text()):
            found_any = True
            if version == wanted:
                continue
            old, new = sorted((wanted, version), key=int)
            decision, reason = recorded(DEPENDENCY, old, new, table)
            if decision == "deferred" and reason:
                note = f"решение отложено: {reason}"
                if note not in explanations:
                    explanations.append(note)
            else:
                findings.append(
                    f"{name} carries eclipse-temurin:{version}, but the build asks for JDK {wanted}"
                )

    if not found_any:
        findings.append("no eclipse-temurin base image was found — the images' JDK is not checked")

    return findings, explanations


def self_test() -> int:
    """Prove the check can fail: a bumped base image must be rejected."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory) / "tree"
        root.mkdir()

        # A table with no decision in it: the divergence is nobody's decision
        # yet, and the gate has to say so.
        empty = Path(directory) / "empty.tsv"
        empty.write_text("# решений нет\n", encoding="utf-8")

        # The decision that was really taken about this very bump.
        deferred = Path(directory) / "deferred.tsv"
        deferred.write_text(
            "eclipse-temurin\tdeferred\t22 → 25\tвзяли и сломали: образ с JDK 25 не обеспечил сборку.\n",
            encoding="utf-8",
        )

        (root / "build.gradle.kts").write_text("    jvmToolchain(22)\n")
        (root / "Dockerfile.app").write_text("FROM eclipse-temurin:22-jdk-alpine AS builder\n")
        (root / "Dockerfile.debug").write_text("FROM eclipse-temurin:22-jre\n")

        findings, explanations = check(root, empty)
        if findings or explanations:
            print("self-test FAILED: a matching tree was rejected", file=sys.stderr)
            return 1

        # The break this gate exists for: the base image moved, the build did not.
        (root / "Dockerfile.app").write_text("FROM eclipse-temurin:25-jdk-alpine AS builder\n")

        findings, explanations = check(root, empty)
        if not findings:
            print("self-test FAILED: a bumped base image was accepted", file=sys.stderr)
            return 1
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)

        # The same divergence, with the decision that was taken about it: the
        # gate explains itself instead of failing.
        findings, explanations = check(root, deferred)
        if findings:
            print("self-test FAILED: a decided divergence was reported as a finding", file=sys.stderr)
            return 1
        if not explanations or "решение отложено" not in explanations[0]:
            print("self-test FAILED: a decided divergence was not explained", file=sys.stderr)
            return 1

        # And the other direction: the build moved, the images did not.
        shutil.copy(root / "Dockerfile.app", root / "Dockerfile.app.bak")
        (root / "Dockerfile.app").write_text("FROM eclipse-temurin:22-jdk-alpine AS builder\n")
        (root / "build.gradle.kts").write_text("    jvmToolchain(25)\n")
        findings, _ = check(root, empty)
        if not findings:
            print("self-test FAILED: a moved toolchain was accepted", file=sys.stderr)
            return 1

        # And a version that is only mentioned in a comment must not be read as the
        # version the build asks for.
        (root / "build.gradle.kts").write_text(
            "    // It used to be repeated as `jvmToolchain(21)` in each module.\n"
            "    jvmToolchain(22)\n"
        )
        findings, _ = check(root, empty)
        if findings:
            print("self-test FAILED: a version in a comment was read as the build's", file=sys.stderr)
            return 1

    print("✅ self-test: a base image that does not match the build is rejected")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="the tree to check")
    parser.add_argument("--self-test", action="store_true", help="prove the check can fail")
    arguments = parser.parse_args()

    if arguments.self_test:
        return self_test()

    findings, explanations = check(arguments.root)
    for explanation in explanations:
        print(f"⚠️ {explanation}")
    if findings:
        print("❌ the images do not carry the JDK the build asks for:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1
    if explanations:
        print("✅ the images do not carry the JDK the build asks for — but the decision is recorded")
    else:
        print("✅ the images carry the JDK the build asks for")
    return 0


if __name__ == "__main__":
    sys.exit(main())
