"""
The subprocess layer: the rules every child process in this tool is started by.

The children here are substitutes — a Python one-liner instead of Gradle, Compose or
the crawler jar — so the tests prove the layer's contract without a stack, a
network or a JVM.
"""

from __future__ import annotations

import asyncio
import os
import signal
import sys
import time
from pathlib import Path

import pytest

PARSER_DIR = str(Path(__file__).resolve().parents[1] / "parser")
if PARSER_DIR not in sys.path:
    sys.path.insert(0, PARSER_DIR)

import proc  # noqa: E402 - the script's sibling, loaded the way the script loads it


def _python(code: str) -> list[str]:
    """A substitute executable: the interpreter asked to run one line of code."""
    return [sys.executable, "-c", code]


def _dead(pid: int, timeout: float = 5.0) -> bool:
    """
    Whether a process is gone. A zombie counts: it is dead, it only waits to be reaped.
    """
    deadline = time.monotonic() + timeout
    while True:
        stat = Path(f"/proc/{pid}/stat")
        if stat.exists():
            if stat.read_text().rsplit(") ", 1)[1].split(" ", 1)[0] == "Z":
                return True
        else:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
        if time.monotonic() > deadline:
            return False
        time.sleep(0.05)


def test_a_finished_command_reports_its_code_and_both_outputs() -> None:
    result = asyncio.run(proc.sh(*_python("import sys; print('out'); print('err', file=sys.stderr); sys.exit(3)")))

    assert result.code == 3
    assert result.stdout.strip() == "out"
    assert result.stderr.strip() == "err"


def test_a_command_that_is_not_installed_is_reported_not_raised_raw() -> None:
    with pytest.raises(proc.ProcError) as raised:
        asyncio.run(proc.sh("wbparser-definitely-not-installed"))

    assert "wbparser-definitely-not-installed" in str(raised.value)


def test_a_command_is_started_without_a_shell() -> None:
    """The argv is the command: a shell would read the second argument as a script."""
    result = asyncio.run(proc.sh(*_python("import sys; print(sys.argv[1])"), "a; touch /tmp/pwned"))

    assert result.stdout.strip() == "a; touch /tmp/pwned"


def test_the_environment_of_a_child_replaces_the_environment() -> None:
    result = asyncio.run(
        proc.sh(
            *_python("import os; print(os.environ.get('ONLY')); print('HOME' in os.environ)"),
            env={"ONLY": "yes"},
        )
    )

    assert result.stdout.split() == ["yes", "False"]


def test_a_streamed_command_merges_stderr_and_reports_its_code() -> None:
    lines: list[str] = []

    code = asyncio.run(
        proc.stream(
            lines.append,
            *_python(
                "import sys; print('first', flush=True); print('second', file=sys.stderr, flush=True); sys.exit(2)"
            ),
        )
    )

    assert code == 2
    assert lines == ["first", "second"]


def test_a_command_that_talks_a_lot_does_not_deadlock() -> None:
    lines: list[str] = []

    code = asyncio.run(proc.stream(lines.append, *_python("print('x' * 100)\n[print(i) for i in range(5000)]")))

    assert code == 0
    assert len(lines) == 5001


def test_the_scope_ends_the_child_when_it_ends() -> None:
    async def scenario() -> tuple[int | None, int | None]:
        async with proc.spawn(*_python("import time; time.sleep(30)")) as child:
            await asyncio.sleep(0.1)
            inside = child.returncode
        return inside, child.returncode

    inside, after = asyncio.run(scenario())

    # The child was still running inside the scope, and it is gone after it.
    assert inside is None
    assert after is not None


def test_a_cancelled_caller_does_not_leave_the_child_running() -> None:
    """The child dies with the caller: a cancelled action leaves no process behind."""
    holders: dict[str, object] = {}

    async def body() -> None:
        async with proc.spawn(*_python("import time; time.sleep(30)")) as child:
            holders["child"] = child
            await asyncio.sleep(30)

    async def scenario() -> int | None:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        child = holders["child"]
        assert isinstance(child, asyncio.subprocess.Process)
        return child.returncode

    assert asyncio.run(scenario()) is not None


def test_the_working_directory_of_a_child_is_the_repository_root() -> None:
    result = asyncio.run(proc.sh(*_python("import os; print(os.getcwd())")))

    assert Path(result.stdout.strip()).resolve() == proc.ROOT


def test_the_environment_of_the_caller_is_kept_when_none_is_given(monkeypatch) -> None:
    monkeypatch.setenv("WBPARSER_TEST_MARKER", "kept")

    result = asyncio.run(proc.sh(*_python("import os; print(os.environ.get('WBPARSER_TEST_MARKER'))")))

    assert result.stdout.strip() == "kept"


# ── the errors a start can fail with ─────────────────────────────────────────


def test_a_command_that_is_there_but_cannot_run_keeps_the_reason(tmp_path) -> None:
    """The file exists, but the start is refused: the reason is not lost on the way."""
    script = tmp_path / "not-executable"
    script.write_text("#!/bin/sh\necho hi\n")
    script.chmod(0o644)

    with pytest.raises(proc.ProcError) as raised:
        asyncio.run(proc.sh(str(script)))

    cause = raised.value.__cause__
    assert isinstance(cause, OSError)
    # The message says what happened and where — it does not swallow the original problem.
    assert "Permission denied" in str(raised.value)
    assert str(script) in str(raised.value)


def test_a_directory_cannot_be_started(tmp_path) -> None:
    with pytest.raises(proc.ProcError) as raised:
        asyncio.run(proc.sh(str(tmp_path)))

    assert isinstance(raised.value.__cause__, OSError)


def test_a_finished_command_that_failed_reports_its_code() -> None:
    result = asyncio.run(proc.sh(*_python("import sys; sys.exit(7)")))

    assert result.code == 7


def test_a_streamed_command_that_failed_reports_its_code() -> None:
    code = asyncio.run(proc.stream(lambda _line: None, *_python("import sys; sys.exit(7)")))

    assert code == 7


# ── the life of a child that does not want to end ────────────────────────────


def test_a_child_that_ignores_termination_is_killed_after_the_grace(monkeypatch) -> None:
    """SIGTERM is a request; after the grace it is a kill."""
    monkeypatch.setattr(proc, "TERM_GRACE", 0.5)
    script = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)"

    async def scenario() -> int | None:
        async with proc.spawn(*_python(script)) as child:
            await asyncio.sleep(0.2)
        return child.returncode

    # SIGKILL — the child was killed, it did not exit on its own.
    assert asyncio.run(scenario()) == -9


def test_a_cancelled_caller_leaves_no_child_behind_while_it_writes_a_lot() -> None:
    """A loud child that is cancelled: no hang, and nothing left running."""
    script = (
        "import sys\n"
        "while True:\n"
        "    sys.stdout.write('o' * 100 + '\\n')\n"
        "    sys.stdout.flush()\n"
        "    sys.stderr.write('e' * 100 + '\\n')\n"
        "    sys.stderr.flush()\n"
    )
    holders: dict[str, object] = {}

    async def body() -> None:
        async with proc.spawn(*_python(script)) as child:
            holders["child"] = child
            await asyncio.sleep(30)

    async def scenario() -> tuple[int, int | None]:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.3)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        child = holders["child"]
        assert isinstance(child, asyncio.subprocess.Process)
        return child.pid, child.returncode

    pid, code = asyncio.run(scenario())

    assert code is not None
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_a_cancelled_caller_leaves_no_descendant_behind(tmp_path) -> None:
    """
    A descendant that inherited the pipes dies with the call.

    The pipes stay open while any writer holds them, so a grandchild that inherited
    them would keep the drain waiting for an end that never comes — and the caller
    would wait with it.
    """
    pidfile = tmp_path / "grandchild"
    script = (
        "import subprocess, sys, time\n"
        "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
        "open(sys.argv[1], 'w').write(str(child.pid))\n"
        "time.sleep(60)\n"
    )

    async def body() -> None:
        async with proc.spawn(*_python(script), str(pidfile)):
            await asyncio.sleep(60)

    async def scenario() -> None:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.5)
        task.cancel()
        # The cancellation has to finish: a drain that waits for a writer that is
        # never going to write is a hang, not a slow answer.
        with pytest.raises(asyncio.CancelledError):
            async with asyncio.timeout(10):
                await task

    asyncio.run(scenario())

    assert pidfile.exists(), "the child never started its descendant"
    assert _dead(int(pidfile.read_text()))


# ── the life of a group: the leader and its descendants ───────────────────────


def _leader_starting(tmp_path: Path, descendant: str, *args: str) -> list[str]:
    """
    A leader that starts a descendant and goes away at once.

    The descendant outlives its leader: the group is still there, but the head of
    it is not — and the head is the one whose exit code says nothing.
    """
    child_script = tmp_path / "descendant.py"
    child_script.write_text(descendant)
    leader = (
        "import subprocess, sys\n"
        f"subprocess.Popen([sys.executable, {str(child_script)!r}, {', '.join(repr(a) for a in args)}])\n"
    )
    return _python(leader)


def test_a_descendant_gets_the_signal_even_when_the_leader_is_gone(tmp_path, monkeypatch) -> None:
    """
    The exit of the leader says nothing about the group.

    A descendant that is still working gets the same request to stop, and it is a
    request: it ends on its own, well before the grace is over.
    """
    monkeypatch.setattr(proc, "TERM_GRACE", 2.0)
    marker = tmp_path / "term"
    pidfile = tmp_path / "pid"
    descendant = (
        "import os, signal, sys, time\n"
        "def bye(signum, frame):\n"
        "    open(sys.argv[1], 'w').write('term')\n"
        "    sys.exit(0)\n"
        "signal.signal(signal.SIGTERM, bye)\n"
        "open(sys.argv[2], 'w').write(str(os.getpid()))\n"
        "time.sleep(60)\n"
    )

    async def body() -> None:
        async with proc.spawn(*_leader_starting(tmp_path, descendant, str(marker), str(pidfile))):
            await asyncio.sleep(60)

    async def scenario() -> float:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.5)
        started = time.monotonic()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        return time.monotonic() - started

    elapsed = asyncio.run(scenario())

    # It handled the signal itself: only a SIGTERM lets a process write this.
    assert marker.read_text() == "term", "the descendant never got the signal"
    assert _dead(int(pidfile.read_text()))
    # It ended on the signal, not after the grace that would have killed it.
    assert elapsed < 2.0


def test_a_descendant_that_ignores_the_signal_is_killed_after_the_grace(tmp_path, monkeypatch) -> None:
    """
    A descendant that refuses the request is killed when the grace is over.

    The grace is measured by the group and not by the leader — the leader is
    already gone here.
    """
    monkeypatch.setattr(proc, "TERM_GRACE", 0.5)
    pidfile = tmp_path / "pid"
    descendant = (
        "import os, signal, sys, time\n"
        "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
        "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
        "time.sleep(60)\n"
    )

    async def body() -> None:
        async with proc.spawn(*_leader_starting(tmp_path, descendant, str(pidfile))):
            await asyncio.sleep(60)

    async def scenario() -> float:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.5)
        started = time.monotonic()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        return time.monotonic() - started

    elapsed = asyncio.run(scenario())

    assert elapsed >= 0.5, "the group was not given the grace"
    assert _dead(int(pidfile.read_text()))


def test_a_cancelled_caller_leaves_nothing_behind_during_sh(tmp_path) -> None:
    """A cancelled `sh()`: the child is gone, and the cancellation is handed back."""
    pidfile = tmp_path / "pid"
    script = "import os, sys, time\nopen(sys.argv[1], 'w').write(str(os.getpid()))\ntime.sleep(60)\n"

    async def scenario() -> None:
        task = asyncio.ensure_future(proc.sh(*_python(script), str(pidfile)))
        await asyncio.sleep(0.5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())

    assert _dead(int(pidfile.read_text()))


def test_a_cancelled_caller_leaves_nothing_behind_during_stream(tmp_path) -> None:
    """A cancelled `stream()`: the child is gone, and the cancellation is handed back."""
    pidfile = tmp_path / "pid"
    script = (
        "import os, sys, time\n"
        "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
        "while True:\n"
        "    print('line', flush=True)\n"
    )
    lines: list[str] = []

    async def scenario() -> None:
        task = asyncio.ensure_future(proc.stream(lines.append, *_python(script), str(pidfile)))
        await asyncio.sleep(0.5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())

    assert _dead(int(pidfile.read_text()))


def test_a_second_cancellation_does_not_cut_the_cleanup_short(tmp_path, monkeypatch) -> None:
    """
    The cleanup is not the caller's to interrupt.

    A cancellation that arrives while the group is being stopped does not shorten
    the grace and does not leave the child running — and the caller still sees the
    cancellation it asked for.
    """
    monkeypatch.setattr(proc, "TERM_GRACE", 1.0)
    pidfile = tmp_path / "pid"
    script = (
        "import os, signal, sys, time\n"
        "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
        "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
        "time.sleep(60)\n"
    )

    async def body() -> None:
        async with proc.spawn(*_python(script), str(pidfile)):
            await asyncio.sleep(60)

    async def scenario() -> None:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.5)
        task.cancel()
        # The cleanup is running now: the child ignores the signal, so it has to
        # wait out the grace.
        await asyncio.sleep(0.2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())

    assert _dead(int(pidfile.read_text()))


def test_a_process_that_ended_before_the_cleanup_does_not_hang() -> None:
    """A child that finished on its own: the cleanup has nothing left to stop."""

    async def scenario() -> int | None:
        async with proc.spawn(*_python("print('done')")) as child:
            await asyncio.sleep(0.3)
            assert child.returncode == 0
        return child.returncode

    async def guarded() -> int | None:
        async with asyncio.timeout(10):
            return await scenario()

    assert asyncio.run(guarded()) == 0


def test_a_descendant_that_leaves_the_group_cannot_hold_the_pipes_forever(tmp_path) -> None:
    """
    A descendant that leaves the group is out of reach — and it holds the pipes.

    The wait for the pipes is bounded and the pipes are closed anyway, so a
    process the caller cannot signal does not keep the caller waiting.
    """
    pidfile = tmp_path / "pid"
    descendant = "import os, sys, time\nos.setsid()\nopen(sys.argv[1], 'w').write(str(os.getpid()))\ntime.sleep(30)\n"
    holders: dict[str, object] = {}

    async def body() -> None:
        async with proc.spawn(*_leader_starting(tmp_path, descendant, str(pidfile))) as child:
            holders["child"] = child
            await asyncio.sleep(60)

    async def scenario() -> None:
        task = asyncio.ensure_future(body())
        await asyncio.sleep(0.5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            async with asyncio.timeout(10):
                await task

    asyncio.run(scenario())

    escaped = int(pidfile.read_text())
    try:
        child = holders["child"]
        assert isinstance(child, asyncio.subprocess.Process)
        # The pipes are closed: they are what would have held the caller.
        assert child._transport._closed is True
    finally:
        # The one thing the group cannot do: the caller kills it itself.
        os.kill(escaped, signal.SIGKILL)
