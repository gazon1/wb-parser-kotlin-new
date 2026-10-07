#!/usr/bin/env python3
"""check-gate-wiring.py — prove the gates exist, are reachable, and can fail.

Why this exists
---------------
This repository accumulated a family of checks that were fully configured,
looked authoritative, and had never executed. Every instance reported success:

* `ktlintCheck` and `detekt` were declared in the version catalogue and in the
  plugins block with `apply false`, and were never applied to a module. The CI
  `lint` job invoked them by name, so the job could only ever fail with
  "Task not found" — and before it was wired, nothing noticed.
* `gradle/detekt-baseline.xml` was written as `<Current issues />`, which the
  detekt parser rejects. It suppressed nothing; it looked like suppression.
* `scripts/check.sh` computed `ROOT` as the `scripts/` directory and then ran
  `./gradlew` from inside it, so `./scripts/check.sh` failed on line 17. The
  documented entry point, `just tests::check`, called `./check.sh`, which was
  not in the repository at all. Both were broken and neither was exercised.
* `refresh-decisions-digest.sh` was run by CI as a *refresh*, never as a
  comparison, so a stale `DIGEST.md` shipped green while the agent that reads
  it before every task was reading superseded rules.

Fixing them one at a time does not prevent the fifth, so this encodes the
failure modes as properties of the repository.

The parts
---------
Part A   **wiring** (static). Every Gradle check task this project configures
         must be named by at least one gate. A task with a config block and no
         gate is decoration.

Part C   **a gate that cannot fail** (static). `ignoreFailures = true` makes a
         lint task decorative no matter who invokes it. This repository's
         sibling had a detekt task invoked with `ignoreFailures = true`, so it
         could not fail there and was not run locally either — nothing enforced
         it and nothing ran it.

Part D   **softening** (static). `|| true` on a gate invocation makes a
         blocking gate advisory without saying so. `advisory` is the declared
         form.

Part E   **asymmetry** (static). A gate present in CI but not locally — or the
         reverse — must be listed with a reason. The drift is always real and
         always invisible from one side.

Part F   **positive control** (static). Every registered gate must either
         implement `--self-test` (a control proving it can fail) or carry an
         exemption with a reason.

Part G   **two callers** (static). The shared registry must be invoked by both
         `check.sh` and ci.yml. One registry, two callers, is the only
         arrangement where a gate existing on one surface only cannot recur.

Part B   **can fail** (mutates inputs, restores them). Every gate with a
         `--self-test` is run; its control must pass. A gate whose own control
         does not detect a deliberately broken fixture is enforcing nothing.

Part A2  **tasks exist** (needs Gradle). Every Gradle task named by a gate must
         resolve. This is the direct guard on "Task not found".

Part A3  **the lifecycle runs them** (needs Gradle, `--dry-run`). `./gradlew
         check` must schedule the lint tasks. "detekt is configured" and "running
         check executes detekt" are different claims; only the second makes a
         gate.

Usage
-----
    python3 scripts/check-gate-wiring.py --static
    python3 scripts/check-gate-wiring.py --can-fail
    python3 scripts/check-gate-wiring.py --tasks-exist

Exit codes
----------
    0 — every part passed
    1 — a finding
    2 — the check could not run
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def set_root(path: Path) -> None:
    """Point the check at a different tree.

    Only the positive control uses this. It needs to run the parts against a
    deliberately broken fixture, and mutating the real repository to produce one
    would be a poor trade for a check whose whole purpose is to not disturb it.
    """
    global ROOT, REGISTRY, CHECK_SH, CI_YML, GATE_DIR, ASYMMETRY_FILE, CONTROL_EXEMPTIONS
    ROOT = path
    REGISTRY = path / "scripts" / "ci" / "static-gates.sh"
    CHECK_SH = path / "check.sh"
    CI_YML = path / ".github" / "workflows" / "ci.yml"
    GATE_DIR = path / "config" / "gates"
    ASYMMETRY_FILE = GATE_DIR / "asymmetries.tsv"
    CONTROL_EXEMPTIONS = GATE_DIR / "control-exemptions.tsv"
REGISTRY = ROOT / "scripts" / "ci" / "static-gates.sh"
CHECK_SH = ROOT / "check.sh"
CI_YML = ROOT / ".github" / "workflows" / "ci.yml"
GATE_DIR = ROOT / "config" / "gates"

ASYMMETRY_FILE = GATE_DIR / "asymmetries.tsv"
CONTROL_EXEMPTIONS = GATE_DIR / "control-exemptions.tsv"

#: Gradle tasks that represent a check. A module that configures one of these
#: and is not named by any gate has a decorative configuration.
CHECK_TASKS = {"test", "check", "build", "detekt", "ktlintCheck"}

#: Plugins whose application creates a check task worth demanding a gate for.
PLUGIN_TASKS = {
    "io.gitlab.arturbosch.detekt": "detekt",
    "org.jlleitschuh.gradle.ktlint": "ktlintCheck",
    "org.springframework.boot": None,
}

#: Words that can follow `./gradlew` without being a task. Everything else is
#: treated as one and must resolve — narrowing that set is what let a typo
#: through in the first place.
NON_TASK_WORDS = {
    "gradlew",  # the wrapper path itself
    "help",
    "tasks",
    "wrapper",
    "projects",
}

GATE_LINE = re.compile(r'^\s*gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+?)\s*$')
GRADLE_TASK = re.compile(r"(?::[A-Za-z0-9_-]+:)?([A-Za-z][A-Za-z0-9_-]*)")


class CheckFailed(Exception):
    """A finding, as opposed to an inability to check."""


class CannotRun(Exception):
    """The check could not run — never silently skipped."""


# ---------------------------------------------------------------------------
# Registry parsing
# ---------------------------------------------------------------------------


def read_registry() -> list[tuple[str, str, list[str]]]:
    if not REGISTRY.exists():
        raise CannotRun(f"{REGISTRY.relative_to(ROOT)} does not exist")
    gates: list[tuple[str, str, list[str]]] = []
    for line in REGISTRY.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        match = GATE_LINE.match(line)
        if not match:
            continue
        mode, name, command = match.groups()
        gates.append((mode, name, command.split()))
    if not gates:
        raise CannotRun(f"no gate lines found in {REGISTRY.relative_to(ROOT)}")
    return gates


def read_tsv(path: Path, header: str) -> dict[str, str]:
    if not path.exists():
        raise CannotRun(f"{path.relative_to(ROOT)} does not exist")
    rows: dict[str, str] = {}
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split("\t")
        if len(parts) < 2:
            raise CannotRun(f"{path.relative_to(ROOT)}:{lineno}: expected TAB-separated columns")
        rows[parts[0]] = "\t".join(parts[1:])
    return rows


def read_text(path: Path) -> str:
    if not path.exists():
        raise CannotRun(f"{path.relative_to(ROOT)} does not exist")
    return path.read_text(encoding="utf-8")


def code_lines(path: Path) -> list[str]:
    """Executable lines of a script or workflow, comments removed.

    A substring search over the raw file finds the gate's name in the comment
    that explains why the gate must be called. That comment is precisely what
    made this check pass while the call was deleted — the check was satisfied
    by its own documentation, which is the most embarassingly quiet way a gate
    can stop gating.

    Both surfaces use `#` for comments: bash, and YAML at the start of a line.
    """
    return [
        line
        for line in read_text(path).splitlines()
        if not line.strip().startswith("#")
    ]


# ---------------------------------------------------------------------------
# Part A — wiring
# ---------------------------------------------------------------------------


def strip_kotlin_comments(source: str) -> str:
    """Remove `//` and block comments, leaving string literals intact.

    Necessary because these checks read the build files, and build files are the
    most heavily commented files in the repository — a comment explaining *why*
    `tasks.named("check")` was replaced reads to a naive scan exactly like the
    configuration it describes. That produced a Part A finding against a line of
    prose, which is the failure mode a gate cannot afford: a false accusation
    teaches people to ignore it.

    Quote-aware, because `"https://…"` inside a repository URL must not be
    mistaken for the start of a comment and swallow the rest of the file.
    """
    out: list[str] = []
    i = 0
    n = len(source)
    in_string = False
    in_line_comment = False
    in_block_comment = False

    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
                out.append(ch)
            i += 1
            continue

        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            if ch == "\n":
                out.append(ch)
            i += 1
            continue

        if in_string:
            out.append(ch)
            if ch == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue

        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue

        out.append(ch)
        i += 1

    return "".join(out)


def configured_check_tasks() -> set[str]:
    """Check tasks configured somewhere in the build.

    Comments are stripped first — see `strip_kotlin_comments`.
    """
    found: set[str] = set()
    for build_file in ROOT.glob("**/build.gradle.kts"):
        if "build" in build_file.relative_to(ROOT).parts[:1]:
            continue
        source = strip_kotlin_comments(build_file.read_text(encoding="utf-8"))
        for plugin, task in PLUGIN_TASKS.items():
            if re.search(rf'apply\(plugin\s*=\s*"{re.escape(plugin)}"\)', source) and task:
                found.add(task)
        for match in re.finditer(r'tasks\.(?:register|register<[^>]*>|named)\s*\(?\s*"([A-Za-z][A-Za-z0-9_-]*)"', source):
            name = match.group(1)
            if name in CHECK_TASKS:
                found.add(name)
        if re.search(r"tasks\.test\s*[{.\s]", source):
            found.add("test")
    return found


def named_by_gates(task: str) -> bool:
    """Is `task` named by any Gradle invocation in check.sh or ci.yml?"""
    for surface in (CHECK_SH, CI_YML):
        for line in code_lines(surface):
            if task in gradle_tasks_on_line(line):
                return True
    return False


def part_a() -> list[str]:
    findings: list[str] = []
    for task in sorted(configured_check_tasks()):
        if not named_by_gates(task):
            findings.append(
                f"Part A: `{task}` is configured in the build but no gate names it. "
                "A configured task with no gate is decoration — nothing else will notice."
            )
    return findings


# ---------------------------------------------------------------------------
# Part C — a gate that cannot fail
# ---------------------------------------------------------------------------


def part_c() -> list[str]:
    findings: list[str] = []
    for build_file in ROOT.glob("**/build.gradle.kts"):
        raw = build_file.read_text(encoding="utf-8")
        source = strip_kotlin_comments(raw)
        for match in re.finditer(r"ignoreFailures\s*=\s*true", source):
            line_no = source[: match.start()].count("\n") + 1
            findings.append(
                f"Part C: {build_file.relative_to(ROOT)}:{line_no} sets `ignoreFailures = true`. "
                "A lint task that cannot fail is decorative wherever it is invoked."
            )
    return findings


# ---------------------------------------------------------------------------
# Part D — softened gates
# ---------------------------------------------------------------------------


def part_d() -> list[str]:
    findings: list[str] = []
    for lineno, line in enumerate(read_text(REGISTRY).splitlines(), 1):
        stripped = line.strip()
        if not stripped.startswith("gate "):
            continue
        if re.search(r"\|\|\s*true\b", stripped) or re.search(r";\s*true\s*$", stripped):
            findings.append(
                f"Part D: {REGISTRY.relative_to(ROOT)}:{lineno} softens a gate with `|| true`. "
                "Declare it `advisory` instead, so the softening is visible in the mode column."
            )
    return findings


# ---------------------------------------------------------------------------
# Part E — declared asymmetry
# ---------------------------------------------------------------------------


def gradle_tasks_in(path: Path) -> set[str]:
    tasks: set[str] = set()
    for line in code_lines(path):
        tasks |= gradle_tasks_on_line(line)
    return tasks


def part_e() -> list[str]:
    findings: list[str] = []
    local = gradle_tasks_in(CHECK_SH)
    remote = gradle_tasks_in(CI_YML)
    declared = read_tsv(ASYMMETRY_FILE, "scope")

    for task in sorted(local - remote):
        key = f"local-only:{task}"
        if key not in declared:
            findings.append(
                f"Part E: `{task}` runs in check.sh but not in ci.yml and is not declared in "
                f"{ASYMMETRY_FILE.relative_to(ROOT)}."
            )
    for task in sorted(remote - local):
        key = f"ci-only:{task}"
        if key not in declared:
            findings.append(
                f"Part E: `{task}` runs in ci.yml but not in check.sh and is not declared in "
                f"{ASYMMETRY_FILE.relative_to(ROOT)}."
            )
    return findings


# ---------------------------------------------------------------------------
# Part F — every gate has a positive control
# ---------------------------------------------------------------------------


def gradle_tasks_on_line(line: str) -> set[str]:
    """Task names from a `./gradlew …` line, and nothing else.

    Everything after the first shell metacharacter belongs to the shell, not to
    Gradle: in `./gradlew ktlintCheck detekt || fail "lint reported violations"`
    the last three words are an error message. Reading them as tasks produced a
    flood of "task does not exist" findings that had to be allowlisted away —
    and an allowlist of false alarms is indistinguishable from one of real
    misses.
    """
    if "gradlew" not in line:
        return set()
    tail = line.split("gradlew", 1)[1]
    tail = re.split(r"[|;&<>]", tail, maxsplit=1)[0]
    tail = tail.replace('"', " ").replace("'", " ")
    tasks: set[str] = set()
    for token in tail.split():
        if token.startswith("-"):
            continue
        # `:module:task` and `module:task` both name a task.
        name = token.rsplit(":", 1)[-1] if ":" in token else token
        if name and re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", name) and name not in NON_TASK_WORDS:
            tasks.add(name)
    return tasks


def gate_script(command: list[str]) -> Path | None:
    """The script a gate invokes, if it invokes one.

    Scans every token rather than only the last: the registry reads
    `python3 scripts/check-gate-wiring.py --static`, so the path is not where a
    naive `command[-1]` would look. Getting this wrong makes a gate that *does*
    have a positive control look like one that has none — the check reporting a
    false accusation is how gate registries stop being believed.
    """
    for token in command:
        if token.startswith("-"):
            continue
        candidate = ROOT / token
        if candidate.suffix in {".py", ".sh"} and candidate.exists():
            return candidate
    return None


def part_f(gates: list[tuple[str, str, list[str]]]) -> list[str]:
    findings: list[str] = []
    try:
        exemptions = read_tsv(CONTROL_EXEMPTIONS, "gate")
    except CannotRun:
        exemptions = {}

    for _, name, command in gates:
        joined = " ".join(command)
        if "unittest discover" in joined:
            continue
        script = gate_script(command)
        has_self_test = "--self-test" in joined or (
            script is not None and "--self-test" in script.read_text(encoding="utf-8")
        )
        if has_self_test or name in exemptions:
            continue
        findings.append(
            f"Part F: gate `{name}` has no positive control — no `--self-test`, and no row in "
            f"{CONTROL_EXEMPTIONS.relative_to(ROOT)}. A gate that cannot be shown to fail may be "
            "enforcing nothing."
        )
    return findings


# ---------------------------------------------------------------------------
# Part G — the registry has two callers
# ---------------------------------------------------------------------------


def part_g() -> list[str]:
    findings: list[str] = []
    # The line must *execute* the registry, not merely mention it. Matching a
    # bare substring accepted `chmod +x scripts/ci/static-gates.sh` as evidence
    # that the gates run — which is the check being satisfied by the line that
    # prepares them, and is a false negative with a plausible shape.
    for surface, label in ((CHECK_SH, "check.sh"), (CI_YML, "ci.yml")):
        runs_it = any(
            "static-gates.sh" in line
            and re.search(r"(?:^|[\s;&|])(?:\./)?(?:bash\s+)?\S*static-gates\.sh(?:\s|$)", line)
            and not re.search(r"^\s*(chmod|ls|cat|echo)\b", line)
            for line in code_lines(surface)
        )
        if not runs_it:
            findings.append(
                f"Part G: {label} never executes scripts/ci/static-gates.sh. A gate registry with "
                "one caller is a gate registry whose other surface will drift — and this "
                "repository already had a gate that lived only in CI."
            )
    return findings


# ---------------------------------------------------------------------------
# Part B — can fail
# ---------------------------------------------------------------------------


def part_b(gates: list[tuple[str, str, list[str]]]) -> list[str]:
    findings: list[str] = []
    ran = 0

    for mode, name, command in gates:
        joined = " ".join(command)
        if "unittest discover" in joined:
            # The unit-test gate proves itself by failing when a test fails.
            # Its own trap — a suite that discovers zero tests and exits 0 — is
            # guarded inside scripts/tests/test_gate_scripts.py.
            ran += 1
            continue

        script = gate_script(command)
        if script is None or "--self-test" not in script.read_text(encoding="utf-8"):
            continue

        # Run the control as the script itself declares it, rather than as the
        # registry happens to invoke the gate. `bash` for a shell gate,
        # `python3` for a Python one; anything else is skipped with a finding
        # rather than guessed at, because a control that runs the wrong way
        # proves nothing.
        if script.suffix == ".sh":
            control = ["bash", str(script), "--self-test"]
        elif script.suffix == ".py":
            control = ["python3", str(script), "--self-test"]
        else:
            raise CannotRun(f"gate `{name}` has an unrecognised script type: {script}")

        ran += 1
        completed = subprocess.run(control, cwd=ROOT, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            findings.append(
                f"Part B: the positive control for `{name}` did not pass (exit "
                f"{completed.returncode}). The gate either cannot fail, or its control does not "
                "test the same code path the gate runs."
            )
            for line in (completed.stderr or completed.stdout).strip().splitlines()[-8:]:
                findings.append(f"    {line}")

    if ran == 0:
        raise CannotRun("no gate declares a positive control — Part B verified nothing")
    print(f"  ·  Part B: {ran} control(s) exercised")
    return findings


# ---------------------------------------------------------------------------
# Parts A2 and A3 — the tasks a gate names exist, and the lifecycle runs them
# ---------------------------------------------------------------------------


LIFECYCLE_TASKS = ("detekt", "ktlintCheck")


def part_a3() -> list[str]:
    """`./gradlew check --dry-run` must schedule the lint tasks.

    `--dry-run` performs configuration and prints the task graph without
    executing it, so this costs seconds rather than a full build. It answers the
    question a static scan cannot: not "is detekt configured" but "does running
    `check` actually schedule it" — different claims, and only the second makes
    a gate.
    """
    completed = subprocess.run(
        ["./gradlew", "check", "--dry-run", "--console=plain", "-q"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise CannotRun(
            "gradle `check --dry-run` failed; cannot verify the lifecycle:\n"
            + completed.stderr[-800:]
        )

    scheduled = set(re.findall(r"^:([A-Za-z0-9:_-]+)", completed.stdout, re.M))
    if not scheduled:
        # `--quiet` can collapse the task graph on some Gradle versions. Reading
        # that as "nothing is wired" would be a false accusation; silently
        # passing on an empty graph would be worse. Neither: say so.
        raise CannotRun(
            "gradle `check --dry-run` produced no task graph; cannot verify the lifecycle"
        )

    findings: list[str] = []
    for task in LIFECYCLE_TASKS:
        if not any(name == task or name.endswith(f":{task}") for name in scheduled):
            findings.append(
                f"Part A3: `{task}` is not scheduled by `./gradlew check`. A lint task that runs "
                "only where someone remembered to invoke it is enforced by accident."
            )
    if not findings:
        print(f"  ·  Part A3: check schedules {', '.join(LIFECYCLE_TASKS)}")
    return findings



def part_a2() -> list[str]:
    named: set[str] = set()
    for surface in (CHECK_SH, CI_YML):
        for line in code_lines(surface):
            # Deliberately NOT narrowed to CHECK_TASKS. Restricting it to a
            # known list let a gate name a typo'd or removed task while this
            # part stayed silent — the exact "Task not found" outcome it exists
            # to prevent.
            named |= gradle_tasks_on_line(line)

    if not named:
        raise CannotRun("no Gradle task is named by any gate — nothing to verify")

    completed = subprocess.run(
        ["./gradlew", "tasks", "--all", "--console=plain", "-q"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise CannotRun(
            "gradle `tasks --all` failed; cannot verify that gate tasks exist:\n"
            + completed.stderr[-800:]
        )

    # Task lines look like `test - Runs this project's tests.` — or
    # `app:bootJar - …` when `--all` qualifies a subproject task with its
    # project. Without the prefix in the pattern, every module task reads as
    # "does not exist", which is a false accusation loud enough to be believed.
    available = set()
    for line in completed.stdout.splitlines():
        # Two forms appear: `build - Assembles and tests this project.` and a
        # bare `app:detekt` with no description (lifecycle tasks from plugins
        # that supply no group description). Requiring the `- …` part made
        # every such task look missing.
        match = re.match(
            r"^(?:[A-Za-z0-9_-]+:)?([A-Za-z][A-Za-z0-9_-]*)(?:\s+-\s.*)?$", line.strip()
        )
        if match:
            available.add(match.group(1))

    findings: list[str] = []
    for task in sorted(named):
        if task not in available:
            findings.append(
                f"Part A2: a gate invokes `{task}`, which does not exist. This is the "
                '"Task not found" failure that let ktlintCheck and detekt report success '
                "forever while never running."
            )
    if not findings:
        print(f"  ·  Part A2: {len(named)} gate task(s) resolve ({', '.join(sorted(named))})")
    return findings


# ---------------------------------------------------------------------------
# Positive control
# ---------------------------------------------------------------------------


BROKEN_FIXTURE = {
    # Part A: detekt is configured but no gate names it.
    "build.gradle.kts": (
        'subprojects {\n'
        '    apply(plugin = "io.gitlab.arturbosch.detekt")\n'
        '    apply(plugin = "org.jlleitschuh.gradle.ktlint")\n'
        '    // Part C: a lint task that cannot fail.\n'
        '    extensions.configure<DetektExtension> { ignoreFailures = true }\n'
        '}\n'
    ),
    # check.sh names only `test`; ci.yml names `test` and `build` → Part E.
    "check.sh": "#!/usr/bin/env bash\n./gradlew :domain:test\n",
    ".github/workflows/ci.yml": (
        "name: CI\njobs:\n  build:\n    steps:\n      - run: ./gradlew build\n      - run: ./gradlew test\n"
    ),
    # Part D: a blocking gate quietly softened. No exemption for it → Part F too.
    "scripts/ci/static-gates.sh": (
        "#!/usr/bin/env bash\n"
        'gate blocking "softened gate" python3 -c "pass" || true\n'
    ),
    "config/gates/asymmetries.tsv": "",
    "config/gates/control-exemptions.tsv": "",
}


def run_self_test() -> int:
    """Positive control: prove the static parts fire on a broken fixture.

    The fixture is built from scratch in a temporary directory and every part is
    run against it. If a part reports nothing on a tree that has every defect it
    exists to catch, the part is not detecting anything — which is precisely the
    state this gate was written to end.
    """
    import tempfile

    real_root = ROOT
    try:
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp)
            for relative, content in BROKEN_FIXTURE.items():
                path = fixture / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")

            set_root(fixture)
            gates = read_registry()
            detected = {
                "A": part_a(),
                "C": part_c(),
                "D": part_d(),
                "E": part_e(),
                "F": part_f(gates),
                "G": part_g(),
            }
    finally:
        set_root(real_root)

    # The assertion is on the part label, not on wording. An earlier version
    # pinned the exact sentence and this control failed the moment a message was
    # reworded for clarity — which is the control being useful about drift, but
    # here it is the control that was wrong: a reworded message is not a broken
    # detector. Each part emits findings prefixed with its own label, so the
    # label is the stable property, and the fixture is built so that every part
    # has a real defect to find.
    missing: list[str] = []
    for part, findings in detected.items():
        labelled = [f for f in findings if f.startswith(f"Part {part}:")]
        if not labelled:
            missing.append(f"Part {part}: produced no labelled finding; got {findings}")

    if missing:
        print("self-test FAILED — the check did not detect a defect it exists to catch:", file=sys.stderr)
        for line in missing:
            print(f"  · {line}", file=sys.stderr)
        return 1

    print("✅ self-test: parts A, C, D, E, F and G each fire on a broken fixture")
    return 0


# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--static", action="store_true", help="parts A, C, D, E, F, G")
    parser.add_argument("--can-fail", action="store_true", help="part B")
    parser.add_argument("--tasks-exist", action="store_true", help="parts A2 and A3")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the static parts detect a deliberately broken repository",
    )
    args = parser.parse_args()

    if not (args.static or args.can_fail or args.tasks_exist or args.self_test):
        parser.error("choose at least one of --static, --can-fail, --tasks-exist, --self-test")

    if args.self_test:
        return run_self_test()

    try:
        gates = read_registry()
        findings: list[str] = []

        if args.static:
            findings += part_a()
            findings += part_c()
            findings += part_d()
            findings += part_e()
            findings += part_f(gates)
            findings += part_g()
            print("  ·  Parts A, C, D, E, F, G: checked")

        if args.can_fail:
            findings += part_b(gates)

        if args.tasks_exist:
            findings += part_a2()
            findings += part_a3()

    except CannotRun as exc:
        print(f"❌ cannot run the check: {exc}", file=sys.stderr)
        print(
            "   Failing loudly rather than skipping: a check that reports success when it\n"
            "   could not run is the defect class this gate exists to prevent.",
            file=sys.stderr,
        )
        return 2

    if findings:
        print("", file=sys.stderr)
        for finding in findings:
            print(f"❌ {finding}", file=sys.stderr)
        return 1

    print("✅ gate wiring OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())