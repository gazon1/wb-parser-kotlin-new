"""The plan is the scope of the experiment, and it has to survive a stop.

The runner is what stands between a plan and a real browser session, so the
rules are tested here: an unapproved plan does not run, a step that failed
stops the run, a done step is not run again, and the plan keeps the state that
lets a later run resume.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
from pathlib import Path

import pytest
import yaml

PLAN = Path(__file__).resolve().parents[1] / "wb-research" / "plan.py"


def load_runner():
    spec = importlib.util.spec_from_file_location("wb_research_plan", PLAN)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


plan = load_runner()


def write_plan(path: Path, steps: list[dict], status: str = "approved") -> Path:
    """A plan file with the given steps."""
    path.write_text(
        yaml.safe_dump(
            {
                "id": "2026-11-20-wb-catalog-agent",
                "goal": "how the seller catalog is loaded and paged",
                "status": status,
                "steps": steps,
            },
            sort_keys=False,
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    return path


def step(name: str, command: str = 'chrome-use open "https://www.wildberries.ru/"') -> dict:
    return {"name": name, "command": command, "expected": "the page answers", "state": "planned"}


@pytest.fixture
def investigation(tmp_path, monkeypatch):
    """An investigation whose plan is the one under test."""
    monkeypatch.setattr(plan, "INVESTIGATIONS", tmp_path)

    def make(steps: list[dict], status: str = "approved") -> str:
        name = "2026-11-20-wb-catalog-agent"
        (tmp_path / name).mkdir(exist_ok=True)
        write_plan(tmp_path / name / "plan.yaml", steps, "draft")
        if status == "approved":
            # The operator approves the plan: the scope that was approved is
            # written into the file, and the run only compares it.
            plan.approve(name)
        return name

    return make


def fake_capture(monkeypatch, states: list[int]):
    """A capture that returns the given exit codes, one per step."""
    calls: list[list[str]] = []

    def run_step(investigation, step, settle):
        calls.append(step["command"])
        code = states[len(calls) - 1]
        if code == plan.EXIT_OK:
            return plan.DONE, "recorded"
        if code == plan.EXIT_UNAPPROVED_API:
            return plan.NEEDS_HUMAN, "the origin is not approved"
        return plan.FAILED, "the action failed"

    monkeypatch.setattr(plan, "run_step", run_step)
    return calls


def read_plan(tmp_path: Path, investigation: str) -> dict:
    return yaml.safe_load((tmp_path / investigation / "plan.yaml").read_text(encoding="utf-8"))


def test_an_unapproved_plan_does_not_run(investigation, monkeypatch):
    name = investigation([step("initial-load")], status="draft")
    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    with pytest.raises(SystemExit, match="not approved"):
        plan.run(name, 8.0)

    assert calls == []


def test_a_step_is_captured_and_marked_done(investigation, tmp_path, monkeypatch):
    name = investigation([step("initial-load")])
    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    assert plan.run(name, 8.0) == plan.EXIT_OK
    assert calls == ['chrome-use open "https://www.wildberries.ru/"']

    written = read_plan(tmp_path, name)
    assert written["steps"][0]["state"] == plan.DONE
    assert written["status"] == plan.RUNNING


def test_a_failing_step_stops_the_run(investigation, tmp_path, monkeypatch):
    name = investigation([step("initial-load"), step("next-page")])
    calls = fake_capture(monkeypatch, [plan.EXIT_FAILED, plan.EXIT_OK])

    assert plan.run(name, 8.0) == plan.EXIT_FAILED
    assert calls == ['chrome-use open "https://www.wildberries.ru/"']

    written = read_plan(tmp_path, name)
    assert written["steps"][0]["state"] == plan.FAILED
    assert written["steps"][1]["state"] == "planned"


def test_an_unapproved_origin_needs_a_human(investigation, tmp_path, monkeypatch):
    name = investigation([step("initial-load")])
    fake_capture(monkeypatch, [plan.EXIT_UNAPPROVED_API])

    assert plan.run(name, 8.0) == plan.EXIT_FAILED

    written = read_plan(tmp_path, name)
    assert written["steps"][0]["state"] == plan.NEEDS_HUMAN


def test_a_done_step_is_not_run_again(investigation, tmp_path, monkeypatch):
    name = investigation([step("initial-load"), step("next-page")])
    calls = fake_capture(monkeypatch, [plan.EXIT_OK, plan.EXIT_OK])

    assert plan.run(name, 8.0) == plan.EXIT_OK
    assert len(calls) == 2

    # The second run resumes: the step that is done is skipped.
    calls.clear()
    assert plan.run(name, 8.0) == plan.EXIT_OK
    assert calls == []


def test_a_stopped_step_stops_a_later_run_until_it_is_released(investigation, tmp_path, monkeypatch):
    name = investigation([step("initial-load"), step("next-page")])
    fake_capture(monkeypatch, [plan.EXIT_FAILED, plan.EXIT_OK])

    assert plan.run(name, 8.0) == plan.EXIT_FAILED

    calls = fake_capture(monkeypatch, [plan.EXIT_OK])
    assert plan.run(name, 8.0) == plan.EXIT_FAILED
    assert calls == []

    # The human releases the step: it is planned again and the run continues.
    written = read_plan(tmp_path, name)
    written["steps"][0]["state"] = "planned"
    (tmp_path / name / "plan.yaml").write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK, plan.EXIT_OK])
    assert plan.run(name, 8.0) == plan.EXIT_OK
    assert len(calls) == 2


def test_a_plan_without_steps_is_refused(investigation):
    name = investigation([], status="draft")

    with pytest.raises(SystemExit, match="no steps"):
        plan.run(name, 8.0)


def test_a_plan_of_another_investigation_is_refused(investigation, tmp_path):
    name = investigation([step("initial-load")])
    path = tmp_path / name / "plan.yaml"
    written = yaml.safe_load(path.read_text(encoding="utf-8"))
    written["id"] = "2026-11-19-another-one"
    path.write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    with pytest.raises(SystemExit, match="another investigation"):
        plan.run(name, 8.0)


def test_the_capture_is_run_with_the_command_of_the_step(monkeypatch):
    """The step reaches the capture as a command line, not as a string."""
    seen: list[list[str]] = []

    class Result:
        returncode = 0
        stdout = ""
        stderr = ""

    def fake_run(argv, **kwargs):
        seen.append(argv)
        return Result()

    monkeypatch.setattr(subprocess, "run", fake_run)

    state, _ = plan.run_step(
        "2026-11-20-wb-catalog-agent",
        {"name": "initial-load", "command": 'chrome-use open "https://www.wildberries.ru/"'},
        8.0,
    )

    assert state == plan.DONE
    assert seen[0][-4:] == ["--", "chrome-use", "open", "https://www.wildberries.ru/"]
    assert "2026-11-20-wb-catalog-agent" in seen[0]
    assert "initial-load" in seen[0]


def test_an_approved_origin_reaches_the_capture(monkeypatch):
    """An origin the step may reach is approved for this step only."""
    seen: list[list[str]] = []

    class Result:
        returncode = 0
        stdout = ""
        stderr = ""

    def fake_run(argv, **kwargs):
        seen.append(argv)
        return Result()

    monkeypatch.setattr(subprocess, "run", fake_run)

    plan.run_step(
        "2026-11-20-wb-catalog-agent",
        {
            "name": "initial-load",
            "command": 'chrome-use open "https://www.wildberries.ru/"',
            "allow_hosts": ["splitter.wb.ru"],
        },
        8.0,
    )

    assert "--allow-host" in seen[0]
    assert seen[0][seen[0].index("--allow-host") + 1] == "splitter.wb.ru"


def test_an_id_that_is_a_path_is_refused(investigation, tmp_path, monkeypatch):
    """The id names a directory: a path is not an id, and nothing is written."""
    monkeypatch.setattr(plan, "INVESTIGATIONS", tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    plan_file = outside / "plan.yaml"
    plan_file.write_text("id: ../../outside\nstatus: approved\nsteps: []\n", encoding="utf-8")
    before = plan_file.read_text(encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    with pytest.raises(SystemExit, match="single name"):
        plan.run("../../outside", 8.0)

    assert calls == []
    assert plan_file.read_text(encoding="utf-8") == before


def test_a_changed_scope_needs_a_new_approval(investigation, tmp_path, monkeypatch):
    """The scope that was approved is the scope that runs."""
    name = investigation([step("initial-load")])
    fake_capture(monkeypatch, [plan.EXIT_OK])
    assert plan.run(name, 8.0) == plan.EXIT_OK

    # The plan is running. Somebody widens it while nobody watches.
    written = read_plan(tmp_path, name)
    written["steps"][0]["command"] = 'chrome-use open "https://www.wildberries.ru/other"'
    (tmp_path / name / "plan.yaml").write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    with pytest.raises(SystemExit, match="approve it again"):
        plan.run(name, 8.0)

    assert calls == []


def test_a_new_approval_covers_the_new_scope(investigation, tmp_path, monkeypatch):
    """The operator approves the widened plan: then it runs."""
    name = investigation([step("initial-load")])
    fake_capture(monkeypatch, [plan.EXIT_OK])
    assert plan.run(name, 8.0) == plan.EXIT_OK

    written = read_plan(tmp_path, name)
    written["steps"][0]["command"] = 'chrome-use open "https://www.wildberries.ru/other"'
    written["steps"][0]["state"] = "planned"
    (tmp_path / name / "plan.yaml").write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK, plan.EXIT_OK])

    with pytest.raises(SystemExit, match="approve it again"):
        plan.run(name, 8.0)
    assert calls == []

    # The operator approves the changed plan: the scope is approved again.
    plan.approve(name)

    assert plan.run(name, 8.0) == plan.EXIT_OK
    assert len(calls) == 1


def test_a_plan_changed_after_the_approval_does_not_run(investigation, tmp_path, monkeypatch):
    """The approval covers the plan as it was approved, not as it is later."""
    name = investigation([step("initial-load")])

    # Somebody widens the plan between the approval and the first run.
    written = read_plan(tmp_path, name)
    written["steps"][0]["command"] = 'chrome-use open "https://www.wildberries.ru/other"'
    (tmp_path / name / "plan.yaml").write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    with pytest.raises(SystemExit, match="approve it again"):
        plan.run(name, 8.0)

    assert calls == []


def test_a_plan_without_an_approval_record_does_not_run(investigation, tmp_path, monkeypatch):
    """The status alone is not an approval: the approved scope has to be there."""
    name = investigation([step("initial-load")], status="draft")

    written = read_plan(tmp_path, name)
    written["status"] = "approved"
    (tmp_path / name / "plan.yaml").write_text(yaml.safe_dump(written, sort_keys=False), encoding="utf-8")

    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    with pytest.raises(SystemExit, match="was not approved"):
        plan.run(name, 8.0)

    assert calls == []


def test_the_approval_writes_the_scope_it_covered(investigation, tmp_path):
    """The approval says what it approved."""
    name = investigation([step("initial-load")])

    written = read_plan(tmp_path, name)
    assert written["status"] == plan.APPROVED
    assert written["approved_scope"] == plan.scope_of(written["steps"])


def test_a_changed_config_needs_a_new_approval(investigation, tmp_path, monkeypatch):
    """The capture config is part of the scope: it decides what is reachable."""
    config = tmp_path / "capture-config.json"
    config.write_text(
        json.dumps({"allowedOrigins": ["www.wildberries.ru"], "allowedPages": [], "allowedQueryParams": []}),
        encoding="utf-8",
    )
    monkeypatch.setattr(plan, "CONFIG_PATH", config)

    name = investigation([step("initial-load")])
    calls = fake_capture(monkeypatch, [plan.EXIT_OK])

    # The config is widened after the approval.
    config.write_text(
        json.dumps(
            {
                "allowedOrigins": ["www.wildberries.ru", "api.wildberries.ru"],
                "allowedPages": [],
                "allowedQueryParams": [],
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(SystemExit, match="approve it again"):
        plan.run(name, 8.0)

    assert calls == []


def test_a_malformed_allow_hosts_is_refused(investigation):
    """The approved origins are a list of names, not a string."""
    name = investigation([{**step("initial-load"), "allow_hosts": "splitter.wb.ru"}], status="draft")

    with pytest.raises(SystemExit, match="malformed allow_hosts"):
        plan.run(name, 8.0)


def test_a_step_name_that_is_a_path_is_refused(investigation):
    """The name of a step is the name of a file: one name, not a path."""
    name = investigation([step("../../other/step")], status="draft")

    with pytest.raises(SystemExit, match="one name"):
        plan.run(name, 8.0)
