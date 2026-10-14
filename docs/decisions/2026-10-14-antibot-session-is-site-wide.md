---
title: "Anti-bot warm-up is amortized per profile, not per request"
date: 2026-10-14
tags: [infrastructure, browser, antibot, performance]
---

## Context

`BrowserDownloader` drives WB through `page.evaluate(fetch())` inside AdsPower Chrome, because
plain HTTP gets HTTP 498. Called from a *fresh* page context, the internal API
(`__internal/u-recom/personal/v9/search`) answers `HTTP 200 {"products": []}` — the request
succeeds, WB just reports nothing, because the anti-bot session cookies are not there yet.

The first fix was to navigate the page to the catalog URL before every API call. That worked,
but measured **4.1 s per page** across a 132-page / 13 200-item run (528 s and 541 s on two
consecutive runs). Decomposing that 4.1 s showed it was almost entirely self-inflicted:

| step | cost |
|---|---|
| `page.navigate(catalogUrl, LOAD)` | ~1.5–2 s |
| `page.waitForTimeout(2000)` | 2.0 s |
| `cookies()` + `evaluate(fetch)` | ~0.3 s |

Two independent signals said the navigation was paying for itself repeatedly:

1. `BrowserPool.getOrCreatePage()` already navigated to `wildberries.ru` and slept 3 s — the
   same warm-up, paid a second time, per request.
2. The working Python reference (`scripts/wb_browser_fetch.py`) navigates **once**, then loops
   `page_num` 1..N calling the API from that same page context.

## Alternatives considered

1. **Just drop the 2 s sleep** — keep the per-request navigation.
   Rejected as the endpoint, not the destination: it leaves ~2 s of SPA load per page for a
   session that is already valid. ~2x, when ~6x was available for the same edit.

2. **Keep navigating per request, but cache cookies in Kotlin.**
   Rejected: the cost is the navigation and the challenge wait, not the cookie read. Caching
   cookies does not remove the page load.

3. **Navigate once per profile (chosen).** Treat the WB session as site-wide, not per-catalog.

## Decision

The anti-bot session is **scoped to `wildberries.ru` as a whole**, not to a single `dest`.
`BrowserPool.ProfileState` carries a `warm` flag; `BrowserDownloader` navigates to a catalog
page and waits out the challenge **only when the borrowed profile is cold**, then calls
`markWarm()`. Every later request on that profile skips the navigation entirely and goes
straight to `evaluate(fetch)`.

The flag lives on `ProfileState`, not on the `BorrowedPage`, because the page is cached and
reused across borrows; `BorrowedPage` exposes it as a read-only snapshot taken under the
profile lock, plus `markWarm()` to set it.

Per-request pacing is a randomised delay in `[browser-jitter-min-ms, browser-jitter-max-ms)`
(default 200–500 ms), applied to **every** request including the first.

## Rationale

Steady state dropped to **0.66 s/page** — the fetch itself (~0.3 s) plus the pacing delay
(~0.35 s average). The whole run fell from 541 s to 113 s, because the ~29 s of warm-up is now
paid once per profile instead of once per page.

Correctness rests on `dest` being a query parameter, not part of the session: one warm
navigation is enough to call the API for *any* catalog. That is why the Python reference gets
away with a single navigation, and why the run produced byte-identical item counts (13 200 on
both the slow and fast runs, same 132 targets, all with a non-zero price).

The jitter is not a rounding nicety. A warm profile otherwise fires ~90 requests/minute from
one IP on a perfectly regular cadence, which is the shape WB's rate limiting looks for. A
constant delay would be worse than no delay — it would keep the metronome.

Parallelising across the 5 profiles was deliberately **not** done in this change: `CrawlRunner`
loops targets sequentially and its counters are plain `var`, so it needs its own concurrency
work rather than being bundled into a performance fix.

## Consequences

- **Always** reset `warm = false` when `getOrCreatePage()` opens a new page or when a profile
  is closed. A warm flag on a freshly connected page makes the API return empty results and
  the crawl silently saves nothing — the same symptom as the original bug.
- **Always** run one cold request per profile after a CDP reconnect; the flag is the only
  thing standing between a new profile and a silent zero-item crawl.
- **Never** remove the jitter expecting it to be free. It is ~0.35 s of the 0.66 s steady-state
  budget, but it is what keeps the request pattern from looking mechanical.
- `BrowserPool` no longer blocks a dispatcher thread with `Thread.sleep(3000)` at connect time.
  Warm-up now belongs to whoever needs a warm page, and is timed by the caller.
- `BrowserDownloader` takes `BorrowedPage` rather than `Page`, so the warm-up contract travels
  with the borrow instead of being re-derived at each call site.