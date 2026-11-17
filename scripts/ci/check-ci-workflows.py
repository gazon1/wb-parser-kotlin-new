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
4. **The nightly run and its issue** — some workflow has a `schedule` trigger
   with a `cron`, and some workflow opens a GitHub issue. A scheduled run that
   fails silently is a scheduled run nobody reads.
5. **The wait list of the alert job** — the job that reports a failed nightly
   run waits for every other job of the pipeline. That list is a mirror of the
   pipeline, and a mirror goes stale the moment a job is added: the report
   would then be made before the new job finished, and the issue would say
   "nothing failed" while something did. The report itself comes from the
   run's own API; this list is only about *when* to report.
6. **The generated catalog models** — the `static` job regenerates them and
   fails on a diff of the generated file. The check names all three parts and
   requires them in one step of that job: the generator, `git diff --exit-code`,
   and the file the diff is about. A search for the script name anywhere in the
   file would be satisfied by a step that regenerates and throws the result
   away, which is the failure this check exists to prevent, one level up.

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
            # The publication is checked against the API, because the action
            # reports a rejected publication as a warning: the step is green
            # and no score appears.
            "api.scorecard.dev",
        ],
    },
    "lint-autofix.yml": {
        "why": "the linters' own corrections are applied without a human",
        "needles": [
            "scripts/ci/lint-autofix.sh",
        ],
    },
    "camoufox-smoke.yml": {
        "why": "the real Camoufox launch contract is checked on a machine with the pinned build",
        "needles": [
            "workflow_dispatch:",
            "scripts/camoufox.sh install",
            "CamoufoxSmokeTest",
        ],
    },
}

#: The shared corrector. Both surfaces call it: the workflow above and the
#: local recipe (`just lint::autofix`).
AUTOFIX_SCRIPT = "scripts/ci/lint-autofix.sh"

#: The checker that asks the upstream repository whether a pinned SHA is a
#: commit. It needs the network, so it runs in the pipeline rather than in the
#: static registry — and the gate below checks that the pipeline calls it, so
#: it cannot be deleted unnoticed.
PIN_CHECKER = "scripts/ci/check-action-pins.py"

#: The generator behind the committed Python models, and the recipe that runs it.
#: The pipeline regenerates the file and fails on a diff, because that is the only
#: proof a committed generated file still describes the contract it was made from.
MODEL_GENERATOR = "scripts/parser/generate-models.sh"

#: The generated file the diff is about, and the job both commands must sit in.
#: Regenerating without diffing proves nothing, and a step in a job that never runs
#: proves less — so the check names all three parts, not just the script.
MODEL_OUTPUT = "scripts/parser/catalog_models.py"
MODEL_JOB = "static"

#: The pipeline, and the job inside it that reports a failed nightly run.
PIPELINE = "ci.yml"
ALERT_JOB = "nightly-alert"


# ---------------------------------------------------------------------------
# Reading YAML without a YAML library
# ---------------------------------------------------------------------------
#
# The static gate runs on a bare `actions/setup-python`, with no project
# dependencies installed, so PyYAML is not available. What is needed here is
# narrower than YAML anyway: a comment-free text, the names of the jobs, and
# one `needs:` list.


def strip_yaml_comments(text: str) -> str:
    """The text without its comments.

    A `#` starts a comment unless it is inside a quoted string. The gate looks
    for lines like `security-events: write`, and a commented-out line that
    still contains the text would satisfy the check — the check would be
    satisfied by its own documentation, which is the quietest way a gate can
    stop gating. The meta-gate strips comments for the same reason
    (scripts/check-gate-wiring.py, `code_lines`).
    """
    out: list[str] = []
    for line in text.splitlines():
        in_single = False
        in_double = False
        cut: int | None = None
        i = 0
        while i < len(line):
            ch = line[i]
            if ch == "'" and not in_double:
                in_single = not in_single
            elif ch == '"' and not in_single:
                in_double = not in_double
            elif ch == "#" and not in_single and not in_double and (i == 0 or line[i - 1] in " \t"):
                # A comment starts the line, or follows whitespace. A `#` in
                # the middle of a word is part of that word.
                cut = i
                break
            i += 1
        out.append(line[:cut] if cut is not None else line)
    return "\n".join(out)


def job_names(text: str) -> list[str]:
    """The names of the jobs in a workflow.

    A job is a key at two spaces of indentation under `jobs:`. This is not a
    YAML parser; it is a reader that knows one thing about these workflows,
    and the caller says so when it cannot find what it needs.
    """
    names: list[str] = []
    in_jobs = False
    for line in text.splitlines():
        if re.match(r"^jobs:\s*$", line):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if re.match(r"^[A-Za-z]", line):
            # A new top-level key ends the block.
            break
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            names.append(match.group(1))
    return names


def job_body(text: str, job: str) -> list[str] | None:
    """The lines of a job, `None` when there is no such job.

    A check that finds a command anywhere in the file cannot tell whether the job
    that holds it ever runs, or whether the command is part of the step it looks
    like. This narrows the search to one job, which is the smallest thing that
    makes "the pipeline does X" a statement about a job rather than about a file.
    """
    lines = text.splitlines()
    start: int | None = None
    for index, line in enumerate(lines):
        if re.match(rf"^  {re.escape(job)}:\s*$", line):
            start = index
            break
    if start is None:
        return None
    body: list[str] = []
    for line in lines[start + 1 :]:
        if re.match(r"^[A-Za-z]", line) or re.match(r"^  [A-Za-z0-9_-]+:\s*$", line):
            break  # a new top-level key, or the next job
        body.append(line)
    return body


def job_needs(text: str, job: str) -> list[str] | None:
    """The `needs:` of a job, `[]` when it has none, `None` when it has no such job."""
    body = job_body(text, job)
    if body is None:
        return None
    for line in body:
        match = re.match(r"^\s+needs:\s*\[(.*)\]\s*$", line)
        if match:
            return [name.strip() for name in match.group(1).split(",") if name.strip()]
    return []


# ---------------------------------------------------------------------------
# The checks
# ---------------------------------------------------------------------------


def workflow_paths(root: Path) -> list[Path]:
    return sorted((root / ".github" / "workflows").glob("*.yml"))


def read_workflows(root: Path) -> dict[str, str]:
    """Every workflow's comment-free text. A missing file is an empty text."""
    texts: dict[str, str] = {}
    for path in workflow_paths(root):
        texts[path.name] = strip_yaml_comments(path.read_text(encoding="utf-8"))
    return texts


def check_required_files(root: Path) -> list[str]:
    findings: list[str] = []
    for name, spec in REQUIRED.items():
        path = root / ".github" / "workflows" / name
        if not path.exists():
            findings.append(
                f"{name} does not exist. {spec['why']} — and a guarantee that "
                "silently stopped running is indistinguishable from one that never ran."
            )
            continue
        text = strip_yaml_comments(path.read_text(encoding="utf-8"))
        for needle in spec["needles"]:
            if needle not in text:
                findings.append(
                    f"{name} does not mention `{needle}`. {spec['why']}, and this is the line that does it."
                )
    return findings


def check_autofix_script(root: Path) -> list[str]:
    script = root / AUTOFIX_SCRIPT
    if script.exists():
        return []
    return [
        f"{AUTOFIX_SCRIPT} does not exist, but the auto-fix workflow calls it. "
        "The corrector is the deliverable; without it the workflow fails on the runner."
    ]


def check_action_pins_are_checked(root: Path) -> list[str]:
    """The pipeline checks that every pinned SHA is a commit, and the checker exists.

    A 40-character SHA is the identifier of *some* git object, and an annotated
    tag is a git object too. Pinning an action to the SHA of a tag object looks
    like the strongest form of pinning and is not: the action runs, and the
    Scorecard publication API refuses the result, because the SHA is not a
    commit of the action's repository. The run is green and no score appears.

    The question cannot be answered from the shape of the SHA, so it is asked
    of the upstream repository — which needs the network, which is why the
    check runs in the pipeline and not in the static registry. A checker that
    lives nowhere is dead code, and a checker that lives only in the pipeline
    can be deleted without anything noticing. Both are findings here.
    """
    findings: list[str] = []
    checker = root / PIN_CHECKER
    if not checker.exists():
        findings.append(
            f"{PIN_CHECKER} does not exist. The pipeline calls it to ask the upstream "
            "repository whether each pinned SHA is a commit, and without it a pin to a "
            "tag object looks exactly like a pin to a commit — the action runs, the "
            "publication is refused, and the run is green."
        )
    texts = read_workflows(root)
    called = any(PIN_CHECKER in text for text in texts.values())
    if not called:
        findings.append(
            f"no workflow calls {PIN_CHECKER}. A checker that nothing calls is dead "
            "code, and the pin it checks is the one thing a green run cannot tell you "
            "is wrong."
        )
    return findings


def job_steps(text: str, job: str) -> list[str] | None:
    """The text of each step of a job. `None` when there is no such job."""
    body = job_body(text, job)
    if body is None:
        return None
    steps: list[list[str]] = []
    for line in body:
        if re.match(r"^ {4,6}- ", line):
            steps.append([line])
        elif steps:
            steps[-1].append(line)
    return ["\n".join(step) for step in steps]


def step_regenerates(step: str) -> bool:
    return MODEL_GENERATOR in step


def step_diffs_output(step: str) -> bool:
    """The step fails the job when the generated file changed.

    `git diff --exit-code` is what makes the job red, and the file it names is what
    makes it about the generated models rather than about something else.
    """
    return "git diff --exit-code" in step and MODEL_OUTPUT in step


def check_models_are_regenerated(root: Path) -> list[str]:
    """The pipeline regenerates the committed models and fails on a diff, and the
    generator exists.

    The offline gate (`scripts/ci/check-catalog-models.py`) compares the contract's
    hash recorded in the file, which catches an edited contract. It cannot catch a
    generator whose flags changed, or a hand-edit that kept the marker. Re-running the
    generator and diffing is the only check that covers both, and it needs PyPI — so
    it lives in the pipeline, and this is what keeps it from being deleted.

    Three parts are checked, and in the job that runs: the generator, the
    `git diff --exit-code`, and the file the diff is about — in that order. Looking for
    the script name anywhere in the file would be satisfied by a workflow that
    regenerates the file and throws the result away, which is the failure this check
    exists to prevent, one level up.

    The two commands may be separate steps: a job fails on the first failing step, and
    both orders that put regeneration first are the same guarantee. What is not
    allowed is a diff before the regeneration, which compares the file with its own
    previous commit and would stay green on a contract edit.
    """
    findings: list[str] = []
    generator = root / MODEL_GENERATOR
    if not generator.exists():
        findings.append(
            f"{MODEL_GENERATOR} does not exist. The pipeline runs it and fails on a "
            "diff; without it the committed models are a file nobody can show is current."
        )

    texts = read_workflows(root)
    pipeline = texts.get(PIPELINE)
    if pipeline is None:
        return [*findings, f"{PIPELINE} does not exist, so nothing regenerates the models."]

    steps = job_steps(pipeline, MODEL_JOB)
    if steps is None:
        return [
            *findings,
            f"{PIPELINE} has no `{MODEL_JOB}` job. The regeneration runs there, "
            "and a step in a job that does not exist proves nothing.",
        ]

    regenerating = [index for index, step in enumerate(steps) if step_regenerates(step)]
    diffing = [index for index, step in enumerate(steps) if step_diffs_output(step)]

    if not regenerating and not diffing:
        findings.append(
            f"the `{MODEL_JOB}` job of {PIPELINE} does not regenerate {MODEL_OUTPUT} and "
            "fail on a diff. A generated file that nothing regenerates in CI is a file "
            "that drifts from its contract in silence."
        )
    elif not diffing:
        findings.append(
            f"the `{MODEL_JOB}` job of {PIPELINE} runs {MODEL_GENERATOR} without failing "
            f"on a diff of {MODEL_OUTPUT}. Regenerating and discarding the result proves "
            "nothing: the check that matters is `git diff --exit-code` on the file."
        )
    elif not regenerating:
        findings.append(
            f"the `{MODEL_JOB}` job of {PIPELINE} diffs {MODEL_OUTPUT} but never runs "
            f"{MODEL_GENERATOR}. A diff against a file nobody regenerated is a comparison "
            "with the previous commit, not with the contract."
        )
    elif min(diffing) < min(regenerating):
        findings.append(
            f"the `{MODEL_JOB}` job of {PIPELINE} diffs {MODEL_OUTPUT} before it runs "
            f"{MODEL_GENERATOR}. The diff must come after the regeneration, or it compares "
            "the file with itself."
        )
    return findings


def check_top_level_permissions(root: Path) -> list[str]:
    """Every workflow declares what a job gets unless it says otherwise.

    A workflow with no top-level `permissions:` leaves the grant to the
    repository's default — a decision nobody made, and one that can change
    under the repository. The Scorecard reports it as "no topLevel permission
    defined", and the finding is correct: a job added later without a
    declaration of its own inherits everything the caller has.
    """
    findings: list[str] = []
    for path in workflow_paths(root):
        text = strip_yaml_comments(path.read_text(encoding="utf-8"))
        if not re.search(r"^permissions:\s*$", text, re.M):
            findings.append(
                f"{path.name} declares no top-level permissions. A job that does not "
                "declare its own inherits the caller's grant, and a workflow that says "
                "nothing about permissions leaves the grant to the repository's default "
                "— a decision nobody made."
            )
    return findings


def check_scheduled_run(root: Path) -> list[str]:
    """The scheduled run exercises the pipeline, and the pipeline is defined once.

    Two rules, and the second is the interesting one:

    1. Some workflow runs on a schedule, with a time, and that workflow
       *calls* the pipeline. A schedule that does not call the pipeline is a
       schedule for something else.
    2. The pipeline itself has no schedule of its own. A schedule inside the
       pipeline is a copy of the entry point: the next job added to the
       pipeline would have to be added to the copy too, and the copy is what
       goes stale. A schedule that calls the pipeline has no jobs of its own
       to fall behind.

    The other workflows have schedules of their own — CodeQL and Scorecard
    run weekly — and this check does not touch them.
    """
    findings: list[str] = []
    texts = read_workflows(root)
    scheduled = [name for name, text in texts.items() if re.search(r"^\s*schedule:\s*$", text, re.M)]
    if not scheduled:
        findings.append(
            "no workflow has a `schedule` trigger. The tests are supposed to run on "
            "their own, and not only when somebody remembers to push."
        )
        return findings
    if not any(re.search(r"^\s*-\s*cron:\s*[\"']?\S+", text, re.M) for text in texts.values()):
        findings.append(
            "a workflow has a `schedule` trigger without a `cron` entry — GitHub rejects a schedule that has no time."
        )
    pipeline = texts.get(PIPELINE, "")
    if re.search(r"^\s*schedule:\s*$", pipeline, re.M):
        findings.append(
            f"{PIPELINE} has a `schedule` trigger of its own. The schedule is an "
            "entry point that calls the pipeline; a schedule inside the pipeline is "
            "a second copy of the entry point, and the copy goes stale."
        )
    if not any("uses: ./.github/workflows/" + PIPELINE in texts[name] for name in scheduled):
        findings.append(
            "no scheduled workflow calls the pipeline. The scheduled run must be an "
            f"entry point that calls {PIPELINE} — otherwise the schedule runs "
            "something that is not the pipeline, and the two drift apart."
        )
    return findings


def check_failure_becomes_an_issue(root: Path) -> list[str]:
    """A failed scheduled run opens an issue."""
    texts = read_workflows(root)
    if any("gh issue create" in text for text in texts.values()):
        return []
    return [
        "no workflow opens an issue. A scheduled failure has to land somewhere a "
        "person will see it, not in a scrollback buffer."
    ]


def check_wait_list(root: Path) -> list[str]:
    """The alert job waits for every other job of the pipeline.

    The report is the run's own API, so it cannot be stale. The wait list
    says *when* the report is made, and it is a mirror of the pipeline: add a
    job and forget the list, and the report is made while that job is still
    running.
    """
    path = root / ".github" / "workflows" / PIPELINE
    if not path.exists():
        return [
            f".github/workflows/{PIPELINE} does not exist — the pipeline and the "
            "issue that carries its failure have nowhere to live."
        ]
    text = strip_yaml_comments(path.read_text(encoding="utf-8"))
    jobs = job_names(text)
    if not jobs:
        return [f".github/workflows/{PIPELINE} has no readable `jobs:` block."]

    needs = job_needs(text, ALERT_JOB)
    if needs is None:
        return [
            f".github/workflows/{PIPELINE} has no `{ALERT_JOB}` job. A failed scheduled run has nobody to report it."
        ]
    missing = [job for job in jobs if job != ALERT_JOB and job not in needs]
    if missing:
        return [
            f"the `{ALERT_JOB}` job does not wait for: {', '.join(missing)}. The "
            "report is made before those jobs finish, and an issue that says "
            '"nothing failed" while something did is worse than no issue.'
        ]
    return []


def check_actions_are_pinned(root: Path) -> list[str]:
    """Every action is pinned to a commit, not to a tag.

    A tag is a name that can be moved: `actions/checkout@v4` resolves to
    whatever the owner points it at today, and the person who pushes the tag
    decides what runs inside the repository's permissions. A commit is a
    name that cannot move.

    The version stays in a comment, so dependabot still knows what to update
    and a reader still knows what is running.

    A local reference — `uses: ./.github/workflows/ci.yml` — is a workflow
    of this repository, and there is nothing to pin.
    """
    findings: list[str] = []
    pattern = re.compile(r"uses:\s+([A-Za-z0-9._-]+/[A-Za-z0-9._/-]+)@([^\s#]+)")
    for path in workflow_paths(root):
        text = strip_yaml_comments(path.read_text(encoding="utf-8"))
        for match in pattern.finditer(text):
            action, ref = match.group(1), match.group(2)
            if re.fullmatch(r"[0-9a-f]{40}", ref):
                continue
            findings.append(
                f"{path.name} uses `{action}@{ref}` — a tag, not a commit. A tag can be "
                "moved by whoever owns it, and what runs inside this repository's "
                "permissions should not be decided by a name. Pin it to the commit "
                "and keep the version in a comment."
            )
    return findings


def check_watchdog(root: Path) -> list[str]:
    """Something watches the nightly, and it watches something that exists.

    The nightly reports its own failures. Nobody reports its *absence*: a
    scheduled workflow that never runs is not a failing workflow, the badge
    stays green, and the repository quietly stops being checked. A watchdog
    closes that hole, and it is the only thing that can.

    Two rules: some workflow must watch the nightly, and every workflow that
    is watched must exist — a watchdog that watches a name nobody publishes
    is a watchdog that reports nothing, in exactly the way it was written to
    prevent.
    """
    findings: list[str] = []
    texts = read_workflows(root)
    watched = {name: set(re.findall(r"actions/workflows/([A-Za-z0-9._-]+\.yml)", text)) for name, text in texts.items()}
    if not any("nightly.yml" in names for names in watched.values()):
        findings.append(
            "no workflow watches the nightly run. A scheduled workflow that never "
            "runs reports nothing — there is no failure to report, there is no run — "
            "and the badge stays green while the repository stops being checked."
        )
    for name, names in watched.items():
        for target in sorted(names):
            if not (root / ".github" / "workflows" / target).exists():
                findings.append(
                    f"{name} watches `{target}`, which does not exist. A watchdog that "
                    "watches a name nobody publishes reports nothing — in exactly the "
                    "way it was written to prevent."
                )
    return findings


def check(root: Path) -> list[str]:
    """Run every check against `root`. Used by the self-test on a fixture."""
    return (
        check_required_files(root)
        + check_autofix_script(root)
        + check_action_pins_are_checked(root)
        + check_models_are_regenerated(root)
        + check_top_level_permissions(root)
        + check_scheduled_run(root)
        + check_failure_becomes_an_issue(root)
        + check_wait_list(root)
        + check_actions_are_pinned(root)
        + check_watchdog(root)
    )


# ---------------------------------------------------------------------------
# Positive control
# ---------------------------------------------------------------------------

GOOD_WORKFLOWS = {
    "codeql.yml": (
        "name: CodeQL\n"
        "on:\n"
        "  schedule:\n"
        '    - cron: "17 3 * * 1"\n'
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  analyze:\n"
        "    permissions:\n"
        "      security-events: write\n"
        "    steps:\n"
        "      - uses: github/codeql-action/init@0123456789abcdef0123456789abcdef01234567\n"
        "        with:\n"
        "          languages: java-kotlin\n"
        "      - uses: github/codeql-action/analyze@0123456789abcdef0123456789abcdef01234567\n"
    ),
    "scorecard.yml": (
        "name: Scorecard\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  analysis:\n"
        "    permissions:\n"
        "      id-token: write\n"
        "    steps:\n"
        "      - uses: ossf/scorecard-action@89abcdef0123456789abcdef0123456789abcdef\n"
        "        with:\n"
        "          results_file: results.sarif\n"
        "      - run: curl -fsS https://api.scorecard.dev/projects/github.com/x/y\n"
    ),
    "lint-autofix.yml": (
        "name: Lint auto-fix\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  autofix:\n"
        "    steps:\n"
        "      - run: bash scripts/ci/lint-autofix.sh\n"
    ),
    "camoufox-smoke.yml": (
        "name: Camoufox smoke\n"
        "on:\n"
        "  workflow_dispatch:\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  smoke:\n"
        "    steps:\n"
        "      - run: scripts/camoufox.sh install\n"
        "      - run: ./gradlew :browser:test --tests '*CamoufoxSmokeTest*'\n"
    ),
    "ci.yml": (
        "name: CI\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  static:\n"
        "    steps:\n"
        "      - run: echo static\n"
        "      - run: python3 scripts/ci/check-action-pins.py\n"
        "      - run: |\n"
        "          bash scripts/parser/generate-models.sh\n"
        "          git diff --exit-code -- scripts/parser/catalog_models.py\n"
        "  build:\n"
        "    steps:\n"
        "      - run: echo build\n"
        "  nightly-alert:\n"
        "    needs: [static, build]\n"
        "    steps:\n"
        "      - run: gh issue create --title nightly\n"
    ),
    "nightly.yml": (
        "name: CI (nightly)\n"
        "on:\n"
        "  schedule:\n"
        '    - cron: "0 3 * * *"\n'
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  nightly:\n"
        "    uses: ./.github/workflows/ci.yml\n"
    ),
    "watchdog.yml": (
        "name: Nightly watchdog\n"
        "on:\n"
        "  schedule:\n"
        '    - cron: "0 9 * * 1"\n'
        "permissions:\n"
        "  actions: read\n"
        "  contents: read\n"
        "  issues: write\n"
        "jobs:\n"
        "  watchdog:\n"
        "    steps:\n"
        "      - run: gh api repos/x/actions/workflows/nightly.yml/runs\n"
    ),
}


def write_fixture(
    root: Path,
    workflows: dict[str, str],
    with_script: bool = True,
    with_checker: bool = True,
    with_generator: bool = True,
) -> None:
    workflows_dir = root / ".github" / "workflows"
    workflows_dir.mkdir(parents=True, exist_ok=True)
    # The cases run one after another in one directory. A case that removes a
    # workflow has to remove the file for real — otherwise the file of the
    # previous case is still there, and the case tests nothing.
    for stale in workflows_dir.glob("*.yml"):
        stale.unlink()
    for name, content in workflows.items():
        (workflows_dir / name).write_text(content, encoding="utf-8")
    script = root / AUTOFIX_SCRIPT
    script.parent.mkdir(parents=True, exist_ok=True)
    if with_script:
        script.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    elif script.exists():
        script.unlink()
    checker = root / PIN_CHECKER
    if with_checker:
        checker.write_text("#!/usr/bin/env python3\nexit 0\n", encoding="utf-8")
    elif checker.exists():
        checker.unlink()
    generator = root / MODEL_GENERATOR
    if with_generator:
        generator.parent.mkdir(parents=True, exist_ok=True)
        generator.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    elif generator.exists():
        generator.unlink()


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
                "self-test FAILED — the check reports findings on a tree that satisfies every requirement:",
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
                    "codeql.yml": GOOD_WORKFLOWS["codeql.yml"].replace("security-events: write", "contents: read"),
                },
                "expect": "security-events: write",
            },
            "the permission is only in a comment": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "codeql.yml": GOOD_WORKFLOWS["codeql.yml"].replace(
                        "      security-events: write", "      # security-events: write"
                    ),
                },
                "expect": "security-events: write",
            },
            "scorecard.yml lost the id-token permission": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "scorecard.yml": GOOD_WORKFLOWS["scorecard.yml"].replace("id-token: write", "contents: read"),
                },
                "expect": "id-token: write",
            },
            "an action is pinned to a tag": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "codeql.yml": GOOD_WORKFLOWS["codeql.yml"].replace(
                        "github/codeql-action/init@0123456789abcdef0123456789abcdef01234567",
                        "github/codeql-action/init@v4",
                    ),
                },
                "expect": "a tag, not a commit",
            },
            "a workflow declares no top-level permissions": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "nightly.yml": GOOD_WORKFLOWS["nightly.yml"].replace("permissions:\n  contents: read\n", ""),
                },
                "expect": "declares no top-level permissions",
            },
            "nobody checks that the pins are commits": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "      - run: python3 scripts/ci/check-action-pins.py\n", ""
                    ),
                },
                "expect": "no workflow calls",
            },
            "the pin checker does not exist": {
                "workflows": GOOD_WORKFLOWS,
                "checker": False,
                "expect": "does not exist",
            },
            "nobody regenerates the catalog models in CI": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n",
                        "",
                    ),
                },
                "expect": "does not regenerate",
            },
            "the generator runs and the diff is thrown away": {
                # The defect this check exists to catch: the script name is still in
                # the file, so a search for the name alone would pass.
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n", ""
                    ),
                },
                "expect": "without failing on a diff",
            },
            "the diff is about another file": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "git diff --exit-code -- scripts/parser/catalog_models.py",
                        "git diff --exit-code -- README.md",
                    ),
                },
                "expect": "without failing on a diff",
            },
            "the diff runs but nothing regenerates first": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n",
                        "      - run: git diff --exit-code -- scripts/parser/catalog_models.py\n",
                    ),
                },
                "expect": "but never runs",
            },
            "the diff runs before the regeneration": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n",
                        "      - run: git diff --exit-code -- scripts/parser/catalog_models.py\n"
                        "      - run: bash scripts/parser/generate-models.sh\n",
                    ),
                },
                "expect": "before it runs",
            },
            "the regeneration and the diff are separate steps": {
                # Allowed on purpose: the job fails on the first failing step, so
                # both orders that put regeneration first are the same guarantee.
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n",
                        "      - run: bash scripts/parser/generate-models.sh\n"
                        "      - run: git diff --exit-code -- scripts/parser/catalog_models.py\n",
                    ),
                },
                "expect": None,
            },
            "the regeneration is not in the static job": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"]
                    .replace(
                        "      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n",
                        "",
                    )
                    .replace(
                        "  build:\n    steps:\n      - run: echo build\n",
                        "  build:\n    steps:\n      - run: |\n"
                        "          bash scripts/parser/generate-models.sh\n"
                        "          git diff --exit-code -- scripts/parser/catalog_models.py\n"
                        "      - run: echo build\n",
                    ),
                },
                "expect": "does not regenerate",
            },
            "the static job is gone": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace("  static:\n", "  checks:\n"),
                },
                "expect": "has no `static` job",
            },
            "the model generator does not exist": {
                "workflows": GOOD_WORKFLOWS,
                "generator": False,
                "expect": "does not exist",
            },
            "the publication is not checked against the API": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "scorecard.yml": GOOD_WORKFLOWS["scorecard.yml"].replace(
                        "      - run: curl -fsS https://api.scorecard.dev/projects/github.com/x/y\n",
                        "",
                    ),
                },
                "expect": "api.scorecard.dev",
            },
            "the watchdog watches a workflow that does not exist": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "watchdog.yml": GOOD_WORKFLOWS["watchdog.yml"].replace(
                        "workflows/nightly.yml/runs", "workflows/nightly-v2.yml/runs"
                    ),
                },
                "expect": "which does not exist",
            },
            "nothing watches the nightly": {
                "workflows": {name: text for name, text in GOOD_WORKFLOWS.items() if name != "watchdog.yml"},
                "expect": "no workflow watches the nightly",
            },
            "the auto-fix script is gone": {
                "workflows": GOOD_WORKFLOWS,
                "script": False,
                "expect": "does not exist",
            },
            "no workflow has a schedule": {
                "workflows": {
                    name: text.replace("schedule:", "pull_request:") for name, text in GOOD_WORKFLOWS.items()
                },
                "expect": "schedule",
            },
            "the pipeline has a schedule of its own": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "name: CI\n", 'name: CI\non:\n  schedule:\n    - cron: "0 4 * * *"\n'
                    ),
                },
                "expect": "schedule",
            },
            "the scheduled run does not call the pipeline": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "nightly.yml": GOOD_WORKFLOWS["nightly.yml"].replace(
                        "    uses: ./.github/workflows/ci.yml\n",
                        "    runs-on: ubuntu-latest\n    steps:\n      - run: echo nightly\n",
                    ),
                },
                "expect": "calls the pipeline",
            },
            "ci.yml never opens an issue": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace("gh issue create", "echo"),
                },
                "expect": "opens an issue",
            },
            "the alert job waits for too little": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace("needs: [static, build]", "needs: [static]"),
                },
                "expect": "does not wait for",
            },
            "the alert job is gone": {
                "workflows": {
                    **GOOD_WORKFLOWS,
                    "ci.yml": GOOD_WORKFLOWS["ci.yml"].replace(
                        "  nightly-alert:\n    needs: [static, build]\n    steps:\n"
                        "      - run: gh issue create --title nightly\n",
                        "  other-job:\n    steps:\n      - run: gh issue create --title nightly\n",
                    ),
                },
                "expect": "nightly-alert",
            },
        }

        for name, case in cases.items():
            write_fixture(
                root,
                case.get("workflows", GOOD_WORKFLOWS),
                with_script=case.get("script", True),
                with_checker=case.get("checker", True),
                with_generator=case.get("generator", True),
            )
            findings = check(root)
            # `expect: None` is a case that must stay quiet — the rule it exercises
            # permits what the fixture does, and a check that flagged it would be
            # stricter than the guarantee it states.
            if case["expect"] is None:
                if findings:
                    print(
                        f"self-test FAILED — the check reports findings where the rule allows it: {name}",
                        file=sys.stderr,
                    )
                    for line in findings:
                        print(f"  · {line}", file=sys.stderr)
                    return 1
                continue
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
