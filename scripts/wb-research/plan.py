#!/usr/bin/env python3
"""Run an approved research plan: one captured step at a time.

`capture.py` records one named step of an experiment. `plan.py` is the thin
loop around it: it reads the plan of an investigation, runs the steps that are
not done yet, and writes the state of every step back into the plan.

Why a plan file at all: the experiment has to be inspectable *before* it runs,
and a run has to be resumable *after* it stops. Both live in one file —
`devtools/wb-research/investigations/<id>/plan.yaml` — that a human reads
before approving it and that the agent reads to know where it stopped.

The boundaries are not repeated here. `capture.py` refuses the page actions
that could change the site, and the redactor is the only thing that writes a
sanitized fixture. This script runs steps, records what happened and stops
where it must stop.

Usage:
    plan.py run --investigation <id> [--settle <seconds>]
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CAPTURE = HERE / "capture.py"
INVESTIGATIONS = REPO_ROOT / "devtools" / "wb-research" / "investigations"
RAW_ROOT = Path.home() / ".cache" / "wb-research"

# The states of a step. `planned` is the only one the runner acts on, so a run
# that was interrupted resumes at the first step that did not finish.
PLANNED = "planned"
DONE = "done"
FAILED = "failed"
NEEDS_HUMAN = "needs-human"

# The states of the plan itself. A plan that was not approved is not run: the
# approval is what makes the scope of the experiment a decision and not a
# guess.
APPROVED = "approved"
RUNNING = "running"

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_UNAPPROVED_API = 2


def plan_path(investigation: str) -> Path:
    """The plan file of an investigation."""
    return INVESTIGATIONS / investigation / "plan.yaml"


def load_plan(path: Path) -> dict:
    """Read a plan, or say what is wrong with it."""
    if not path.is_file():
        raise SystemExit(f"the plan is not there: {path}")
    plan = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(plan, dict):
        raise SystemExit(f"the plan is not a mapping: {path}")
    return plan


def save_plan(path: Path, plan: dict) -> None:
    """Write the plan back, keeping the order of the file.

    The file is written with `yaml.safe_dump` and no comments: the plan is a
    record of what was decided and what happened, and a note belongs in a
    field, not in a comment that the next write drops.
    """
    path.write_text(
        yaml.safe_dump(plan, sort_keys=False, allow_unicode=True, default_flow_style=False, width=100),
        encoding="utf-8",
    )


def check_plan(plan: dict, investigation: str) -> list[dict]:
    """The steps of the plan, checked at the door.

    A step without a name has no evidence file to point at, and a step without
    a command does nothing, so both are refused here rather than in the middle
    of a run.
    """
    if plan.get("id") != investigation:
        raise SystemExit(f"the plan names another investigation: {plan.get('id')}")
    steps = plan.get("steps")
    if not isinstance(steps, list) or not steps:
        raise SystemExit("the plan has no steps")
    names: set[str] = set()
    for step in steps:
        if not isinstance(step, dict):
            raise SystemExit("a step of the plan is not a mapping")
        name = step.get("name")
        command = step.get("command")
        if not isinstance(name, str) or not name:
            raise SystemExit("a step of the plan has no name")
        if name in names:
            raise SystemExit(f"two steps of the plan are named {name}")
        names.add(name)
        if not isinstance(command, str) or not command.strip():
            raise SystemExit(f"the step {name} has no command")
    return steps


def run_step(investigation: str, step: dict, settle: float) -> tuple[str, str]:
    """Run one step of the plan: its state and what the capture printed.

    The state follows the exit code of the capture: a step that was recorded
    is done, a step whose action failed is failed, and a call to an origin
    that is neither approved nor marked as noise needs a human — the origin
    is the operator's decision, not the runner's.
    """
    name = step["name"]
    command = shlex.split(step["command"])
    argv = [
        sys.executable,
        str(CAPTURE),
        "step",
        "--investigation",
        investigation,
        "--name",
        name,
        "--settle",
        str(step.get("settle", settle)),
    ]
    # An origin the step is allowed to reach. The approval is in the plan, so
    # it is inspectable, and it holds for this step only — the shared config
    # keeps the scope of every other run.
    for host in step.get("allow_hosts", []):
        argv += ["--allow-host", host]
    argv += ["--", *command]
    result = subprocess.run(argv, capture_output=True, text=True, check=False)
    output = (result.stdout or "") + (result.stderr or "")
    if result.returncode == EXIT_OK:
        return DONE, output.strip()
    if result.returncode == EXIT_UNAPPROVED_API:
        return NEEDS_HUMAN, output.strip()
    return FAILED, output.strip()


def note_of(investigation: str, name: str, state: str) -> str:
    """A short note about a step, taken from the report of the capture.

    The report of the capture is in the raw cache and says what was recorded,
    what was kept and which origin was not approved. The plan keeps the short
    version of it: the plan is read by a person, and a page of output is not
    a note.
    """
    report = RAW_ROOT / investigation / f"{name}.report.json"
    if not report.is_file():
        return ""
    try:
        data = json.loads(report.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ""
    hosts = data.get("unapprovedApiHosts") or []
    if hosts:
        return f"an API call went to {', '.join(hosts)}, which is neither approved nor marked as noise"
    if state == DONE:
        return f"recorded {data.get('recorded', 0)} requests, kept {data.get('kept', 0)}"
    return ""


def run(investigation: str, settle: float) -> int:
    """Run the steps of the plan that are not done yet."""
    path = plan_path(investigation)
    plan = load_plan(path)
    steps = check_plan(plan, investigation)

    status = plan.get("status")
    if status not in {APPROVED, RUNNING}:
        raise SystemExit(f"the plan is not approved (status: {status})")

    plan["status"] = RUNNING
    save_plan(path, plan)

    stopped = False
    for step in steps:
        state = step.get("state", PLANNED)
        if state == DONE:
            continue
        if state != PLANNED:
            # A step that failed or that needs a human is where the run
            # stopped. It stays until a human says it may go on.
            print(f"{step['name']}: {state} — {step.get('note', '')}")
            stopped = True
            break
        new_state, output = run_step(investigation, step, settle)
        step["state"] = new_state
        step["note"] = note_of(investigation, step["name"], new_state)
        save_plan(path, plan)
        if output:
            print(output)
        if new_state != DONE:
            stopped = True
            break

    print("the state of the plan:")
    for step in steps:
        print(f"  {step['name']}: {step.get('state', PLANNED)}")
    return EXIT_FAILED if stopped else EXIT_OK


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)

    run_parser = sub.add_parser("run", help="run the steps of the plan that are not done yet")
    run_parser.add_argument("--investigation", required=True, help="the investigation id")
    run_parser.add_argument("--settle", type=float, default=8.0, help="seconds a step waits for the answers")

    args = parser.parse_args(argv)
    return run(args.investigation, args.settle)


if __name__ == "__main__":
    raise SystemExit(main())
