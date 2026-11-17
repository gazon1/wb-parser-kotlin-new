---
title: "The log tab's follow is a cancellable worker, and the app waits for it"
date: 2026-11-18
tags: [python, tui, architecture]
---

## Context

The TUI's log tab watches `docker compose logs`. The follow never ends on its own,
so something has to end it: the operator's Stop button, a second Follow, or the TUI
itself being closed.

Three things were in the way.

The follow was a thread worker with a `subprocess.Popen` in it, and the process was
stopped by `terminate()`. A thread cannot be cancelled, so "Stop" was a request to a
thread that might be blocked reading a pipe, and the process outlived the request
whenever the thread did not get there first.

Textual cancels the workers when the app goes, but it does not wait for them: the
`docker compose logs` was signalled during the shutdown and left to die on its own,
and its pipes were closed by the garbage collector after the event loop was gone.
That is the "Event loop is closed" warning the subprocess transport's finaliser
prints — the child process was cleaned up by an accident, not by a rule.

And the buttons were not on the screen. A Textual `Input` is `width: 100%` by
default, so the "lines" input in the log row took the whole row and pushed Load,
Follow and Stop off the right edge. The log tab looked right and could not be used:
Stop was off-screen, and the acceptance criterion "Stop stops the follow" could not
even be tried by hand.

## Idea

1. Keep the thread worker and make the pipe reading cancellable.
   Rejected: a thread is not cancellable, and the subprocess layer that was built
   for exactly this (`proc.py`) is async.
2. Let Textual's own cancellation be enough, and trust the shutdown.
   Rejected: Textual cancels without waiting, and the child was still alive when the
   app was gone.
3. The follow is a worker of its own group, the group is cancelled on Stop and on
   unmount, and the app waits for the cancelled worker (chosen).

## Decision

**The follow is a Textual worker in its own group, and it is exclusive.**

`follow_logs` is `@work(exclusive=True, group="follow")`. The group is what makes
the three ways of ending it one rule:

| What the operator does | What happens |
|---|---|
| Press Stop | the "follow" group is cancelled |
| Press Follow again | the new worker is exclusive in the group, so the old one is cancelled first |
| Close the TUI | the app cancels the group in `on_unmount` and waits for it |

Nothing else keeps a handle on the worker. There is no `_follow_proc`, no
`stop_follow()`, and no field that says "the worker is running" — the group is the
state.

**Cancelling the worker cancels the child.** The child is started through `proc`,
and a cancelled scope terminates the child's process group, kills it if it does not
go away, waits for it and closes its pipes. That is the subprocess layer's rule,
and it is why the TUI does not need to know how a process is stopped.

**The app waits for the cancelled worker.**

Textual cancels the workers when the app goes, but it does not wait for them. The
app's `on_unmount` cancels the "follow" group and awaits each cancelled worker, so
the child is gone before the app is. Without that wait the child was still running
when the app closed, and the pipes were closed by the garbage collector after the
event loop had gone — the cleanup happened by accident, and the accident had a
warning attached to it.

**The log row's Input is eight columns wide.**

A Textual `Input` is `width: 100%` by default. In the log row that pushed the
buttons off the screen, and a Stop button that cannot be pressed stops nothing. The
rule is written down in the app's CSS next to the other widths, and a test asserts
that the three buttons are inside the screen.

**A follow that ends with a non-zero code says so.**

The CLI's `logs` fails when `docker compose logs` fails. The TUI's follow now says
the same thing instead of ending in silence, and a follow that ends by itself with
code 0 says nothing — that is the normal end of a watch.

## Rationale

The group is the smallest thing that answers "is a follow running?". A field on the
app — `self._follow_worker` — is the same answer written down twice: once in the
app and once in the worker manager, and the copy in the app is the one that goes
stale. The group is where the worker already lives, so cancelling it is the whole
of "Stop".

Waiting for the cancelled worker is the difference between "the child was told to
stop" and "the child is gone". The subprocess layer already guarantees the second
thing for its own scope; the app only has to give the scope the chance to finish.
It is a few lines, and they are the difference between a promise and an accident.

The button that cannot be pressed is worth its own paragraph because it is the
kind of defect that survives review: the CSS is a string, the layout looks right in
a screenshot of the code, and the bug is only visible to an operator with a wide
terminal who tries to press Stop. A test that asserts the buttons are inside the
screen is cheap and permanent.

## Consequences

- **Always** end the follow by cancelling the "follow" group, and **Never** by
  holding a handle on the worker: the group is the state.
- **Always** await a cancelled worker before the app goes. A cancelled worker that
  nobody waits for is a process that is still running when the app is gone.
- **Never** read a child's output in a thread. The subprocess layer reads it
  asynchronously, and a thread cannot be cancelled.
- **Never** leave a widget's default width in a row of buttons: a Textual `Input`
  is 100% wide, and it pushes what follows it off the screen.
- **Always** say what happened when a follow ends badly: a non-zero exit code is a
  message, not silence.
- **Always** prove the log tab with a substituted executable, and ask the process
  itself whether it is still there — a mock cannot answer "is it still running?".
