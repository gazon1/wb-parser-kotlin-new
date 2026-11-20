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
    plan.py approve --investigation <id>   # the operator approves the plan
    plan.py run --investigation <id>
"""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import importlib.util
import json
import math
import re
import shlex
import subprocess
import sys
import uuid
from contextlib import contextmanager
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CAPTURE = HERE / "capture.py"
REDACTOR = HERE / "redact.py"
CONFIG_PATH = REPO_ROOT / "devtools" / "wb-research" / "capture-config.json"
INVESTIGATIONS = REPO_ROOT / "devtools" / "wb-research" / "investigations"
RAW_ROOT = Path.home() / ".cache" / "wb-research"

_REDACTOR = None
_CAPTURE = None

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

# How long a step waits for the answers, when the plan does not say. It is the
# wait of the plan, not of the run: the run does not get to shorten it.
DEFAULT_SETTLE = 8.0

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_UNAPPROVED_API = 2

# The id of an investigation becomes a directory name in the repository and in
# the raw cache, so it has to be one name and nothing else: the same rule as
# in `capture.py`.
INVESTIGATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def safe_investigation(value: str) -> str:
    """The id of an investigation, checked to be a single safe path component.

    The id names a directory in the repository. A name that carries a
    separator or a `..` would point at another directory than the one the
    operator named, and the runner writes the plan back — so it is refused
    before any file is read or written.
    """
    text = str(value or "").strip()
    if not text or ".." in text or not INVESTIGATION_NAME.fullmatch(text):
        raise SystemExit(f"the investigation id must be a single name, not a path: {value!r}")
    return text


def plan_path(investigation: str) -> Path:
    """The plan file of an investigation."""
    return INVESTIGATIONS / safe_investigation(investigation) / "plan.yaml"


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


def load_redactor():
    """The redactor next door. Its rules about a name are the rules here."""
    global _REDACTOR
    if _REDACTOR is not None:
        return _REDACTOR
    spec = importlib.util.spec_from_file_location("wb_research_redact", REDACTOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _REDACTOR = module
    return module


def load_capture():
    """The capture next door. Its defaults are the defaults here."""
    global _CAPTURE
    if _CAPTURE is not None:
        return _CAPTURE
    spec = importlib.util.spec_from_file_location("wb_research_capture", CAPTURE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _CAPTURE = module
    return module


def safe_step_name(name: str) -> str:
    """The name of a step, checked the way the capture checks it.

    The name of a step is the name of the file it produces, in the raw cache
    and in the repository. A name the rules would reduce is refused rather
    than silently changed: two names can reduce to one, and the second step
    would overwrite the evidence of the first one.
    """
    return load_redactor().safe_identifier(name, "step")


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
        if safe_step_name(name) != name:
            raise SystemExit(f"the name of a step is one name, not something to reduce: {name!r}")
        if name in names:
            raise SystemExit(f"two steps of the plan are named {name}")
        names.add(name)
        if not isinstance(command, str) or not command.strip():
            raise SystemExit(f"the step {name} has no command")
        hosts = step.get("allow_hosts", [])
        if not isinstance(hosts, list) or any(not isinstance(host, str) or not host for host in hosts):
            raise SystemExit(f"the step {name} has a malformed allow_hosts")
    return steps


def config_scope(path: Path | None = None) -> dict:
    """The part of the capture config that decides what the session may touch.

    The capture reads the shared config when it runs a step, so the config is
    part of the scope a plan runs under: a config that allows another origin
    or another page widens what the session may reach, exactly as a wider
    command in the plan does.

    The noise hosts and the kept resource types are part of it for the same
    reason, and not because they decide what is kept: a host that is noise is
    a host whose traffic is not held to the approval, and a request type that
    is not kept is a request type that is not an API call — so both decide
    what the capture refuses to stop at. The limit on the bodies is the one
    setting that only decides what is read.

    The defaults are the defaults of the capture: a config that leaves a
    setting out is read by the capture as its own default, and the scope has
    to say the same thing.

    The scope is read from a file the caller names, so that the scope of a
    run is the scope of the file the run froze and not the scope of a file
    that may change under it.
    """
    path = CONFIG_PATH if path is None else path
    if not path.is_file():
        raise SystemExit(f"no capture config at {path}")
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"the capture config is not readable: {path} ({error})") from error
    return {
        "allowedOrigins": sorted(config.get("allowedOrigins", [])),
        "allowedPages": sorted(config.get("allowedPages", [])),
        "allowedQueryParams": sorted(config.get("allowedQueryParams", [])),
        "noiseHosts": sorted(str(host).strip().lower() for host in config.get("noiseHosts", [])),
        "keptResourceTypes": sorted(config.get("keptResourceTypes", list(load_capture().API_RESOURCE_TYPES))),
    }


def settle_of(step: dict) -> float:
    """How long a step waits for the answers, in seconds.

    The wait is part of what the step observes: a step that stops listening
    earlier may miss the request that carries the answer, and the evidence of
    such a step is not the evidence of the step with a longer wait. The wait
    therefore belongs to the scope of the step, and it is read from the plan
    alone — a run cannot shorten it behind the approval.
    """
    value = step.get("settle", DEFAULT_SETTLE)
    try:
        seconds = float(value)
    except (TypeError, ValueError) as error:
        raise SystemExit(f"the step {step.get('name')} has a malformed settle: {value!r}") from error
    if not math.isfinite(seconds) or seconds < 0:
        raise SystemExit(f"the step {step.get('name')} has a malformed settle: {value!r}")
    return seconds


def step_payload(step: dict) -> dict:
    """What a step is: its name, its command, the origins it may reach, its wait."""
    return {
        "name": step.get("name"),
        "command": step.get("command"),
        "allow_hosts": sorted(step.get("allow_hosts", [])),
        "settle": settle_of(step),
    }


def scope_of(steps: list[dict], config: dict | None = None) -> str:
    """The scope of a plan: what it may do and which origins it may reach.

    The name, the command and the approved origins of a step are the scope, and
    so is the capture config, because the capture reads it while the step runs.
    The state of a step and its note are not: they change while the run goes
    on. The fingerprint of the scope is what the approval covers — a plan whose
    scope changed after the approval has to be approved again.
    """
    config = config_scope() if config is None else config
    scope = [step_payload(step) for step in steps]
    payload = json.dumps({"config": config, "steps": scope}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def step_scope(step: dict, config: dict) -> str:
    """The scope one step was run under: the step itself and the config of the run.

    The config is part of it for the same reason it is part of the approval: a
    config that reaches another origin makes the same command a different run.
    The fingerprint is written into the step when it runs, so a state that was
    made under another scope can be told from a state that was made under this
    one.
    """
    payload = json.dumps({"config": config, "step": step_payload(step)}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def config_snapshot(investigation: str) -> Path:
    """The capture config, frozen for the run.

    The capture reads the config itself, every time it starts, so a config
    that changed while the run was going would widen the scope of the steps
    that come after it — under an approval that was given for the old one.
    The run hands the capture this file instead: every step of the run works
    under the config the approval covered, whatever the shared file says
    afterwards.

    The file is the run's own: two runs of the same investigation do not share
    it, so one run cannot overwrite the config of another one. The run removes
    it when it is done.
    """
    directory = RAW_ROOT / safe_investigation(investigation)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"approved-config-{uuid.uuid4().hex}.json"
    if not CONFIG_PATH.is_file():
        raise SystemExit(f"no capture config at {CONFIG_PATH}")
    path.write_text(CONFIG_PATH.read_text(encoding="utf-8"), encoding="utf-8")
    return path


def run_step(investigation: str, step: dict, config_path: Path) -> tuple[str, str]:
    """Run one step of the plan: its state and what the capture printed.

    The state follows the exit code of the capture: a step that was recorded
    is done, a step whose action failed is failed, and a call to an origin
    that is neither approved nor marked as noise needs a human — the origin
    is the operator's decision, not the runner's.

    The capture works under the config the run froze, not under the shared
    file: the scope of the run is the scope that was approved. The wait is
    the one of the step, for the same reason.
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
        "--config",
        str(config_path),
        "--settle",
        str(settle_of(step)),
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


@contextmanager
def investigation_lock(investigation: str):
    """One run of one investigation at a time.

    The plan, the capture and the evidence of an investigation are named after
    the investigation and the step, so two runs of the same investigation
    write the same files: one of them would delete the recording of the other
    and the plan would keep the state of a run whose evidence is gone. The
    lock is taken for the whole run and is not waited for — the second run is
    told that another one is going, and it starts when that one is done.
    """
    directory = RAW_ROOT / safe_investigation(investigation)
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "run.lock").open("w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise SystemExit(f"another run of {investigation} is going — wait for it to end") from error
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def approve(investigation: str) -> int:
    """Approve the plan as it stands and write down what was approved.

    The approval is an act of its own, not a field the runner reads: the
    fingerprint of the scope is written into the plan here, and the runner
    only compares the scope it finds with the one that was approved. A plan
    whose scope changed after this — another command, another origin — does
    not match the fingerprint and is refused until it is approved again.

    The approval also plans the steps again whose state was made under another
    scope: a state that belongs to another command, another origin or another
    config says nothing about the step as it stands now, and a run that skips
    such a step would claim a command it never ran. A step whose scope did not
    change keeps its state — the run resumes where it stopped.
    """
    path = plan_path(investigation)
    with investigation_lock(investigation):
        plan = load_plan(path)
        steps = check_plan(plan, investigation)
        config = config_scope()
        for step in steps:
            if step.get("scope") == step_scope(step, config):
                continue
            state = step.get("state", PLANNED)
            step["state"] = PLANNED
            step.pop("note", None)
            step.pop("scope", None)
            if state != PLANNED:
                print(f"{step['name']}: {state} → planned — the step is not the one that was run")
        plan["approved_scope"] = scope_of(steps, config)
        plan["status"] = APPROVED
        save_plan(path, plan)
    print(f"the plan is approved: {investigation}")
    print(f"the scope that was approved: {plan['approved_scope'][:12]}")
    return EXIT_OK


def run(investigation: str) -> int:
    """Run the steps of the plan that are not done yet."""
    path = plan_path(investigation)
    with investigation_lock(investigation):
        plan = load_plan(path)
        steps = check_plan(plan, investigation)

        status = plan.get("status")
        if status not in {APPROVED, RUNNING}:
            raise SystemExit(f"the plan is not approved (status: {status})")
        approved = plan.get("approved_scope")
        if not approved:
            raise SystemExit("the plan was not approved — approve it: plan.py approve --investigation <id>")
        snapshot = config_snapshot(investigation)
        try:
            config = config_scope(snapshot)
            if approved != scope_of(steps, config):
                raise SystemExit(
                    "the scope of the plan is not the scope that was approved — the operator has to approve it again"
                )

            plan["status"] = RUNNING
            save_plan(path, plan)

            stopped = False
            for step in steps:
                state = step.get("state", PLANNED)
                if step.get("scope") != step_scope(step, config):
                    # The state was made under another scope — another command,
                    # another origin, another config, or by hand. It says
                    # nothing about this step, so the step runs again.
                    state = PLANNED
                    step["state"] = PLANNED
                    step.pop("note", None)
                    step.pop("scope", None)
                if state == DONE:
                    continue
                if state != PLANNED:
                    # A step that failed or that needs a human is where the run
                    # stopped. It stays until a human says it may go on.
                    print(f"{step['name']}: {state} — {step.get('note', '')}")
                    stopped = True
                    break
                new_state, output = run_step(investigation, step, snapshot)
                step["state"] = new_state
                step["note"] = note_of(investigation, step["name"], new_state)
                # The scope the state was made under: a later approval compares
                # it with the scope of the step and plans the step again when
                # they differ.
                step["scope"] = step_scope(step, config)
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
        finally:
            # The frozen config is the run's own and it is gone when the run is.
            snapshot.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)

    approve_parser = sub.add_parser("approve", help="approve the plan as it stands and record the scope")
    approve_parser.add_argument("--investigation", required=True, help="the investigation id")

    run_parser = sub.add_parser("run", help="run the steps of the plan that are not done yet")
    run_parser.add_argument("--investigation", required=True, help="the investigation id")

    args = parser.parse_args(argv)
    if args.verb == "approve":
        return approve(args.investigation)
    return run(args.investigation)


if __name__ == "__main__":
    raise SystemExit(main())
