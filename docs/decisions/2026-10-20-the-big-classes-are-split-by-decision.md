---
title: "The three large classes are split by the decision they hold, not by the layer they live in"
date: 2026-10-20
tags: [architecture, refactoring, maintainability]
---

## Context

Three classes had grown until one file held several unrelated decisions:

- `WbCatalogRequest` — 257 lines that owned the crawl-URL algebra (`catalogUrlOf`, `crawlUrlOf`,
  `parseCatalogUrl`, `nextPageUrl`), the internal API call (`buildWbApiUrl`, `buildWbHeaders`)
  and the JavaScript that issues it inside the browser (`FETCH_SCRIPT`).
- `Pipeline` — 710 lines that owned the coordinator loop, the concurrent download/parse batch
  and the sequential page processing (stop check, filter → enrich → save, pagination).
- `CrawlRunner` — 636 lines and a fifteen-parameter constructor that owned the advisory lock,
  the job row, the heartbeat, the per-target pipeline, the counters and the run loop.

The cost was not the line count. It was that the decisions which fail *quietly* sat far from
the tests that guard them, and that a test could not reach one of them without dragging in the
other two. `Pipeline`'s batch runner, for instance, could only be exercised through the whole
run loop.

`WbCatalogRequest` was already the subject of an ADR: it was created to keep request
construction out of `BrowserDownloader`. Splitting it did not undo that — it made the rule
sharper.

## Alternatives

1. **Leave them as they are.**
   Rejected. The three files were named in the tech-debt plan as the largest remaining
   structural cost, and the reason they were deferred was that a split "without a win" was
   assumed. The win is now visible: each piece has a name that says what it decides.

2. **Split by layer — one package per concern.**
   Rejected. It moves files around without moving decisions. A `WbCatalogApi` in a
   `catalog` package next to a `CatalogUrls` in a `urls` package is the same class in two
   homes; the split only becomes real when the two can change for different reasons.

3. **Split by decision (chosen).** Each new unit owns exactly one decision, and the units are
   named after that decision.

## Decision

**`WbCatalogRequest` → three objects, each with one owner:**

- `WbCatalogRequest` — the crawl URL: which catalog, which page, which token. It is the only
  place that knows what a catalog id is (`catalogIdOf`), so the referer builder does not carry
  a second copy of the pattern.
- `WbCatalogApi` — the internal WB call: the endpoint, its parameters, the headers.
- `WbCatalogFetchScript` — the JavaScript that issues the call inside the page.
- `WbUrlEncoding` — the encoding shared by the two URL builders, so a space is encoded the
  same way in both.

**`Pipeline` → three units:**

- `Pipeline` — the coordinator loop: claim a task, hand it to the batch, decide what to do
  with the outcome, keep the counters.
- `PipelineBatch` — the concurrent half: download + parse for up to `concurrency` tasks at a
  time, with the retry back-off pre-computed rather than waited on.
- `PipelinePageProcessor` — the sequential half: the stop check, the filter → enrich → save
  path, and the pagination step.

`PipelineBatch` takes the *same* `Coordinator` instance as the loop. Two coordinators would
mean two attempt counts, and every retry would look like the first attempt — the split is
only safe because the coordinator stays shared.

**`BrowserPool` → the pool plus six collaborators** (added 2026-10-20, PR #70):

The file was 943 lines and held five decisions: the AdsPower Local API, the Playwright CDP
lifecycle, the pool's leasing, the profile's own lifecycle, and the lease handle.

- `BrowserPool` — the pool: which profile to hand out, and when.
- `BrowserProfileState` — one profile's state: warm, resting after an anti-bot answer, last
  used, browser open. It used to be a private inner class of the pool, so every one of its
  answers was reachable only by going through the pool.
- `AdsPowerApi` / `RealAdsPowerApi` — the Local API contract and its implementation.
- `BrowserAutomation` / `CdpAutomation` — the Playwright contract and its implementation.
- `BorrowedPage` (+ `BorrowedPageHooks`, `BrowserPoolExhaustedException`) — the lease handle.
- `ChromeEndpointProbe` — the DevTools port probe.
- `AdsPowerConfig`, `BrowserProfileConfig` — the configuration.

`BrowserProfileState` needs four things it cannot know by itself — its index in the
rotation, how many profiles there are, which browsers the machine has, where to log — so
they travel in one `BrowserPoolContext` holder rather than as four more parameters.

The split paid down two suppressed detekt findings rather than moving them: `resolveCdpUrl`
now asks a helper for the AdsPower URL, and the baseline lost all three of its entries —
the third, `SideInterpreterRegistry`, had been stale since the `Side.SaveBatch` and
`Side.ScheduleRetry` interpreters were deleted.

**`CrawlRunner` → a coordinator plus four collaborators:**

- `CrawlRunner` — the run loop: lock → load due targets → crawl them → close the job row.
- `CrawlJobStore` — the `crawl_jobs` row: opening it, closing it, and the heartbeat that
  makes a crashed crawl detectable.
- `TargetCrawl` — one target: its pipeline, its start URL, its result.
- `CrawlMetrics` — the counters, including the ones for the limits and for the products that
  come back without a category name.
- `CrawlRunnerConfig`, `CrawlSource`, `CrawlHooks` — the knobs, the downloader/parser pair and
  the callbacks, so the constructor lists what the runner *is* rather than what it *does*.

The constructor of `CrawlRunner` now takes five parameters. `CrawlRunnerConfig` is a data
class, and detekt does not count data-class constructors — a fact established by experiment
rather than by reading the rule, because it decides how the knobs may be grouped.

## Rationale

Every piece now has a name that answers "what does this decide?", and the tests follow the
names: `WbCatalogRequestTest` covers the URL algebra, `WbCatalogApiTest` covers the API call
and the script.

The behavioural risk of such a refactor is that something quietly stops happening. Two such
risks were found and closed while making the split:

- The batch runner originally had its own `Coordinator`, which would have reset the attempt
  count of every retry.
- `WbCatalogApi` originally carried its own copy of the catalog-id pattern, which would have
  been a second place to keep in step with `WbCatalogRequest`.

Neither is visible in the counters — a retry that looks like a first attempt is a back-off
that does not grow, and a catalog id that is parsed two ways is a referer that points at the
wrong catalog — which is exactly the family of bug the split was meant to make easier to see.

## Consequences

- **Always** split a class by the decision it holds, not by the layer it sits in. A new unit
  gets a name that says what it decides.
- **Never** give a split unit a second copy of state the original owned. The batch shares the
  coordinator; the API builder asks the URL algebra for the catalog id.
- **Always** keep the request construction of the browser fetch in the `infra/http` request
  objects, not back inside the adapter. A wrong `dest` or page number answers HTTP 200 with
  an empty `products` array, so the only defence is a test on the value that gets built.
- **Always** keep a data class for the knobs of a class with many parameters. Detekt does not
  count data-class constructors, so the grouping costs nothing at the gate and buys a
  constructor that can be read.
- **Never** move a piece of behaviour into a collaborator without carrying the state it
  depends on. A split that changes what a piece knows is a behaviour change wearing a
  refactor's clothes.
