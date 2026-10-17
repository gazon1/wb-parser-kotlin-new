---
title: "BrowserPool testability — AdsPowerApi interface extraction"
date: 2026-10-17
tags: [browser, architecture, testing]
---

## Context

`BrowserPool.ProfileState.resolveCdpUrl()` calls `checkActiveV2()` and `startProfileV2()` directly via raw `HttpURLConnection`. This makes `BrowserPool` impossible to unit-test without a real AdsPower Local API server running.

Integration tests with a real AdsPower installation cover the end-to-end path, but unit-level assertions about the reconnect state machine, the `warm` flag lifecycle, and concurrent borrow/release semantics require a fast, deterministic fake.

## Idea

Extract a private `AdsPowerApi` interface inside `BrowserPool`. The production implementation calls the real AdsPower Local API. A `FakeAdsPowerApi` in the test source set returns canned CDP URLs deterministically.

No changes to the public API of `BrowserPool` — the interface is constructor-injected with a default of the real implementation.

## Decision

1. Define `AdsPowerApi` sealed interface with two members:
   - `checkActiveV2(profileId: String): String?` — returns CDP WS URL if active, null otherwise
   - `startProfileV2(profileId: String): String?` — starts profile, returns CDP WS URL, null on failure

2. Default implementation `RealAdsPowerApi` moves the current `checkActiveV2`/`startProfileV2` logic unchanged.

3. `ProfileState` accepts `AdsPowerApi` via constructor from `BrowserPool`.

4. `FakeAdsPowerApi(profileId: String, cdpUrl: String?)` — deterministic fake that returns the configured `cdpUrl` or null.

5. Unit tests in `BrowserPoolTest.kt`:
   - `borrowPage returns page from warm profile`
   - `health check failure triggers reconnect`
   - `borrowPage waits on locked profile then succeeds`
   - `concurrent borrows serialize correctly`

## Consequences

- **Always** inject `AdsPowerApi` via constructor for testability — never call `HttpURLConnection` directly in `ProfileState`.
- Playwright/CDP connection remains an integration concern — no attempt to mock it in unit tests.
- `FakeAdsPowerApi` lives in `src/test/kotlin/` alongside the tests that use it.
