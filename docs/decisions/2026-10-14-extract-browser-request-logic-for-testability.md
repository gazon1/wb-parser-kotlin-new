---
title: "Browser fetch extracts request construction instead of excluding itself from coverage"
date: 2026-10-14
tags: [infrastructure, testing, coverage, browser]
---

## Context

Adding [BrowserPool] and [BrowserDownloader] dropped `wbparser` line coverage from 47.04% to
39.40%, tripping the blocking floor in `config/gates/coverage-floor.txt` (45%). The new code
contributed roughly 1 150 uncovered lines.

The cause is structural, not sloppiness. `BrowserPool.ProfileState` calls `connectOverCDP`,
spawns `ss` for a port scan and issues HTTP to the AdsPower Local API; `BrowserDownloader`
drives a `Page` and evaluates JavaScript in it. None of it is reachable from a unit test
without a running AdsPower Chrome, which CI does not have.

The easy way out was to exclude those two files from Kover and return to ~48%. That was
rejected: this repo has two dedicated gate-integrity checks (`check-gate-wiring.py` Parts A/B,
"a gate that reports success forever because it cannot run"), and silently removing code from
what a coverage gate measures is the same failure wearing a different hat.

## Alternatives considered

1. **Exclude the adapters from Kover.**
   Rejected as gate-defeating. It would also be dishonest: the excluded code is genuinely
   untested, and hiding it would make the 45% floor describe less than it appears to.

2. **Write tests with Playwright mocks.**
   Rejected: the project forbids mocks in favour of fakes (`AGENTS.md`), and the only fake
   available here would be the 100+ method `Page` interface. Such tests would assert
   implementation details — that `navigate` was called — not behaviour.

3. **Extract the pure half and test it (chosen).** Split request construction out of the
   browser-touching adapter, then test what remains testable.

## Decision

Move everything in the fetch path that is a pure function of its inputs into
`WbCatalogRequest`: catalog-URL parsing, API-URL construction, header construction, the fetch
script constant and the jitter bounds. `BrowserDownloader` keeps the browser interaction and
delegates to it.

`BrowserFetchTiming` groups the remaining timing knobs that would otherwise push the
constructor past detekt's `LongParameterList`, and drops a `fetchTimeoutMs` parameter that was
declared but never read.

The browser adapters stay in the measurement. Their uncovered lines are the honest cost of
needing a real browser, and the floor is now met by testing real logic rather than by
narrowing what the gate looks at.

## Rationale

Coverage went 39.40% → 45.09% and the floor passes with margin, from tests that assert
decisions rather than plumbing.

Two of those tests immediately paid for themselves by finding live defects, both of the same
family as the bugs that motivated the browser work in the first place — a wrong request detail
that fails quietly instead of loudly:

- `jitterMs(-50, -10)` returned a **negative** delay, because `ThreadLocalRandom.nextLong`
  accepts negative bounds. That delay went straight into `waitForTimeout`, so a mistyped
  `browser-jitter-min-ms` would have thrown mid-request. Both bounds are now clamped at zero.
  (Since 2026-10-20 the function lives in `RequestPacer`: the request builder does not decide
  when a request may go out.)
- `WbItemDto.id` was a required field, so a single product missing `id` threw inside
  `decodeFromString`, the catch-all turned the whole response into an empty page, and the crawl
  reported success while storing nothing. The pre-existing `id <= 0` guard in `toParsedItem`
  was unreachable for an absent `id`. `id` now defaults to 0, which is what makes that guard
  do its job.

## Consequences

- **Always** keep request construction in the `infra/http` request objects, not back inside the
  adapter. A wrong `dest` or page number answers HTTP 200 with an empty `products` array, so the
  only defence is a test on the value that gets built. (Since 2026-10-20 those objects are
  `WbCatalogRequest` — the crawl URL, `WbCatalogApi` — the internal API call, and
  `WbCatalogFetchScript` — the script; see the ADR on splitting by decision.)
- **Never** solve a coverage drop on untestable adapters by excluding them. Extract the logic
  that deserves testing and leave the rest measured.
- **Never** give a Kover-exercised DTO a required field that a single malformed API entry can
  omit. Default it and let the item-level guard reject it.
- The browser adapters remain uncovered by design. Adding a browser-backed integration test is
  the only thing that will change that, and it needs a profile-per-test fixture CI does not
  have yet.