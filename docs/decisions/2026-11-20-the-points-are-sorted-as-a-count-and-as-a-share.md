---
title: "the points are sorted as a count and as a share of the price, and a window is a bound on the observation"
date: 2026-11-20
tags: [api, crawler, data-model]
---

## Context

The catalog is made of the products that pay review points («Баллы ВБ за отзыв»), and the
operator's first question about that catalog is *which of these is worth buying*: the points
a product pays, and the points relative to what it costs. A product that pays 400 points and
costs 1 000 ₽ is a better deal than one that pays 450 and costs 4 000 ₽, and the table could
show neither ranking.

The same question has a time dimension: "what did the last run collect" and "what was
observed today" are the two windows an operator checks after starting the crawler, and the
catalog API had no way to ask for either.

Both are questions about a **paginated** list. Sorting or filtering one loaded page in the
terminal answers about that page and lies about the rest, and loading the whole table to sort
it locally is not an option on a table of millions of observations.

## Idea

- (a) Sort and filter in the TUI over the page it has loaded. — The page is 20 rows of an
  ordered result; a local sort reorders those 20 and calls it "the most points", which is a
  wrong answer dressed as a list. The presets have the same defect: filtering a page by
  time drops rows that should have moved into the page.
- (b) Load every matching row and sort locally. — The crawler's table is measured in
  millions of observations; the list is paginated for that reason.
- (c) Extend the read API: the orders and the bound become part of the contract, and the
  terminal asks for them like any other client.

## Decision

The API gains two sort orders and one filter; the terminal gains a column and a preset
select.

- `FeedbackPointsDesc`/`FeedbackPointsAsc` order by `feedback_points` as a count.
- `FeedbackPointsShareDesc`/`FeedbackPointsShareAsc` order by
  `feedback_points × 10 000 / COALESCE(sale_price_kopecks, price_kopecks)`: the points as a
  share of the price the product is sold at, in percent. **A point is a ruble** — WB credits
  a point to the wallet as a ruble, so the two values are in the same unit and the share is
  one divided by the other. The price is the one the card shows (the sale price when there is
  one), and a zero or absent price is `NULL`, not a division.
- Every new order ends with the row id, like the existing ones: a page boundary stays exact.
- `scrapedAfter` (ISO-8601 with an offset) keeps the products whose **current** observation
  was made at or after the moment. It is applied to the read model, not inside it: a product
  observed last week and again today is in the answer with the price it has now, and one last
  observed last week is out.
- The terminal's items tab names the windows it knows: `all` (no bound), `today` (local
  midnight of the operator's machine) and `last run` (the `started_at` of the newest crawl
  from `/api/admin/jobs`). They are three names for one server-side parameter.
- The points share is a column of the terminal's table, computed from the two fields the card
  already carries. It is **not** a field of the contract: the API orders by it, the client
  renders it, and neither stores it.

## Rationale

The API owns the SQL (see `DIGEST.md`: the catalog is read through the API, and the API owns
the SQL). An order or a filter that the client applies to a page is a second, wrong
implementation of a query — and the two implementations drift the first time either changes.

The share is derived, and putting it in the contract would freeze a unit decision into a
field every consumer has to keep: the same card would carry points, price and a percentage
that must agree with both. The sort is where the ratio is compared, so the ratio is computed
there.

The bound is a moment, not a "preset": the API does not know what "today" means to a given
operator, and a server that decided would answer differently in a different timezone. The
terminal names its windows and sends a moment.

## Consequences

- **Always** order and filter a paginated list inside the API, on the whole result. A sort or
  a filter applied to one page is an answer about that page, not about the list.
- **Always** end a new sort fragment with `i.id`, and **always** keep
  `CatalogSort` and the contract's `sort` enum the same list — `CatalogOpenApiTest` checks the
  second, `CatalogSortTest` the first.
- **Never** store the points share: it is `points / price`, derived where it is compared. A
  stored share is a third value that can disagree with the two it came from.
- **Always** read the share against the price the card shows (`COALESCE(sale_price, price)`),
  so the ranking and the column agree with the card.
- **Always** keep review points a count in the data model: the share is a ratio derived for a
  comparison, not a new unit of the column (`2026-11-19-the-review-points-are-what-the-crawler-collects.md`
  stays in force).
- **Always** send a timestamp with an offset and refuse one without: a local time means the
  server's zone, and the same URL would then mean different moments in different deployments.
- **Always** treat `scrapedAfter` as a bound on the current observation, not on the history:
  the list answers about products, and a product's membership and price are decided on its
  newest observation.
- A window in the terminal is a name for one bound. "Last run" with no run recorded has no
  bound and no rows: there is nothing a crawl has collected yet.
- The storefront (another repository) validates its sort values against its own copy of the
  contract; the eight orders it accepted keep their meaning, and the four new ones reach it
  as ordinary additions.
