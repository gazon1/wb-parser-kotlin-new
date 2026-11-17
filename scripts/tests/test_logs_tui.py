"""
The TUI's log tab — the last lines of the log, and the follow.

The log tab is the one place where the operator watches something that never ends,
so the questions are about ending it: Stop has to kill the child, a second Follow
must not leave the first one running, and closing the TUI must not leave a
`docker compose logs` behind either.

The children are substitutes. Compose is replaced by a Python one-liner, so the
tests need neither Docker nor a running stack — and the child writes its pid down,
so "is it still running?" is answered by the process itself and not by a mock.
"""

from __future__ import annotations

import asyncio
import os
import sys

import pytest
from test_wbparser import _load_wbparser


@pytest.fixture
def mod():
    """The tool itself — the same module the TUI runs."""
    return _load_wbparser()


# A child that prints its pid, says a line and then waits: it is the shape of
# `docker compose logs --follow`, which never ends on its own.
FOLLOWER = """
import os, sys, time
open(sys.argv[1], 'w').write(str(os.getpid()))
print('a line of log', flush=True)
time.sleep(30)
"""

# A child that ends by itself, the way `docker compose logs --tail N` does.
TAIL = """
import sys
print('first line', flush=True)
print('second line', flush=True)
"""


def _compose(mod, monkeypatch, *, pidfile=None, running=True, tail=TAIL) -> list[str]:
    """Replace Compose with a substitute, and record the commands it was asked for."""
    calls: list[tuple[str, ...]] = []

    def compose(*argv: str) -> list[str]:
        calls.append(argv)
        if argv and argv[0] == "ps":
            script = "print('app')" if running else "print('')"
            return [sys.executable, "-c", script]
        if pidfile is not None and "--follow" in argv:
            return [sys.executable, "-c", FOLLOWER, str(pidfile)]
        return [sys.executable, "-c", tail]

    monkeypatch.setattr(mod, "_compose_command", compose)
    return calls


def _alive(pid: int) -> bool:
    """Whether the process is still there."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


def _assert_gone(pid: int) -> None:
    """The process is gone, and it was reaped: a zombie is still a process."""
    assert not _alive(pid)
    with pytest.raises(ChildProcessError):
        os.waitpid(pid, os.WNOHANG)


async def _open_the_log_tab(mod, pilot) -> None:
    """Switch to the log tab and wait for the tab to be laid out."""
    await pilot.press("7")
    await pilot.pause()


async def _wait_for_child(pidfile) -> int:
    """Wait for the follow's child to start, and answer with its pid."""
    for _ in range(100):
        if pidfile.exists():
            return int(pidfile.read_text())
        await asyncio.sleep(0.05)
    raise AssertionError("the follow never started its child")


def _shown(mod, host) -> str:
    """What the operator saw in the log view."""
    view = host.query_one("#logs-view", mod.RichLog)
    return "".join(strip.text for strip in view.lines)


async def _wait_for_text(mod, host, text: str) -> None:
    """
    Wait for the text to show up in the log view — and insist that it does.

    Polling without an assertion is a test that passes when nothing happens, so
    the wait ends in a failure, never in a shrug.
    """
    for _ in range(100):
        if text in _shown(mod, host):
            return
        await asyncio.sleep(0.05)
    raise AssertionError(f"the log view never showed {text!r}")


# ── the log tab ──────────────────────────────────────────────────────────────


def test_the_log_buttons_are_on_the_screen(mod) -> None:
    """
    The buttons have to be reachable: an Input is 100% wide by default, and it
    pushed Load, Follow and Stop off the screen.
    """

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            for button_id in ("#btn-logs", "#btn-logs-follow", "#btn-logs-stop"):
                button = host.query_one(button_id)
                assert button.region.right <= host.screen.size.width, button_id

    asyncio.run(scenario())


def test_the_load_writes_the_last_lines(mod, monkeypatch) -> None:
    """A load is a snapshot: the lines the log ended with."""
    _compose(mod, monkeypatch)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs")
            await _wait_for_text(mod, host, "second line")

    asyncio.run(scenario())


def test_the_load_says_when_the_app_is_not_running(mod, monkeypatch) -> None:
    """There is no log without a container: the operator is told why."""
    _compose(mod, monkeypatch, running=False)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await _wait_for_text(mod, host, "not running")

    asyncio.run(scenario())


def test_the_follow_streams_the_lines_as_they_arrive(mod, monkeypatch, tmp_path) -> None:
    """The follow writes what the child says, line by line."""
    pidfile = tmp_path / "pid"
    _compose(mod, monkeypatch, pidfile=pidfile)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs-follow")
            await _wait_for_text(mod, host, "a line of log")

    asyncio.run(scenario())


def test_a_follow_that_fails_says_so(mod, monkeypatch) -> None:
    """A follow that ends with a non-zero code is a failure, not a silent end."""
    _compose(mod, monkeypatch, tail="import sys; sys.exit(1)")

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs-follow")
            await _wait_for_text(mod, host, "failed with code")

    asyncio.run(scenario())


# ── ending the follow ────────────────────────────────────────────────────────


def test_the_stop_button_kills_the_follow_process(mod, monkeypatch, tmp_path) -> None:
    """Stop stops the follow: the child process is gone, and not just detached."""
    pidfile = tmp_path / "pid"
    _compose(mod, monkeypatch, pidfile=pidfile)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs-follow")
            await _wait_for_child(pidfile)
            await pilot.click("#btn-logs-stop")
            await asyncio.sleep(1.0)

    asyncio.run(scenario())

    _assert_gone(int(pidfile.read_text()))


def test_a_second_follow_leaves_no_first_process_running(mod, monkeypatch, tmp_path) -> None:
    """Follow twice: the first follow is over, and its child is gone."""
    pidfile = tmp_path / "pid"
    _compose(mod, monkeypatch, pidfile=pidfile)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs-follow")
            first = await _wait_for_child(pidfile)
            # A button ignores a click while it is still lit up from the last one.
            await asyncio.sleep(0.3)
            await pilot.click("#btn-logs-follow")
            await asyncio.sleep(1.0)
            _assert_gone(first)

    asyncio.run(scenario())


def test_closing_the_tui_leaves_no_follow_process(mod, monkeypatch, tmp_path) -> None:
    """The TUI goes away, and so does the `docker compose logs` it was watching."""
    pidfile = tmp_path / "pid"
    _compose(mod, monkeypatch, pidfile=pidfile)

    async def scenario() -> None:
        host = mod.WbparserApp()
        async with host.run_test(size=(140, 40)) as pilot:
            await _open_the_log_tab(mod, pilot)
            await pilot.click("#btn-logs-follow")
            await _wait_for_child(pidfile)

    asyncio.run(scenario())

    _assert_gone(int(pidfile.read_text()))
