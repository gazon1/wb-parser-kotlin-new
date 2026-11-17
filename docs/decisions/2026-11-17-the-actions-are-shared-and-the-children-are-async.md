---
title: "The actions are shared, and every child process is started by one async API"
date: 2026-11-17
tags: [python, tui, architecture]
---

## Context

`scripts/parser/wbparser.py` had two front ends — a Typer CLI and a Textual TUI —
and three things an operator can do to the stack: `up`, `down` and `crawl`. Each
front end had its own copy of those three things.

The CLI had them as commands that called `subprocess.run` through two helpers, `_run`
and `_compose`. The TUI had them as button handlers that called the same two helpers
from a thread worker, and reported back with `call_from_thread`. The rules were
therefore written twice: how the jar is built, what `docker compose` is asked for,
what environment a crawl runs in, when the stack is ready. A rule that exists twice
is a rule that will drift — and the readiness timeout is exactly the kind of rule
that has to be the same in both places.

The subprocess layer itself was blocking. `subprocess.run` cannot be cancelled: when
the operator cancelled the TUI worker that was building the jar, the child kept
running, and the pipe it was writing to had no reader. The blocking call was also
what forced the thread worker and the `call_from_thread` bridge in the first place.

## Idea

1. Keep the two implementations and unify only their names.
   Rejected: the two copies of the readiness rule are the bug, not the symptom.
2. Extract the whole HTTP layer into a module of its own, next to the subprocess one.
   Rejected: the issue is about the actions and the children, not about the HTTP
   layer, and moving it would have made the change much larger than the problem.
3. One asynchronous subprocess module and one set of shared actions, with both front
   ends calling them (chosen).

## Decision

**`scripts/parser/proc.py` is the only way a child process starts in this tool.**

It is a sibling module, not a package, and it knows nothing about Rich, Textual or
the crawler: it starts a process, reads its output and ends it. Three entry points:

| Function | What it is for |
|---|---|
| `spawn(*argv, env=, cwd=, merge_stderr=)` | a scope that owns a child's life: when the scope ends, the child is gone |
| `sh(*argv, env=, cwd=)` | a short command: its exit code, stdout and stderr |
| `stream(say, *argv, env=, cwd=)` | a command whose output is read line by line as it arrives |

The rules live there and only there: `asyncio.create_subprocess_exec` with the argv
as a list (never a shell string), `cwd` at the repository root, stdout and stderr as
pipes, and a child that is terminated and then killed when its scope ends. A scope
that is cancelled is not an exit: the child dies with the caller.

**What the scope ends is the child's process group, not its pid.**

The child leads a group of its own (`start_new_session=True`), and the scope ends
the group. The order is fixed:

1. `SIGTERM` goes to the group — always, whether the leader is still running or
   has already exited. The leader's exit says nothing about its descendants.
2. The group is given a grace period, and the grace is measured by the group: the
   group is asked whether it still has members (`os.killpg(pgid, 0)`). The
   leader's exit is not the answer — a descendant that ignores the signal is
   exactly what the grace is for.
3. Whatever is left of the group is killed, and the group is given a moment to
   disappear.
4. The pipes are read, with a bound.
5. The leader's exit is waited for, with a bound.

Waiting for the group and waiting for the leader are two different tasks and they
are not mixed: the leader is reaped on its own, and the group is asked about its
members on its own.

The pipes are read before the leader's exit is waited for, and the order is not
cosmetic: Python reports the exit of a child only after its pipes have reached
their end, so a child that was killed with its output still unread would never
report its exit — the teardown would wait for a reader that is not there yet.

A descendant that inherited the pipes keeps them open, and a pipe that stays open
never reaches its end — a grandchild would keep the drain waiting for a writer
that is not the direct child, and the call would wait with it.

One thing the group cannot reach is a descendant that detached itself into a
session of its own — Gradle's daemon does exactly that. Such a descendant is not
ours to kill, so the wait for the end of the pipes is bounded, and the pipes are
closed anyway when the bound is reached. Closing them reaches for a private
handle (`Process._transport`), and it is one function, so that it is one place to
change when Python grows a public way to do it.

The teardown runs in a task of its own, and it is shielded: a cancelled caller
still gets a dead child, a second cancellation does not cut the teardown short,
and the cancellation the caller asked for is handed back once the child is gone.

Process groups are a POSIX thing: `start_new_session` and `os.killpg` are what
the scope is built on, and the project is Linux-only.

The question the group is asked has one blind spot: a zombie is still a member of
its group, so a descendant that died and was not reaped yet keeps the group
alive. The cost is bounded and it is not a leak — the grace periods are spent on
a process that is already dead. Measured: on a host whose init reaps orphans a
cancelled call takes 0.05 s; in a container whose PID 1 never reaps, it takes
the two grace periods, 6.0 s. The layer does not read the members' states from
procfs: that would be a second mechanism for one environment, and it waits for a
delay that has to show up in practice first.

**The three actions live in `wbparser.py` and are shared by both front ends.**

`action_up`, `action_down` and `action_crawl` are coroutines. Each takes a `say`
callback and returns an exit code — `EXIT_OK`, `EXIT_WRONG` or `EXIT_UNUSABLE` — and
raises `Unusable` for a precondition that is missing (no jar, no database). The
callback is the only difference between the two front ends: a Rich console in the
CLI, a `RichLog` in the TUI.

The CLI runs them with `asyncio.run(action_*(...))`: one event loop per command, at
the sync boundary, and nowhere else. The TUI runs the same coroutine in a Textual
worker, so the event loop keeps running while Gradle builds or a crawl runs, and
cancelling the worker cancels the child.

The executables are named by three seams — `_gradle_command()`, `_compose_command(*argv)`
and `_java_command()` — so a test can substitute a child without Gradle, Compose or a
JVM. That is the same reasoning as every other seam in this repository: the rule is
one place, and the test proves the rule without the stack.

The TUI's Up, Down and Crawl buttons live in an `Actions` widget that runs the
action in a worker of its own group. The buttons wait for the action rather than
cancelling each other: one action at a time, and a cancelled action is not left
half-done under the operator's feet.

The TUI's Down asks first. A confirmation screen comes up before the subprocess
starts, and a refusal stops nothing at all: the stack stays up, and the operator
sees why. Escape is a refusal as well — it is the way out of a question, and the
way out of this one is "no". The CLI's `down` is a command on a command line — it
does what it says, and `--volumes` is the only way to delete the rows.

**The behaviour that was there before is still there.**

The exit codes are the same: a stack that does not become ready is `1`; a missing
jar, a missing database and a failed build are `2`. `down --volumes` still removes
the database volume, and the TUI's Down never does — it has no `--volumes` behind
it. The messages are the same, and the child's output is escaped before it reaches
Rich, so a log line that looks like markup is a log line and not a style.

One thing is new: the TUI's Down asks before it stops the stack. Stopping the
crawler is the one button whose consequence the operator cannot undo by clicking
again, so it is worth a question — and the question is asked before the subprocess
starts, not after.

## Rationale

The subprocess layer was the thing that forced every workaround: the thread worker,
the `call_from_thread` bridge, the cancellation that could not cancel. Moving it to
one asynchronous module removes the reason for all three at once, and it makes the
cancellation rule a property of the layer instead of a promise in a handler.

A pid is not enough for a promise about processes. The child can have descendants,
and the descendants inherit the pipes — so ending the child alone can leave a pipe
open forever, with the drain waiting for an end that never comes. The process group
is the smallest unit that answers the question the layer is actually making: does
anything that belongs to this call outlive it?

The same is true of the exit of the leader: it is the end of one process, not of
the group it leads. A layer that treats it as the end of the group leaves the
descendants running and their pipes open, which is the failure this layer exists
to prevent.

Keeping the actions in `wbparser.py` rather than in a module of their own is a
deliberate boundary. The actions are the operator's vocabulary — they are what the
CLI commands and the TUI buttons mean — and they speak in terms of the tool's own
model: the jar, the stack, the crawl. Splitting them away would have meant a new
module boundary for a hundred lines, and the boundary the issue asks for is the one
that matters: a layer that starts processes knows nothing about the tool that asks
for them.

The `say` callback is the seam between the two front ends. A widget cannot be
passed into a function that the CLI also calls, and a console cannot be written into
a `RichLog`; a function that takes a line and a caller that decides where the line
goes is the smallest thing both can share.

The exit code is the answer to "what happened", and `Unusable` is the answer to
"could this have happened at all". Keeping them apart is what preserves the exit
codes the shell already depends on: a failed crawl is a wrong answer (`1`), and a
missing jar is a command that could not run (`2`).

## Consequences

- **Always** start a child process through `proc`. There is no other way in this
  tool, and `subprocess` is not imported anywhere in it.
- **Never** pass a shell string: the argv is a list, and `shell=True` does not exist
  in this tool.
- **Always** let the scope own the child. A cancelled scope terminates the child's
  process group, kills it if it does not go away, waits for it either way, and
  closes its pipes — no process and no pipe outlives the call that started it.
- **Always** signal the whole group, even when the leader has already exited. The
  leader's exit says nothing about the descendants that are still working.
- **Never** measure the grace period by the leader's exit. The grace belongs to
  the group: the group is asked whether it still has members.
- **Never** wait for a pipe without a bound. A descendant that detached itself
  holds the write end, and a wait with no end is a hang.
- **Never** promise a prompt cancellation where orphans are not reaped. A zombie
  descendant counts as a member of the group, so the grace is spent on a process
  that is already dead — 6.0 s instead of 0.05 s, measured. The fix, if the delay
  ever shows in practice, is to read the members' states from procfs — not to
  shorten the grace.
- **Never** let a cancellation cut the cleanup short, and **Never** lose the
  cancellation the caller asked for. The cleanup is shielded, and it survives a
  second cancellation.
- **Always** run one `asyncio.run` per CLI command, at the sync boundary, and
  **Never** inside a coroutine, a worker or a polling loop.
- **Always** name an executable through a seam (`_gradle_command`,
  `_compose_command`, `_java_command`), so a test can substitute it.
- **Never** print a secret or an environment value. The crawl's environment is
  built for the child, and only the host, the port and the database name are said
  out loud.
- **Always** escape a child's output before it reaches Rich.
- **Never** let the TUI's Down remove the volume. Removing the rows is the CLI's
  `--volumes`, and only the CLI's.
- **Never** stop the stack from the TUI without the operator's confirmation. The
  confirmation comes before the subprocess starts, and a refusal starts nothing.
  Escape is a refusal.
- **Always** prove an action with a substituted executable. The action's contract is
  the argv it starts, the environment it passes and the exit code it returns — not
  whether Gradle is installed on the machine that runs the test.
