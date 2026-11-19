---
title: "the review points are what the crawler collects, and WB sends them only when asked"
date: 2026-11-19
tags: [api, crawler, data-model]
---

> **Partially supersedes** `2026-11-19-the-cashback-is-a-percentage-and-wb-sends-it-only-when-asked.md`:
> the cashback there is the **WB Wallet** cashback, which is not what this crawler collects.
> The rule "never read `price.cashback` as an amount of money" stays in force.

## Context

The crawler exists to find products that pay the buyer back. The owner means **«Баллы ВБ за
отзыв»** — WB points paid for a product review, the badge the storefront renders as
«N Б ЗА ОТЗЫВ». The previous decision read that as the **WB Wallet** cashback
(`price.cashback`), which is a different thing: a percentage of the price paid back into the
wallet, offered by a third party.

The two never overlap. Measured on the live endpoint, `menu_v3_9467 карты памяти`, the same
catalog query with three different requests:

| request | `total` | products | with `feedbackPoints` | with `price.cashback` |
|---|---|---|---|---|
| plain catalog query | 50241 | 100 | 2 (250 Б) | 0 |
| `+ ffeedbackpoints=1` | 19 | 19 | 19 | 0 |
| `+ fcashback=1` | 15 | 15 | 0 | 15 (1 %..3 %) |

So the plain request answers «almost nothing has points» — the field is present but empty
for 98 of 100 products — and `fcashback=1` asks WB for a *different* product set, one without
a single review point. The filter that answers the crawler's question is
`ffeedbackpoints=1`: it narrows 50241 products down to 19, and all 19 carry points
(80, 120, 200, 250, 300, 400, 450 Б).

The evidence is committed as a sanitized bundle:
`devtools/wb-research/investigations/2026-11-19-feedback-points-filter/evidence/feedback-points-filter.json`.

## Idea

- (a) Keep collecting wallet cashback. — It answers a question nobody asked; the products
  with wallet cashback carry no review points, so the crawler would still find nothing.
- (b) Ask for both filters at once. — WB's filters intersect, and the intersection of the
  measured sets is empty: points and wallet cashback never appear together in this category.
- (c) Ask for the filter that names the thing being collected, and store that field.

## Decision

The catalog request carries `ffeedbackpoints=1` and no longer carries `fcashback=1`. The
review points are stored as their own value, end to end, and never as a cashback percentage.

- `feedbackPoints` is the field as it came (`250` → `250` points), nullable: a product
  without a review offer has no points.
- It travels as its own column (`feedback_points`), its own API field and its own CLI
  column (`points`). It is **not** folded into `cashback_percent`: points are a count, a
  cashback percentage is a rate, and one column cannot hold both without a unit tag.
- The wallet cashback stays in the model as a percentage (see the superseded ADR) and is
  read when WB sends it. It is no longer requested — the request that asks for it returns a
  set of products with no review points.
- The catalog is made of the observations that carry points, and the membership is decided
  on the **newest** observation of a product: a product whose newest observation pays no
  points is out of the catalog, even when an older observation of it did. The condition is
  applied to the read model and not inside it — inside, it would pick the newest observation
  that pays points and keep the product with a price it does not have. The detail read
  applies it too: an observation outside the catalog is not found. And the detail read
  answers about the **current** observation of the product: an older observation is not
  found either, because the product left the catalog when the newer observation arrived.

## Rationale

`ffeedbackpoints` is WB's own filter for «Баллы ВБ за отзыв» — the storefront lists it in
`resultset=filters` as «Баллы ВБ за отзыв» → «Да». Asking for it is how WB itself asks the
same question, so the answer is the set the storefront shows, not a subset reconstructed
locally.

The two readings of «кешбек» were separated by measurement, not by argument: the plain query
and `fcashback=1` both return products with `price.cashback = 0`, and only `ffeedbackpoints=1`
returns products with points. A crawl under `fcashback=1` would have collected 15 products
and reported zero review points for every one of them — the exact failure the owner
reported, dressed up as a fix.

## Consequences

- **Always** send `ffeedbackpoints=1` with a catalog request. Without it WB answers mostly
  zeros, and a crawl reports success while storing almost no points.
- **Never** read `price.cashback` as an amount of money. It is a percentage. (Unchanged from
  the superseded ADR.)
- **Never** send `fcashback=1` with a catalog request. It selects a product set without
  review points.
- **MUST** keep review points and cashback percentage in separate fields, columns and
  outputs. They are different units.
- **Always** decide whether a product is in the catalog on its newest observation, and
  apply the same condition to every read of the catalog — the list, the targets, the top
  deals and the detail read. A read that answers about a product the list leaves out is
  two answers about two catalogs.
- **Always** let the detail read answer about the current observation of the product. An
  old observation is a price the product does not have, and the id of it is not an id the
  storefront can show.
- **Always** decide what is current inside the target the row belongs to. A product is
  observed per catalog, so a newer observation of it in another target is another product
  of another catalog — and a detail read that answered 404 for a row the list of its own
  target shows would be two answers about one catalog.
- A crawl of a category now yields the review-point-carrying products of that category only
  (tens, not hundreds per category) — the catalog shrank because the catalog *is* the
  answer to "which of these products pay for a review".
- Products crawled before this change keep their stored `feedback_points = NULL`; they are
  re-crawled, not migrated.
- The catalog's cashback-based sort, its `minCashback` filter and its "best cashback" list
  have nothing to sort by any more: no crawled product carries a wallet cashback, because the
  request does not ask for one. They keep working — they just report empty and order by
  recency. Sorting the catalog by review points is a separate decision, and this one does not
  make it.
