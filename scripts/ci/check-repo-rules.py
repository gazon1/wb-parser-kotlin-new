#!/usr/bin/env python3
"""check-repo-rules.py — the mechanical rules of the repository, checked by the build.

Why this exists
---------------
A rule that only exists in a document is a rule that is followed by whoever reads
the document and by nobody else. Most of the rules this repository has are of that
kind, and they all lived in AGENTS.md, which every agent reads on every turn — so
they cost context on every turn and enforced nothing.

The rules in this file are the ones a machine can decide: a force-unwrap in a
production source, a `delay(-1L)` sentinel, a Spring import in a module that must
not know Spring, a CHANGELOG.md, a `fixDependencies` in CI, a pinned JDK path, and
the browser module knowing something it must not know.

Each of them is a pattern. A pattern is cheap to check and impossible to forget.

The rules that a machine cannot decide live in CODING_STANDARDS.md, where a
reviewer reads them — and the mechanical ones below are the ones that used to sit
there as prose.

Usage
-----
    python3 scripts/ci/check-repo-rules.py
    python3 scripts/ci/check-repo-rules.py --self-test

Exit codes
----------
    0 — no rule is broken
    1 — a finding
    2 — the check could not run
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

#: The sources that ship. A rule about production code is a rule about these.
PRODUCTION_SOURCES = (
    "browser/src/main/kotlin",
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The browser module is a library: it knows about a browser and about nothing else.
#: No database, no framework, no HTTP client of its own — the crawler owns those.
BROWSER_MODULE = "browser/src/main/kotlin"

#: What the browser module must not name.
FORBIDDEN_IN_BROWSER = (
    "java.sql",
    "javax.sql",
    "org.springframework",
    "kafka",
    "redis",
    "ktor",
    "io.micrometer",
    "org.jetbrains.exposed",
    "arrow",
)

#: The modules that must not name Playwright at all.
#:
#: The other side of the same boundary. `FORBIDDEN_IN_BROWSER` says the browser
#: module knows about a browser and about nothing else; this says the rest of the
#: repository does not know about Playwright either. The browser module's public
#: API is `BrowserPage`, and Playwright is an implementation detail of the module
#: that owns it.
#:
#: Without this rule the boundary is only half-declared: Playwright could creep
#: back into `wbparser` — which is exactly how it got there the first time, and
#: why the crawler needed `implementation(libs.playwright)` at all.
FORBIDDEN_OUTSIDE_BROWSER = (
    "com.microsoft.playwright",
)

#: The modules whose production sources must not name Playwright.
MODULES_WITHOUT_PLAYWRIGHT = (
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The one package inside the browser module that may name Playwright: the adapter
#: that wraps it. Everything else in the module speaks `BrowserPage`.
BROWSER_PLAYWRIGHT_PACKAGE = "browser/src/main/kotlin/ru/wbparser/browser/playwright"

#: The production sources whose types a test double may stand in for.
PRODUCTION_SOURCE_SETS = (
    "browser/src/main/kotlin",
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The test source sets a double may live in.
TEST_SOURCE_SETS = (
    "browser/src/test/kotlin",
    "browser/src/testFixtures/kotlin",
    "wbparser/src/test/kotlin",
    "wbparser/src/testFixtures/kotlin",
    "app/src/test/kotlin",
    "tests/src/test/kotlin",
)

#: A declaration of a type: `class X`, `interface X`, `object X`, with the
#: modifiers that may precede it.
_DECLARATION = re.compile(
    r"\b(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)",
)


def kotlin_files(root: Path, relative: str) -> list[Path]:
    """Every Kotlin source under `relative`, sorted."""
    directory = root / relative
    if not directory.is_dir():
        return []
    return sorted(directory.rglob("*.kt"))


def strip_noise(line: str) -> str:
    """Removes what cannot hold code: comments and string literals.

    A rule that fires on a KDoc mentioning `!!` is a rule people learn to work
    around, and a rule people work around is worse than no rule.
    """
    out: list[str] = []
    index = 0
    in_string = False
    while index < len(line):
        char = line[index]
        if in_string:
            if char == "\\":
                index += 2
                continue
            if char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            index += 1
            continue
        if char == "/" and index + 1 < len(line) and line[index + 1] == "/":
            break
        out.append(char)
        index += 1
    return "".join(out)


def relative(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def check_force_unwrap(root: Path) -> list[str]:
    """No `!!` in production code.

    A force-unwrap is a crash with a stack trace instead of a decision. The
    repository's rule is `require`/`check` with an explicit message, or a nullable
    with a `let` — both say what the code expects, and neither lies at runtime.
    """
    pattern = re.compile(r"[A-Za-z0-9_)\]]\s*!!(?!=)")
    findings: list[str] = []
    for source in PRODUCTION_SOURCES:
        for path in kotlin_files(root, source):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if pattern.search(strip_noise(line)):
                    findings.append(
                        f"{relative(path, root)}:{number}: a force-unwrap in production code — "
                        "use require/check with a message, or a nullable with a `let`"
                    )
    return findings


def check_delay_sentinel(root: Path) -> list[str]:
    """No `delay(-1L)` as a sentinel.

    A negative delay is not "no delay": it is a number the coroutine machinery
    refuses to wait for. `null` says "there is no delay", and it says it in a way
    the type system can check.
    """
    pattern = re.compile(r"delay\(\s*-\s*1\s*L?\s*\)")
    findings: list[str] = []
    for source in PRODUCTION_SOURCES:
        for path in kotlin_files(root, source):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if pattern.search(strip_noise(line)):
                    findings.append(
                        f"{relative(path, root)}:{number}: a negative delay as a sentinel — "
                        "use null to say 'there is no delay'"
                    )
    return findings


def check_spring_outside_the_app(root: Path) -> list[str]:
    """No Spring outside the application module.

    The crawler is a library. Spring in it would make the library's test setup
    depend on a container, and the boundary the modules were built for would be a
    convention rather than a fact.

    Micrometer is not Spring: it is a metrics facade, and the crawler reports its
    own gauges through it. What must not be in a library is the *container*, not
    the measurements.
    """
    pattern = re.compile(r"^import\s+org\.springframework")
    findings: list[str] = []
    for source in ("browser/src/main/kotlin", "wbparser/src/main/kotlin"):
        for path in kotlin_files(root, source):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if pattern.match(line.strip()):
                    findings.append(
                        f"{relative(path, root)}:{number}: a framework import outside the "
                        "application module — the crawler is a library"
                    )
    return findings


def check_browser_module_is_a_browser(root: Path) -> list[str]:
    """The browser module knows about a browser and about nothing else.

    No database, no HTTP client of its own, no metrics, no arrow: the crawler owns
    those, and the browser stack is the thing the crawler borrows a page from.
    """
    findings: list[str] = []
    for path in kotlin_files(root, BROWSER_MODULE):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not stripped.startswith("import "):
                continue
            for forbidden in FORBIDDEN_IN_BROWSER:
                if stripped.startswith(f"import {forbidden}"):
                    findings.append(
                        f"{relative(path, root)}:{number}: the browser module names "
                        f"{forbidden} — the browser stack is a library about browsers"
                    )
    return findings


def check_playwright_stays_inside_the_browser_module(root: Path) -> list[str]:
    """Playwright is an implementation detail of the browser module.

    The crawler and the application speak `BrowserPage`; only the adapter inside
    the browser module knows what a Playwright page is. A `com.microsoft.playwright`
    import anywhere else is the boundary leaking, and it is the leak that made the
    crawler depend on Playwright at all.

    The browser module's own adapter may name it — that is what an adapter is — and
    so may the module's tests, which test the adapter against the library it wraps.
    """
    findings: list[str] = []

    for module in MODULES_WITHOUT_PLAYWRIGHT:
        for path in kotlin_files(root, module):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if not stripped.startswith("import "):
                    continue
                for forbidden in FORBIDDEN_OUTSIDE_BROWSER:
                    if stripped.startswith(f"import {forbidden}"):
                        findings.append(
                            f"{relative(path, root)}:{number}: {forbidden} outside the "
                            "browser module — Playwright is an implementation detail; "
                            "the boundary is `BrowserPage`"
                        )

    for path in kotlin_files(root, BROWSER_MODULE):
        if BROWSER_PLAYWRIGHT_PACKAGE in path.relative_to(root).as_posix():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not stripped.startswith("import "):
                continue
            for forbidden in FORBIDDEN_OUTSIDE_BROWSER:
                if stripped.startswith(f"import {forbidden}"):
                    findings.append(
                        f"{relative(path, root)}:{number}: {forbidden} outside the "
                        "browser module's own adapter — the module speaks `BrowserPage`"
                    )
    return findings


def production_type_names(root: Path) -> set[str]:
    """The names of the types the production code declares."""
    names: set[str] = set()
    for source_set in PRODUCTION_SOURCE_SETS:
        for path in kotlin_files(root, source_set):
            for match in _DECLARATION.finditer(path.read_text(encoding="utf-8")):
                names.add(match.group(1))
    return names


def check_test_doubles_are_fakes(root: Path) -> list[str]:
    """A double of one of our own classes is named `Fake*`, not `Mock*`.

    The rule this enforces lives in CODING_STANDARDS.md: a hand-written double of
    our own type is a `Fake` — it answers and remembers — while a mock is for
    checking interaction with a library. The naming is the mechanical half of that
    rule, and this is where it is checked.

    Only a double of *our* type is a finding. Spring's own `MockHttpServletRequest`
    is a library class, not ours, and the rule has nothing to say about it.
    """
    our_types = production_type_names(root)
    if not our_types:
        return []

    findings: list[str] = []
    for source_set in TEST_SOURCE_SETS:
        for path in kotlin_files(root, source_set):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for match in _DECLARATION.finditer(line):
                    name = match.group(1)
                    if not name.startswith("Mock"):
                        continue
                    stood_for = name[len("Mock"):]
                    if not any(stood_for.startswith(known) for known in our_types):
                        continue
                    findings.append(
                        f"{relative(path, root)}:{number}: {name} is a double of our own "
                        f"{stood_for} — a double of our type is named `Fake*`, not `Mock*`"
                    )
    return findings


def check_no_changelog(root: Path) -> list[str]:
    """No CHANGELOG.md.

    The history of this repository is the commit log and the series of pull
    requests. A second history, written by hand, is a history that disagrees with
    the first one.
    """
    if (root / "CHANGELOG.md").exists():
        return ["CHANGELOG.md exists — the history is the commit log and the pull requests"]
    return []


def check_no_fix_dependencies_in_ci(root: Path) -> list[str]:
    """No `fixDependencies` in CI.

    The task rewrites build scripts and does not see runtime-only dependencies, so
    running it in CI would rewrite the build scripts of a commit that never asked
    for it. It is a manual tool with a reviewed diff.
    """
    findings: list[str] = []
    workflows = root / ".github" / "workflows"
    if not workflows.is_dir():
        return findings
    for path in sorted(workflows.glob("*.yml")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "fixDependencies" in line:
                findings.append(
                    f"{relative(path, root)}:{number}: fixDependencies in CI — it rewrites "
                    "build scripts and is a manual tool with a reviewed diff"
                )
    return findings


def check_no_pinned_jdk(root: Path) -> list[str]:
    """No `org.gradle.java.home` in the committed properties.

    A path to one developer's JDK makes the repository buildable on exactly one
    machine — and unbuildable everywhere else, including in CI, where the failure
    reads as a mysterious "Java home supplied is invalid". The version is one
    number in one place: the toolchain.
    """
    properties = root / "gradle.properties"
    if not properties.is_file():
        return []
    findings: list[str] = []
    for number, line in enumerate(properties.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#") or stripped.startswith("!"):
            # A comment may talk about the property — this file does, and explains
            # why it is not there.
            continue
        if stripped.startswith("org.gradle.java.home"):
            findings.append(
                f"gradle.properties:{number}: a pinned JDK path — the toolchain provisions "
                "the JDK, and a path that exists on one machine cannot exist on another"
            )
    return findings


def check(root: Path) -> list[str]:
    """Run every check against `root`. Used by the self-test on a fixture."""
    return (
        check_force_unwrap(root)
        + check_delay_sentinel(root)
        + check_spring_outside_the_app(root)
        + check_browser_module_is_a_browser(root)
        + check_playwright_stays_inside_the_browser_module(root)
        + check_test_doubles_are_fakes(root)
        + check_no_changelog(root)
        + check_no_fix_dependencies_in_ci(root)
        + check_no_pinned_jdk(root)
    )


# ---------------------------------------------------------------------------
# Positive control
# ---------------------------------------------------------------------------

#: A tree that breaks every rule, and the check that must notice.
VIOLATIONS = {
    "app/src/main/kotlin/ru/wbparser/app/Broken.kt": (
        "package ru.wbparser.app\n"
        "\n"
        "fun broken(value: String?): String {\n"
        "    delay(-1L)\n"
        "    return value!!\n"
        "}\n"
    ),
    "wbparser/src/main/kotlin/ru/wbparser/Broken.kt": (
        "package ru.wbparser\n"
        "\n"
        "import org.springframework.stereotype.Component\n"
        "\n"
        "class Broken\n"
    ),
    "browser/src/main/kotlin/ru/wbparser/browser/Broken.kt": (
        "package ru.wbparser.browser\n"
        "\n"
        "import org.jetbrains.exposed.sql.Table\n"
        "\n"
        "class Broken\n"
    ),
    "wbparser/src/main/kotlin/ru/wbparser/infra/http/Leaky.kt": (
        "package ru.wbparser.infra.http\n"
        "\n"
        "import com.microsoft.playwright.Page\n"
        "\n"
        "class Leaky\n"
    ),
    "wbparser/src/test/kotlin/ru/wbparser/infra/http/MockAdsPowerApi.kt": (
        "package ru.wbparser.infra.http\n"
        "\n"
        "class MockAdsPowerApi\n"
    ),
    "browser/src/main/kotlin/ru/wbparser/browser/adspower/AdsPowerApi.kt": (
        "package ru.wbparser.browser.adspower\n"
        "\n"
        "interface AdsPowerApi\n"
    ),
    "CHANGELOG.md": "# The history that must not be here\n",
    ".github/workflows/ci.yml": "jobs:\n  build:\n    steps:\n      - run: ./gradlew fixDependencies\n",
    "gradle.properties": "org.gradle.java.home=/home/max/.jdks/temurin-21.0.10\n",
}


def build_fixture(root: Path) -> None:
    """Writes a tree that breaks every rule."""
    for relative_path, content in VIOLATIONS.items():
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def self_test() -> int:
    """Proves the check can fail: a deliberately broken tree must produce findings."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        build_fixture(root)
        findings = check(root)
        if not findings:
            print("self-test FAILED: a tree that breaks every rule produced no findings")
            return 1
        for expected in (
            "force-unwrap",
            "negative delay",
            "framework import",
            "browser module names",
            "outside the browser module",
            "is a double of our own",
            "CHANGELOG.md exists",
            "fixDependencies in CI",
            "pinned JDK path",
        ):
            if not any(expected in finding for finding in findings):
                print(f"self-test FAILED: nothing reported the '{expected}' violation")
                return 1
        print(f"self-test OK: {len(findings)} findings on a deliberately broken tree")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="prove the check can fail")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not ROOT.is_dir():
        print(f"the repository root {ROOT} does not exist", file=sys.stderr)
        return 2

    findings = check(ROOT)
    if findings:
        print("Repository-rule violations:", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1

    print("repository rules: no violations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
