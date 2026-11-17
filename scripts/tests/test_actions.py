"""
The shared actions — up, down and crawl — and the TUI buttons that run them.

The CLI and the TUI call the same functions, so the rules worth proving are the ones
those functions hold: which argv is started, which environment a crawl runs in, what
the exit codes are, and what a cancelled action leaves behind.

The children are substitutes. The seams that name the executables are replaced with
a Python one-liner, so Gradle, Compose and the crawler jar are not needed to prove
any of it.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import sys

import pytest
from test_wbparser import _load_wbparser
from textual.worker import WorkerCancelled
from typer.testing import CliRunner


@pytest.fixture
def mod():
    """The tool itself — the same module the CLI and the TUI run."""
    return _load_wbparser()


def _stub(*argv: str, code: int = 0) -> list[str]:
    """A substitute executable: it prints its own argv and exits with `code`."""
    script = f"import sys; print(' '.join(sys.argv[1:]), flush=True); sys.exit({code})"
    return [sys.executable, "-c", script, *argv]


def _compose_recorder(mod, monkeypatch, code: int = 0) -> list[tuple[str, ...]]:
    """Replace the compose command with a substitute, and record what it was asked for."""
    calls: list[tuple[str, ...]] = []

    def compose(*argv: str) -> list[str]:
        calls.append(argv)
        return _stub(*argv, code=code)

    monkeypatch.setattr(mod, "_compose_command", compose)
    return calls


def _ready(mod, monkeypatch, answer: bool) -> list[int]:
    """Replace the readiness wait, and record the timeout it was given."""
    timeouts: list[int] = []

    async def wait(timeout: int) -> bool:
        timeouts.append(timeout)
        return answer

    monkeypatch.setattr(mod, "_wait_ready", wait)
    return timeouts


def _host(mod, finished: list[tuple[str, int]] | None = None):
    """A one-screen app with the action buttons and the log they write to."""

    class Host(mod.App):
        def compose(self):
            yield mod.RichLog(id="activity-log")
            yield mod.Actions()

        if finished is not None:

            @mod.on(mod.Actions.Finished)
            def _record(self, event) -> None:
                finished.append((event.action, event.code))

    return Host()


async def _click_down(mod, pilot, confirm: bool = True) -> None:
    """Click Down and answer its confirmation — the button asks before it stops anything."""
    await pilot.click("#btn-down")
    await pilot.pause()
    assert isinstance(pilot.app.screen, mod.ConfirmDown)
    await pilot.click("#confirm-stop" if confirm else "#confirm-keep")
    await pilot.pause()


def _said(mod, host) -> str:
    """What the operator saw in the activity log."""
    log = host.query_one("#activity-log", mod.RichLog)
    return "".join(strip.text for strip in log.lines)


# ── the actions ──────────────────────────────────────────────────────────────


def test_down_keeps_the_volume_by_default(mod, monkeypatch) -> None:
    calls = _compose_recorder(mod, monkeypatch)
    said: list[str] = []

    code = asyncio.run(mod.action_down(said.append))

    assert code == mod.EXIT_OK
    assert calls == [("down",)]
    assert any("volume kept" in line for line in said)


def test_down_removes_the_volume_only_when_it_is_asked(mod, monkeypatch) -> None:
    calls = _compose_recorder(mod, monkeypatch)

    code = asyncio.run(mod.action_down(lambda _text: None, volumes=True))

    assert code == mod.EXIT_OK
    assert calls == [("down", "-v")]


def test_up_starts_the_stack_and_waits_for_the_answer(mod, monkeypatch, tmp_path) -> None:
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)
    compose = _compose_recorder(mod, monkeypatch)
    timeouts = _ready(mod, monkeypatch, answer=True)
    builds: list[bool] = []

    def gradle() -> list[str]:
        builds.append(True)
        return _stub("bootJar")

    monkeypatch.setattr(mod, "_gradle_command", gradle)

    code = asyncio.run(mod.action_up(lambda _text: None, timeout=7))

    assert code == mod.EXIT_OK
    # The jar was there, so nothing was built, and the stack was started.
    assert builds == []
    assert compose == [("up", "-d")]
    assert timeouts == [7]


def test_up_builds_the_jar_when_there_is_none(mod, monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(mod, "JAR", tmp_path / "missing.jar")
    compose = _compose_recorder(mod, monkeypatch)
    _ready(mod, monkeypatch, answer=True)
    builds: list[bool] = []

    def gradle() -> list[str]:
        builds.append(True)
        return _stub("bootJar")

    monkeypatch.setattr(mod, "_gradle_command", gradle)

    code = asyncio.run(mod.action_up(lambda _text: None))

    assert code == mod.EXIT_OK
    assert builds == [True]
    # A rebuilt jar is mounted into the container, so the app is recreated.
    assert compose == [("up", "-d", "--force-recreate", "app")]


def test_up_reports_a_stack_that_does_not_answer(mod, monkeypatch, tmp_path) -> None:
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)
    _compose_recorder(mod, monkeypatch)
    _ready(mod, monkeypatch, answer=False)
    said: list[str] = []

    code = asyncio.run(mod.action_up(said.append, timeout=1))

    assert code == mod.EXIT_WRONG
    assert any("did not become ready" in line for line in said)


def test_crawl_refuses_to_run_without_the_jar(mod, monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(mod, "JAR", tmp_path / "missing.jar")

    with pytest.raises(mod.Unusable):
        asyncio.run(mod.action_crawl(lambda _text: None))


def test_crawl_refuses_to_run_without_the_database(mod, monkeypatch, tmp_path) -> None:
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)

    async def running(service: str) -> bool:
        return False

    monkeypatch.setattr(mod, "_running", running)

    with pytest.raises(mod.Unusable):
        asyncio.run(mod.action_crawl(lambda _text: None))


def test_a_crawl_runs_the_jar_in_the_once_profile(mod, monkeypatch, tmp_path) -> None:
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)

    async def running(service: str) -> bool:
        return True

    monkeypatch.setattr(mod, "_running", running)
    monkeypatch.setattr(
        mod,
        "_java_command",
        lambda: [
            sys.executable,
            "-c",
            "import os,sys; print(os.environ.get('SPRING_PROFILES_ACTIVE'), flush=True); sys.exit(1)",
        ],
    )
    said: list[str] = []

    code = asyncio.run(mod.action_crawl(said.append))

    # A crawl that failed is a wrong answer, not an unusable command.
    assert code == mod.EXIT_WRONG
    assert any(line.strip() == "once" for line in said)


def test_a_crawl_that_succeeded_is_an_ok_answer(mod, monkeypatch, tmp_path) -> None:
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)

    async def running(service: str) -> bool:
        return True

    monkeypatch.setattr(mod, "_running", running)
    monkeypatch.setattr(mod, "_java_command", lambda: _stub("crawl"))

    code = asyncio.run(mod.action_crawl(lambda _text: None))

    assert code == mod.EXIT_OK


# ── the TUI buttons ──────────────────────────────────────────────────────────


def test_the_tui_down_never_removes_the_volume(mod, monkeypatch) -> None:
    """The button has no `--volumes` behind it: the operator keeps the rows."""
    calls = _compose_recorder(mod, monkeypatch)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    assert calls == [("down",)]


def test_the_tui_down_asks_before_it_stops_the_stack(mod, monkeypatch) -> None:
    """The confirmation comes first: a click alone stops nothing."""
    calls = _compose_recorder(mod, monkeypatch)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot, confirm=False)
            await actions.workers.wait_for_complete()
            # The refusal left the stack alone and the buttons working again.
            assert all(not button.disabled for button in actions.query(mod.Button))

    asyncio.run(scenario())

    assert calls == []


def test_escape_is_a_refusal_of_the_confirmation(mod, monkeypatch) -> None:
    """
    Escape is the way out of a question, so it is an answer: no.

    The stack keeps running and nothing is started.
    """
    calls = _compose_recorder(mod, monkeypatch)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await pilot.click("#btn-down")
            await pilot.pause()
            assert isinstance(pilot.app.screen, mod.ConfirmDown)
            await pilot.press("escape")
            await pilot.pause()
            await actions.workers.wait_for_complete()
            assert all(not button.disabled for button in actions.query(mod.Button))

    asyncio.run(scenario())

    assert calls == []


def test_the_tui_down_stops_the_stack_once_when_it_is_confirmed(mod, monkeypatch) -> None:
    calls = _compose_recorder(mod, monkeypatch)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    # Exactly one operation, and it is not the one that deletes the rows.
    assert calls == [("down",)]


def test_the_tui_buttons_are_disabled_while_an_action_runs(mod, monkeypatch) -> None:
    calls = _compose_recorder(mod, monkeypatch)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            await actions.workers.wait_for_complete()
            # The action is over: the buttons work again.
            assert all(not button.disabled for button in actions.query(mod.Button))

    asyncio.run(scenario())

    assert calls == [("down",)]


def test_a_cancelled_action_leaves_no_child_process_behind(mod, monkeypatch, tmp_path) -> None:
    """Cancelling the action kills the command it started — nothing is left running."""
    pidfile = tmp_path / "pid"

    def compose(*argv: str) -> list[str]:
        script = "import os,sys,time; open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(30)"
        return [sys.executable, "-c", script, str(pidfile)]

    monkeypatch.setattr(mod, "_compose_command", compose)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            for _ in range(100):
                if pidfile.exists():
                    break
                await asyncio.sleep(0.05)
            assert pidfile.exists(), "the action never started its child"
            actions.workers.cancel_group(actions, "action")
            with contextlib.suppress(WorkerCancelled):
                await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    pid = int(pidfile.read_text())
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_the_tui_reports_an_action_it_could_not_run(mod, monkeypatch) -> None:
    def compose(*argv: str) -> list[str]:
        return [sys.executable, "-c", "import sys; sys.exit(1)"]

    monkeypatch.setattr(mod, "_compose_command", compose)
    finished: list[tuple[str, int]] = []

    async def scenario() -> None:
        host = _host(mod, finished)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    assert finished == [("down", mod.EXIT_UNUSABLE)]


# ── the two front ends ───────────────────────────────────────────────────────


def test_the_cli_and_the_tui_run_the_same_action(mod, monkeypatch, tmp_path) -> None:
    """One action, two front ends: both ask Compose for exactly the same thing."""
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)
    calls = _compose_recorder(mod, monkeypatch)
    _ready(mod, monkeypatch, answer=True)

    result = CliRunner().invoke(mod.app, ["up"])
    assert result.exit_code == 0, result.output

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await pilot.click("#btn-up")
            await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    assert calls == [("up", "-d"), ("up", "-d")]


def test_the_cli_keeps_its_flags(mod, monkeypatch) -> None:
    """`--build`, `--timeout` and `--volumes` still reach the action."""
    seen: list[tuple[str, dict[str, object]]] = []

    async def up(say, *, build: bool = False, timeout: int = 180) -> int:
        seen.append(("up", {"build": build, "timeout": timeout}))
        return mod.EXIT_OK

    async def down(say, *, volumes: bool = False) -> int:
        seen.append(("down", {"volumes": volumes}))
        return mod.EXIT_OK

    monkeypatch.setattr(mod, "action_up", up)
    monkeypatch.setattr(mod, "action_down", down)

    runner = CliRunner()
    assert runner.invoke(mod.app, ["up", "--build", "--timeout", "42"]).exit_code == 0
    assert runner.invoke(mod.app, ["down", "--volumes"]).exit_code == 0

    assert seen == [("up", {"build": True, "timeout": 42}), ("down", {"volumes": True})]


def test_the_tui_down_never_asks_for_the_volume(mod, monkeypatch) -> None:
    """The TUI's Down has no `--volumes` behind it, and the CLI's has."""
    seen: list[dict[str, object]] = []

    async def down(say, *, volumes: bool = False) -> int:
        seen.append({"volumes": volumes})
        return mod.EXIT_OK

    monkeypatch.setitem(mod._ACTIONS, "down", down)

    async def scenario() -> None:
        host = _host(mod)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await _click_down(mod, pilot)
            await actions.workers.wait_for_complete()

    asyncio.run(scenario())

    assert seen == [{"volumes": False}]


def test_an_error_is_shown_to_the_operator_and_is_not_a_success(mod, monkeypatch, tmp_path) -> None:
    """A failure is a message and a failed exit code — never a green tick."""
    jar = tmp_path / "app.jar"
    jar.write_text("x")
    monkeypatch.setattr(mod, "JAR", jar)

    def compose(*argv: str) -> list[str]:
        return [sys.executable, "-c", "import sys; sys.exit(1)"]

    monkeypatch.setattr(mod, "_compose_command", compose)
    finished: list[tuple[str, int]] = []
    said: list[str] = []

    async def scenario() -> None:
        host = _host(mod, finished)
        async with host.run_test() as pilot:
            actions = host.query_one(mod.Actions)
            await pilot.click("#btn-up")
            await actions.workers.wait_for_complete()
            said.append(_said(mod, host))

    asyncio.run(scenario())

    assert finished == [("up", mod.EXIT_UNUSABLE)]
    assert "✖" in said[0]


def test_the_logs_command_fails_when_compose_fails(mod, monkeypatch) -> None:
    """A broken `docker compose logs` is a failure, not a silent success."""

    async def running(service: str) -> bool:
        return True

    monkeypatch.setattr(mod, "_running", running)

    def compose(*argv: str) -> list[str]:
        return [sys.executable, "-c", "import sys; sys.exit(1)"]

    monkeypatch.setattr(mod, "_compose_command", compose)

    with pytest.raises(mod.Unusable):
        asyncio.run(mod._action_logs(lambda _text: None, follow=False, lines=10))
