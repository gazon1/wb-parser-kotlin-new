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
    "browser-api/src/main/kotlin",
    "browser/src/main/kotlin",
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The browser modules are libraries: they know about a browser and about nothing else.
#: No database, no framework, no HTTP client of their own — the crawler owns those.
#:
#: There are two of them, and the difference between them is the whole point:
#:
#: - `browser-api` is the *contract*: the page, the session, the profile, the pool.
#:   It names no runtime at all.
#: - `browser` is the *runtime*: the Camoufox build, the Playwright launcher, the
#:   adapters. It implements the contract and depends on it.
BROWSER_MODULES = (
    "browser-api/src/main/kotlin",
    "browser/src/main/kotlin",
)

#: What the browser modules must not name.
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
#: modules know about a browser and about nothing else; this says the rest of the
#: repository does not know about Playwright either. The browser contract is
#: `BrowserPage`, and Playwright is an implementation detail of the runtime.
#:
#: Without this rule the boundary is only half-declared: Playwright could creep
#: back into `wbparser` — which is exactly how it got there the first time, and
#: why the crawler needed `implementation(libs.playwright)` at all.
FORBIDDEN_OUTSIDE_BROWSER = ("com.microsoft.playwright",)

#: The modules whose production sources must not name Playwright.
MODULES_WITHOUT_PLAYWRIGHT = (
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The one package inside the runtime module that may name Playwright: the adapter
#: that wraps it. Everything else in the repository speaks `BrowserPage`.
BROWSER_PLAYWRIGHT_PACKAGE = "browser/src/main/kotlin/ru/wbparser/browser/playwright"

#: The runtime's packages: the Camoufox build and the Playwright adapters.
#:
#: The contract module must not name them, and neither must the crawler. The
#: application may — but only in its composition root, the place that binds the
#: contract to the runtime. Everywhere else the runtime would be reachable from
#: business logic, and replacing it would stop being a change of one module.
BROWSER_RUNTIME_PACKAGES = (
    "ru.wbparser.browser.camoufox",
    "ru.wbparser.browser.playwright",
)

#: The composition root: the only place outside the runtime module that may name the
#: runtime.
COMPOSITION_ROOT = "app/src/main/kotlin/ru/wbparser/app/config"

#: The production sources whose types a test double may stand in for.
PRODUCTION_SOURCE_SETS = (
    "browser-api/src/main/kotlin",
    "browser/src/main/kotlin",
    "wbparser/src/main/kotlin",
    "app/src/main/kotlin",
)

#: The test source sets a double may live in.
TEST_SOURCE_SETS = (
    "browser-api/src/test/kotlin",
    "browser-api/src/testFixtures/kotlin",
    "browser/src/test/kotlin",
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


def _closing_quote(text: str, start: int, quote: str) -> int:
    """The index just past the closing quote of the literal that starts at `start`."""
    index = start + 1
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == quote:
            return index + 1
        index += 1
    return len(text)


def _scan_interpolation(text: str, start: int, out: list[str]) -> int:
    """Scans the code inside `${...}` as code — it *is* code, and it can name a runtime.

    A string template is half literal and half code. Dropping the whole thing
    would drop the code with it, and `"${ru.wbparser.browser.camoufox.CamoufoxAutomation}"`
    is a reference as plainly as the same name written out.
    """
    index = start + 2
    depth = 0
    while index < len(text):
        char = text[index]
        if char == '"':
            index = _scan_string(text, index, out)
            continue
        if char == "'":
            index = _closing_quote(text, index, "'")
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            if depth == 0:
                return index + 1
            depth -= 1
        out.append(char)
        index += 1
    return index


def _scan_string(text: str, start: int, out: list[str]) -> int:
    """Scans a `"..."` literal: the text goes, the interpolations stay."""
    index = start + 1
    while index < len(text):
        char = text[index]
        if char == "\\":
            out.append("\n" * text.count("\n", index, index + 2))
            index += 2
            continue
        if char == '"':
            return index + 1
        if char == "$" and text.startswith("${", index):
            index = _scan_interpolation(text, index, out)
            continue
        if char == "\n":
            out.append("\n")
        index += 1
    return index


def _scan_raw_string(text: str, start: int, out: list[str]) -> int:
    """Scans a Kotlin raw string literal: the text goes, the interpolations stay."""
    index = start + 3
    while index < len(text):
        if text.startswith('"""', index):
            return index + 3
        if text[index] == "$" and text.startswith("${", index):
            index = _scan_interpolation(text, index, out)
            continue
        if text[index] == "\n":
            out.append("\n")
        index += 1
    return index


def strip_comments_and_strings(text: str) -> str:
    """Removes what cannot hold code: comments and string literals.

    A rule that fires on a KDoc mentioning `!!` is a rule people learn to work
    around, and a rule people work around is worse than no rule.

    The line structure survives — every removed line still ends in a newline — so
    a finding can name the line the code was on.

    The code inside a string template is code, not text, so it stays: a runtime
    named in `${...}` is a runtime named.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == "/" and text.startswith("//", index):
            end = text.find("\n", index)
            index = length if end == -1 else end
            continue
        if char == "/" and text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end == -1 else end + 2
            out.append("\n" * text.count("\n", index, end))
            index = end
            continue
        if text.startswith('"""', index):
            index = _scan_raw_string(text, index, out)
            continue
        if char == '"':
            index = _scan_string(text, index, out)
            continue
        if char == "'":
            index = _closing_quote(text, index, "'")
            continue
        out.append(char)
        index += 1
    return "".join(out)


def strip_noise(line: str) -> str:
    """Removes what cannot hold code: comments and string literals."""
    return strip_comments_and_strings(line)


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
    """The browser modules know about a browser and about nothing else.

    No database, no HTTP client of their own, no metrics, no arrow: the crawler owns
    those, and the browser stack is the thing the crawler borrows a page from.
    """
    findings: list[str] = []
    for module in BROWSER_MODULES:
        for path in kotlin_files(root, module):
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


def runtime_mentions(text: str) -> list[tuple[int, str]]:
    """The runtime packages named in `text`, with the line each was named on.

    A mention is the package name in code — an import, a fully qualified type, a
    qualified call — and not a mention in prose. Comments and string literals are
    stripped first, so a KDoc that discusses the runtime does not trip the rule.

    An import is not the only way to name a package: `ru.wbparser.browser.camoufox.
    CamoufoxAutomation` in a return type names it just as plainly, and the
    compiler accepts it wherever the runtime is on the classpath.
    """
    findings: list[tuple[int, str]] = []
    cleaned = strip_comments_and_strings(text)
    for number, line in enumerate(cleaned.splitlines(), 1):
        for runtime_package in BROWSER_RUNTIME_PACKAGES:
            if re.search(rf"\b{re.escape(runtime_package)}\b", line):
                findings.append((number, runtime_package))
    return findings


def check_the_runtime_stays_in_its_module(root: Path) -> list[str]:
    """The runtime is named by the runtime module and by the composition root only.

    The contract module (`:browser-api`) names no runtime at all — that is what makes
    the runtime replaceable. The crawler names no runtime either. The application may
    name it, but only in its composition root: the place that binds the contract to
    the runtime. Everywhere else, business logic could reach the runtime, and
    replacing it would stop being a change of one module.

    The compiler already stops most of this — the crawler does not have the runtime
    on its classpath. The rule covers the rest: the contract module and the
    application outside its composition root.

    A name is a name, however it is written: an import or a fully qualified
    reference. The rule looks for the package in the code, not for the word
    `import`, because the application does have the runtime on its classpath and a
    fully qualified reference compiles there.
    """
    findings: list[str] = []

    for module in BROWSER_MODULES:
        if module == "browser/src/main/kotlin":
            continue
        for path in kotlin_files(root, module):
            for number, runtime_package in runtime_mentions(path.read_text(encoding="utf-8")):
                findings.append(
                    f"{relative(path, root)}:{number}: the contract module names "
                    f"{runtime_package} — the contract knows no runtime"
                )

    composition_root = root / COMPOSITION_ROOT
    for path in kotlin_files(root, "app/src/main/kotlin"):
        # The composition root is a directory, not a name: a sibling whose path
        # merely contains the same letters is a different place.
        if path.is_relative_to(composition_root):
            continue
        for number, runtime_package in runtime_mentions(path.read_text(encoding="utf-8")):
            findings.append(
                f"{relative(path, root)}:{number}: the application names "
                f"{runtime_package} outside its composition root — the runtime is "
                "bound to the contract there and nowhere else"
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

    for module in BROWSER_MODULES:
        for path in kotlin_files(root, module):
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
                            "runtime module's own adapter — the repository speaks `BrowserPage`"
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
                    stood_for = name[len("Mock") :]
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
        + check_the_runtime_stays_in_its_module(root)
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
        "package ru.wbparser.app\n\nfun broken(value: String?): String {\n    delay(-1L)\n    return value!!\n}\n"
    ),
    "wbparser/src/main/kotlin/ru/wbparser/Broken.kt": (
        "package ru.wbparser\n\nimport org.springframework.stereotype.Component\n\nclass Broken\n"
    ),
    "browser/src/main/kotlin/ru/wbparser/browser/Broken.kt": (
        "package ru.wbparser.browser\n\nimport org.jetbrains.exposed.sql.Table\n\nclass Broken\n"
    ),
    "wbparser/src/main/kotlin/ru/wbparser/infra/http/Leaky.kt": (
        "package ru.wbparser.infra.http\n\nimport com.microsoft.playwright.Page\n\nclass Leaky\n"
    ),
    "wbparser/src/test/kotlin/ru/wbparser/infra/http/MockBrowserPool.kt": (
        "package ru.wbparser.infra.http\n\nclass MockBrowserPool\n"
    ),
    "browser/src/main/kotlin/ru/wbparser/browser/pool/BrowserPool.kt": (
        "package ru.wbparser.browser.pool\n\nclass BrowserPool\n"
    ),
    "browser-api/src/main/kotlin/ru/wbparser/browser/pool/Leaky.kt": (
        "package ru.wbparser.browser.pool\n\nimport ru.wbparser.browser.camoufox.CamoufoxAutomation\n\nclass Leaky\n"
    ),
    "app/src/main/kotlin/ru/wbparser/app/scheduling/Leaky.kt": (
        "package ru.wbparser.app.scheduling\n\nimport ru.wbparser.browser.camoufox.CamoufoxInstalls\n\nclass Leaky\n"
    ),
    # A fully qualified reference names the runtime just as plainly as an import,
    # and the application has the runtime on its classpath: it compiles there.
    "app/src/main/kotlin/ru/wbparser/app/scheduling/FullyQualifiedLeaky.kt": (
        "package ru.wbparser.app.scheduling\n\nclass FullyQualifiedLeaky {\n"
        "    fun create(): ru.wbparser.browser.camoufox.CamoufoxAutomation = TODO()\n}\n"
    ),
    # A sibling of the composition root whose path contains the same letters.
    # It is not the composition root, so it is not exempt.
    "app/src/main/kotlin/ru/wbparser/app/configuration/Leaky.kt": (
        "package ru.wbparser.app.configuration\n\nimport ru.wbparser.browser.playwright.PlaywrightBrowserPage\n\nclass Leaky\n"
    ),
    # A string template is half literal and half code. The code names the runtime,
    # so the rule must see it.
    "app/src/main/kotlin/ru/wbparser/app/scheduling/InterpolatedLeaky.kt": (
        "package ru.wbparser.app.scheduling\n\nclass InterpolatedLeaky {\n"
        '    val name = "${ru.wbparser.browser.camoufox.CamoufoxAutomation::class.java.name}"\n}\n'
    ),
    # The runtime named in prose and in plain text only: nothing to report.
    "app/src/main/kotlin/ru/wbparser/app/scheduling/ProseOnly.kt": (
        "package ru.wbparser.app.scheduling\n\n"
        "/**\n * Mentions ru.wbparser.browser.camoufox.CamoufoxAutomation in prose.\n */\n"
        "class ProseOnly {\n"
        '    val label = "ru.wbparser.browser.camoufox.CamoufoxAutomation"\n}\n'
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
            "the contract module names",
            "outside its composition root",
            "FullyQualifiedLeaky.kt",
            "configuration/Leaky.kt",
            "InterpolatedLeaky.kt",
            "outside the browser module",
            "is a double of our own",
            "CHANGELOG.md exists",
            "fixDependencies in CI",
            "pinned JDK path",
        ):
            if not any(expected in finding for finding in findings):
                print(f"self-test FAILED: nothing reported the '{expected}' violation")
                return 1
        for unexpected in ("ProseOnly.kt",):
            if any(unexpected in finding for finding in findings):
                print(f"self-test FAILED: '{unexpected}' was reported, and prose is not a violation")
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
