"""
proc — the one way this tool starts a child process.

Gradle, Docker Compose and the crawler jar are all started from here, so the rules
live in one place: the argv is a list (never a shell string), the child's output is
read as it arrives, and the child never outlives the call that started it.

The module knows nothing about Rich, Textual or the crawler. It hands the output to
a callback and returns an exit code; what to do with either is the caller's business.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import signal
import time
from collections.abc import AsyncIterator, Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# How long the group may take to end itself after `SIGTERM` before it is killed.
TERM_GRACE = 5.0

# How long the group may take to disappear after `SIGKILL`.
KILL_GRACE = 1.0

# How long the pipes may take to reach EOF before they are closed anyway.
DRAIN_GRACE = 1.0

# How long the leader may take to report its exit once the pipes are closed.
LEADER_GRACE = 1.0

# How often the group is asked whether it is still there.
POLL = 0.05


class ProcError(Exception):
    """The program could not be started at all — there is nothing to report from it."""


@dataclass
class Result:
    """What a finished command left behind: its exit code and what it said."""

    code: int
    stdout: str
    stderr: str


@contextlib.asynccontextmanager
async def spawn(
    *argv: str,
    env: Mapping[str, str] | None = None,
    cwd: Path | str = ROOT,
    merge_stderr: bool = False,
) -> AsyncIterator[asyncio.subprocess.Process]:
    """
    Start a child and own its life: when the scope ends, the child is gone.

    `env` replaces the environment, the way `exec` does — a caller that wants the
    shell's environment passes it on. stdout and stderr are pipes, so a command that
    talks a lot cannot fill a buffer and wait for a reader that is not there.

    A cancelled scope is not an exit: the child's group is terminated, then killed if
    it does not go away, and the scope waits for it either way.

    The child leads a process group of its own, so a descendant that inherited the
    pipes dies with it: the pipes reach their end instead of waiting for a writer
    that is not ours to kill.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            env=dict(env) if env is not None else None,
            cwd=cwd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT if merge_stderr else asyncio.subprocess.PIPE,
            start_new_session=True,
        )
    except FileNotFoundError as error:
        raise ProcError(f"'{argv[0]}' is not installed") from error
    except OSError as error:
        raise ProcError(f"'{argv[0]}' could not be started: {error}") from error

    try:
        yield proc
    finally:
        await _stop(proc)


async def sh(*argv: str, env: Mapping[str, str] | None = None, cwd: Path | str = ROOT) -> Result:
    """Run a short command and wait for it: the exit code and everything it said."""
    async with spawn(*argv, env=env, cwd=cwd) as proc:
        out, err = await proc.communicate()
        return Result(
            code=proc.returncode if proc.returncode is not None else 0,
            stdout=_text(out),
            stderr=_text(err),
        )


async def stream(
    say: Callable[[str], None],
    *argv: str,
    env: Mapping[str, str] | None = None,
    cwd: Path | str = ROOT,
) -> int:
    """
    Run a command and hand its output to `say`, line by line, as it arrives.

    stderr is merged into stdout: one pipe, one reader, no deadlock, and the lines
    keep the order the child wrote them in.
    """
    async with spawn(*argv, env=env, cwd=cwd, merge_stderr=True) as proc:
        if proc.stdout is None:  # pragma: no cover - spawn always pipes stdout
            raise ProcError("the child's output is not readable")
        async for line in proc.stdout:
            say(_text(line).rstrip("\n"))
        await proc.wait()
        return proc.returncode if proc.returncode is not None else 0


async def _stop(proc: asyncio.subprocess.Process) -> None:
    """
    End the child and wait for it: no process outlives the scope.

    The teardown runs in a task of its own, and it is shielded: a cancelled caller
    still gets a dead child, and the cancellation is handed back once the child is
    gone. A second cancellation does not cut the teardown short either.
    """
    closer = asyncio.ensure_future(_teardown(proc))
    try:
        await asyncio.shield(closer)
    except asyncio.CancelledError:
        # The caller is being cancelled and the child still has to die: the teardown
        # continues even though the cancellation is on its way through.
        while not closer.done():
            with contextlib.suppress(asyncio.CancelledError):
                await asyncio.shield(closer)
        # The teardown's own failure is not the caller's error, but it must not be
        # left unclaimed either.
        with contextlib.suppress(BaseException):
            closer.exception()
        raise


async def _teardown(proc: asyncio.subprocess.Process) -> None:
    """
    Stop the group, then the pipes, then the leader.

    The group goes first and gets a grace period of its own. The leader exiting says
    nothing about its descendants, and a descendant that inherited the pipes keeps
    them open — so the pipes are only read once the group is gone.

    The pipes come before the leader: Python reports the exit of a child only once
    its pipes have reached their end, so waiting for the leader first would wait for
    a reader that is not there yet.
    """
    group = proc.pid  # the child leads its own group
    _signal(group, signal.SIGTERM)
    if not await _group_is_empty(group, TERM_GRACE):
        _signal(group, signal.SIGKILL)
        await _group_is_empty(group, KILL_GRACE)
    await _drain(proc)
    await _wait(proc)


async def _wait(proc: asyncio.subprocess.Process) -> None:
    """
    Wait for the leader to report its exit.

    The wait is bounded: the child is gone by now, and a teardown that never ends is
    a hang, not a slow answer.
    """
    with contextlib.suppress(asyncio.TimeoutError):
        await asyncio.wait_for(proc.wait(), LEADER_GRACE)


async def _group_is_empty(group: int, timeout: float) -> bool:
    """
    Wait for the group to have no members left.

    The grace period is measured by the group and not by the leader: a descendant
    that ignores the signal is exactly what the grace period is for.
    """
    deadline = time.monotonic() + timeout
    while _alive(group):
        if time.monotonic() >= deadline:
            return False
        await asyncio.sleep(POLL)
    return True


def _alive(group: int) -> bool:
    """Whether the group still has members."""
    try:
        os.killpg(group, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _signal(group: int, sig: int) -> None:
    """Signal the whole group: a descendant that inherited the pipes dies with it."""
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(group, sig)


async def _drain(proc: asyncio.subprocess.Process) -> None:
    """
    Close the pipes of a child that is gone.

    A child that wrote more than the pipe held and was then killed leaves its output
    unread: the transport would wait for a reader that never comes, and it would
    outlive the event loop. Reading what is left closes both pipes.

    A descendant that detached itself into a session of its own can still hold the
    write end open. That is the one thing the group cannot reach, so the wait is
    bounded and the pipes are closed anyway.
    """
    try:
        await asyncio.wait_for(proc.communicate(), DRAIN_GRACE)
    except asyncio.CancelledError:
        # The pipes have to be closed even when the caller is cancelled again.
        _close_pipes(proc)
        raise
    except Exception:  # noqa: BLE001 - the child is gone, the pipes are the leftover
        _close_pipes(proc)


def _close_pipes(proc: asyncio.subprocess.Process) -> None:
    """
    Close the read ends of the pipes.

    `Process._transport` is private API: it is the only handle on them, and they are
    what outlive the call when a descendant kept them open. It is one function, so
    it is one place to change if Python grows a public way to close them.
    """
    transport = getattr(proc, "_transport", None)
    if transport is not None:
        with contextlib.suppress(Exception):  # noqa: BLE001 - the pipes are the leftover
            transport.close()


def _text(raw: str | bytes | None) -> str:
    if not raw:
        return ""
    return raw.decode("utf-8", errors="replace") if isinstance(raw, bytes) else raw
