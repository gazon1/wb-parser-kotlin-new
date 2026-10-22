#!/usr/bin/env python3
"""Check that the fat jar and the resolved runtime classpath agree.

The build believes one classpath; the image ships another. Nothing compares them,
so the difference stays invisible until a class is missing at runtime — the class
that was declared `compileOnly`, the jar that a starter dragged in and nobody
named, the library that the module boundary promised and the application never
received.

Three checks, each of which has bitten this project at least once:

1. **The jar holds exactly the resolved runtime classpath.** Every artifact in
   `BOOT-INF/lib` must be on the resolved classpath (nothing extra ships), and
   every artifact on the resolved classpath must be in `BOOT-INF/lib` (nothing
   declared is missing from the image).

2. **No class ships twice.** A class in `BOOT-INF/classes` that is also inside a
   jar in `BOOT-INF/lib` can be loaded from two places, and only one of them is
   the one the build produced. The crawler module was copied into
   `BOOT-INF/classes` while also shipping as a jar, so every crawler class
   shipped twice.

3. **Every class the code references is in the image.** `jdeps` reads the
   constant pool of the compiled application and the compiled crawler module and
   reports the references it cannot resolve against the shipped jars. This is
   the check that catches a `compileOnly` leak before the JVM does: the class
   compiles, the tests pass, and the image has no copy of it.

Usage:
    python3 scripts/ci/check-runtime-classpath.py \\
        [--jar app/build/libs/app.jar] \\
        [--classpath app/build/runtime-classpath.txt]

The classpath file is written by the `:app:writeRuntimeClasspath` Gradle task.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

DEFAULT_JAR = ROOT / "app" / "build" / "libs" / "app.jar"
DEFAULT_CLASSPATH_FILE = ROOT / "app" / "build" / "runtime-classpath.txt"

# Our own compiled code. jdeps reads these; the shipped jars are the classpath
# it resolves them against.
CODE_DIRS = [
    ROOT / "app" / "build" / "classes" / "kotlin" / "main",
    ROOT / "browser" / "build" / "classes" / "kotlin" / "main",
    ROOT / "wbparser" / "build" / "classes" / "kotlin" / "main",
]

# The fat jar is a multi-release jar (log4j-api is one), and jdeps refuses to
# read such a classpath without being told which version to look at.
MULTI_RELEASE = "17"

# Added to the image by the Spring Boot Gradle plugin, for the layered-image
# support. It is not a runtime dependency of the application.
JARMODE_TOOLS = "spring-boot-jarmode-tools-3.4.1.jar"


def fail(message: str) -> None:
    print(f"  ✗ {message}", file=sys.stderr)
    sys.exit(1)


def read_classpath(path: Path) -> list[Path]:
    if not path.exists():
        fail(
            f"the runtime classpath file is missing: {path}\n"
            "    Run the `:app:writeRuntimeClasspath` Gradle task first."
        )
    entries = [Path(line.strip()) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not entries:
        fail(f"the runtime classpath file is empty: {path}")
    return entries


def jar_contents(jar: Path) -> tuple[list[str], set[str]]:
    """Return (the jars in BOOT-INF/lib, the classes in BOOT-INF/classes)."""
    if not jar.exists():
        fail(f"the fat jar is missing: {jar}\n    Build it with `./gradlew :app:bootJar` first.")

    libs: list[str] = []
    classes: set[str] = set()
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                libs.append(entry.removeprefix("BOOT-INF/lib/"))
            elif entry.startswith("BOOT-INF/classes/") and entry.endswith(".class"):
                classes.add(entry.removeprefix("BOOT-INF/classes/"))
    return sorted(libs), classes


def has_classes(artifact: Path) -> bool:
    """Does this artifact carry any class at all?"""
    if not artifact.is_file():
        return False
    try:
        with zipfile.ZipFile(artifact) as archive:
            return any(entry.endswith(".class") for entry in archive.namelist())
    except zipfile.BadZipFile:
        return False


def check_classpath_agrees(libs: list[str], classpath: list[Path]) -> list[str]:
    """The jar holds exactly the resolved runtime classpath.

    Spring Boot's packaging differs from the resolved classpath in two ways, and
    both are deliberate:

    - a jar with no classes in it is dropped from the image. The starters are
      exactly that: a manifest, a licence, and nothing else. Dropping them
      changes nothing at runtime.
    - `spring-boot-jarmode-tools` is added to the image, for the layered-image
      support. It is not a runtime dependency of the application.

    Everything else must match, in both directions.
    """
    findings: list[str] = []

    resolved = {entry.name: entry for entry in classpath if entry.is_file()}
    resolved_dirs = [entry for entry in classpath if entry.is_dir()]
    missing_files = [entry for entry in classpath if not entry.exists()]
    shipped = set(libs)

    for name in sorted(shipped - set(resolved)):
        if name == JARMODE_TOOLS:
            continue
        findings.append(f"the image ships {name}, which is not on the resolved runtime classpath")

    for name, path in sorted(resolved.items()):
        if name in shipped:
            continue
        if not has_classes(path):
            continue  # a jar with no classes: Spring Boot drops it, and nothing is lost
        findings.append(f"{name} is on the resolved runtime classpath but not in the image")

    for directory in resolved_dirs:
        findings.append(
            f"{directory} is a directory on the runtime classpath; the image can only ship files"
        )
    for path in missing_files:
        findings.append(f"{path} is on the runtime classpath but does not exist on disk")
    return findings


def check_no_duplicate_classes(jar: Path, classes: set[str]) -> list[str]:
    """No class ships both loose and inside a jar."""
    findings: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not (entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar")):
                continue
            library = entry.removeprefix("BOOT-INF/lib/")
            with archive.open(entry) as nested:
                with zipfile.ZipFile(nested) as inner:
                    for name in inner.namelist():
                        if name.endswith(".class") and name in classes:
                            findings.append(
                                f"{name} is in BOOT-INF/classes and also inside {library}"
                            )
    return findings


def check_references_resolve(extracted: Path) -> list[str]:
    """Every class our code references is in the image."""
    inputs = [str(directory) for directory in CODE_DIRS if directory.is_dir()]
    if not inputs:
        fail("neither the application nor the crawler module has compiled classes")

    classpath = [str(extracted / "BOOT-INF" / "classes")]
    lib_dir = extracted / "BOOT-INF" / "lib"
    if lib_dir.is_dir():
        classpath.append(str(lib_dir / "*"))

    result = subprocess.run(
        [
            "jdeps",
            "--multi-release",
            MULTI_RELEASE,
            "--missing-deps",
            "--class-path",
            ":".join(classpath),
            *inputs,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        fail(f"jdeps failed:\n{result.stderr.strip()}")

    findings: list[str] = []
    for line in result.stdout.splitlines():
        if "not found" in line:
            findings.append(line.strip())
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--classpath", type=Path, default=DEFAULT_CLASSPATH_FILE)
    args = parser.parse_args()

    jar = args.jar if args.jar.is_absolute() else ROOT / args.jar
    classpath_file = args.classpath if args.classpath.is_absolute() else ROOT / args.classpath

    classpath = read_classpath(classpath_file)
    libs, classes = jar_contents(jar)

    findings = check_classpath_agrees(libs, classpath)
    findings += check_no_duplicate_classes(jar, classes)

    with tempfile.TemporaryDirectory(prefix="runtime-classpath-") as temporary:
        extracted = Path(temporary)
        with zipfile.ZipFile(jar) as archive:
            archive.extractall(extracted)
        findings += check_references_resolve(extracted)

    if findings:
        print("runtime classpath gate FAILED:", file=sys.stderr)
        for finding in findings:
            print(f"  ✗ {finding}", file=sys.stderr)
        return 1

    print(
        f"runtime classpath gate: {len(libs)} artifacts, "
        f"{len(classes)} application classes, every reference resolves"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
