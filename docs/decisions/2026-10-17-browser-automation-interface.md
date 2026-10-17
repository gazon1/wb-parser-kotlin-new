---
title: "BrowserAutomation interface — extract Playwright CDP lifecycle from BrowserPool.ProfileState"
date: 2026-10-17
tags: [browser, architecture, testing]
---

## Context

`BrowserPool.ProfileState.getOrCreatePage()` calls `Playwright.create()`, `playwright.chromium().connectOverCDP()`, `browser.contexts().firstOrNull()`, and `context.newPage()` directly. This makes the state machine logic in `ProfileState` impossible to unit-test without a real Chrome + Playwright installation.

The `AdsPowerApi` abstraction (PR 18) already separates the profile-start API from `BrowserPool`. The remaining gap is the Playwright CDP connection lifecycle itself.

## Idea

Extract a `BrowserAutomation` interface from `ProfileState`. The production implementation (`CdpAutomation`) wraps the real Playwright calls. A `FakeBrowserAutomation` in the test source set records state transitions deterministically.

## Decision

1. Define `BrowserAutomation` interface with three members:
   - `val isConnected: Boolean` — whether a CDP session is active
   - `fun healthCheck(): Boolean` — quick CDP roundtrip to verify browser is alive
   - `fun connect(cdpUrl: String): Page` — connect via CDP and return the first page
   - `fun close()` — shut down the browser

2. `CdpAutomation` implementation wraps the existing `ProfileState` Playwright logic unchanged.

3. `ProfileState` accepts `BrowserAutomation` via constructor from `BrowserPool`.

4. `BrowserPool` passes a `CdpAutomation` factory to each `ProfileState`.

5. `FakeBrowserAutomation` in tests: records `connect` calls, returns a fake `Page`, `isConnected` and `healthCheck` are configurable.

## Consequences

- **Always** inject `BrowserAutomation` via constructor for testability — never call `Playwright` directly in `ProfileState`.
- `BrowserPool` remains the pool manager (round-robin, locking); `BrowserAutomation` handles only the browser lifecycle.
- `FakeBrowserAutomation` lives in `src/test/kotlin/` alongside `BrowserPoolTest`.
- `BorrowedPage` remains unchanged — it still wraps a real `Page`.
