---
title: "The read-only tabs are panes, and the app refreshes only the active one"
date: 2026-11-17
tags: [python, tui]
---

## Context

`scripts/parser/wbparser.py` grew a terminal front end one tab at a time. The
`StatsPane` pilot (#296) showed the shape: a pane owns its data, draws it, and
reports its own loading, error and updated-at state. The other five read-only
tabs did not follow. Each had a `load_*` method on the app, a `_render_*` method
beside it, a thread worker, and a `call_from_thread` to get from one to the
other. The app held the state of five tabs it did not draw, and each tab's
error path cleared the tables of that tab and left the operator with a summary
line that said "Unavailable".

The auto-refresh made it worse: the app polled the tabs it knew about, so a
hidden tab kept asking the API for data nobody was looking at.

## Idea

- Keep the per-tab `load_*` / `_render_*` pairs and only move the drawing into
  a pane. The loading and the errors stay where they were, and the app keeps
  knowing every tab by name.
- Give each tab its own pane, its own refresh button and its own status line,
  and let the app know nothing about them beyond the fact that a tab has a
  pane.
- Move the auto-refresh into every pane, so each pane refreshes itself. The
  app stops polling, but so does the operator's control over it: a pane the
  operator cannot see would still be asking.

## Decision

The five remaining read-only tabs — Status, Items, Runs, Schedule and History —
are panes, and each one owns what it reads and what it draws:

- `fetch()` is `async`, reads the pane's own widgets, and returns the data.
  The filters of the Items tab are read there, on the app's event loop: a
  widget read from a thread worker is a widget read while it may be changing.
- `show(data)` touches widgets and does no I/O.
- `reload()` is the only entry point, and it owns the three states — loading,
  error, and the time of the last success. An error replaces the pane's status
  line and keeps the last good data, which is the pilot's contract and is what
  an operator wants: a failed refresh must not empty the screen.
- Every pane carries its own `Static#pane-status` and its own refresh button.
  The base class watches for that button, so the app does not know which button
  belongs to which pane.

The app keeps a registry — `PANES`, a map from tab id to pane class — and does
three things: it assembles the tabs, it routes the actions that are not a
tab's business (Up, Down, Crawl, the log buttons), and it refreshes the active
tab. `_refresh_tab` is a dispatcher: a pane reloads itself, and the log tab —
which is not a pane — loads its logs.

The auto-refresh stays centralized in the app, because the app is the only
thing that knows which tab is visible. One timer, `REFRESH_EVERY`, refreshes
the active pane and nothing else. A hidden tab never polls the API. The first
`TabActivated` loads the tab the app opens with, so `on_mount` does not refresh
it a second time.

The temporary sync/async bridge from #297 is gone. The app action that starts
the stack waits for the application with `asyncio.run(_with_api(...))` on its own
worker thread, and the action that ends reloads the status pane through the app's
own thread.

## Rationale

The pane contract was already the pilot's shape, and a pilot that does not get
followed is a second dialect. Moving the tabs onto it means the loading, the
error and the updated-at state live in one place per tab, and the app is left
with the two things it is actually good at: composing the screen and knowing
what the operator is looking at.

Reading the filters inside `fetch()` is what makes the migration safe for the
Items tab. The old code read them from a thread worker and handed them to the
request; the pane reads them on the event loop, on the same tick as the reload,
so a value the operator typed cannot change between the read and the request.
The pagination behaves exactly as it did: the page and the size come from the
inputs, a value that is not a number falls back to the first page and to the
default size, and a load does not reset the page the operator is on.

The auto-refresh did not move into the panes on purpose. A pane that refreshes
itself does not know whether it is visible, so six panes would mean six timers
and six hidden pollers — the exact thing the issue asks to stop. One timer in
the app, aimed at the active pane, keeps the visible tab fresh and the hidden
tabs quiet.

## Consequences

- **Always** give a read-only tab a pane, not a `load_*` / `_render_*` pair on
  the app. A tab's data, its loading state and its error belong to the tab.
- **Always** read a filter from its widget inside `fetch()`, on the app's event
  loop; a widget read from a worker thread is a widget read while it may change.
- **Never** let a pane refresh itself on a timer. The app knows which tab is
  visible, and only the app can aim a refresh at it.
- **Never** let a failed refresh empty the pane. The error belongs in the
  status line and the last good data stays on screen.
- **Never** add a per-tab branch to `_refresh_tab` or `_on_button`. The registry
  is the map; a branch is a tab the app knows about twice.
- **Always** press a pane's button in a test of its refresh, rather than calling
  the method behind it. The method and the button are two paths, and only the
  button is the one the operator uses.
- **Always** prepare a pane's content before touching its widgets. The base class
  cannot roll a widget back, so a malformed answer has to fail while the content
  is built — otherwise a failed draw leaves the screen half-updated.
