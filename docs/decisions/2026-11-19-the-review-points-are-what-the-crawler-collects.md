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
- Every read **names the population it answers about**. The table holds two populations that
  never overlap — the review points and the wallet cashback — and a read that hard-wired the
  catalog's membership would answer the cashback list with an empty result even while the
  table held observations that pay a cashback. The catalog's own reads (the list, the
  targets) ask for the review points; the top-deals list asks for the cashback; the detail
  read accepts either, because the id of a card can come from either list — the cashback
  branch with a match id, because that is the key the top-deals list groups by. The
  **counters** are not a list: they describe the catalog itself, so `total_products` counts
  **every** product of the table and `products_with_cashback` counts the **cashback**
  population. A counter that took the review points by default answers "how many products
  are there" with a number about one of the two populations, and a cashback counter read
  from that population is always zero — the populations never meet.

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
  apply the same condition to every read of the catalog — the list and the targets read the
  catalog's membership, the top-deals list reads the cashback one, and the detail read
  accepts either. A read that answers about a product the list leaves out is two answers
  about two catalogs, so the detail read serves only what a list shows.
- **Always** count the counters against the population they name: `total_products` counts
  every product of the table, `products_with_cashback` counts the cashback population, and
  neither takes the review points by default. The size of the catalog is not the size of
  one of its two populations.
- **Always** name the population a read answers about. The review points and the wallet
  cashback are two populations that never appear on the same product, so a read that
  hard-wired one of them would answer the other list with an empty result — the "best
  cashback deals" list was empty even while the table held observations that pay a
  cashback.
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
- **Always** let the read model's index carry every column the model reads. The model reads
  `feedback_points`, and an included column that the index does not carry makes the model
  visit the heap for every observation — the cost the covering index was built to avoid.
  The index's included columns cannot be changed in place, so `V18` builds the index again
  under a second name and renames it (see `docs/operations/catalog-index-rollout.md`).
- **Always** apply the migration that adds a column before the concurrent build that
  carries it. The read model's index carries `feedback_points`, and that column does not
  exist until `V16` is applied: a build before that fails with "column feedback_points does
  not exist", and letting the migration build the index instead holds the lock on the
  crawler's table that the concurrent build exists to avoid.
- **Never** let the writer that writes the new unit run before the migration that converts
  the old rows has been applied, and apply that migration while no writer runs. `V17`
  reads every row as the old writer wrote it and rewrites it in the new unit; a row
  written in the new unit before the conversion is read as an old one — 3 % of 2590 ₽ is
  stored as 77.70 ₽, the conversion reads that as 7770 %, and the row ends up carrying
  201243 ₽. The rollout is four phases: apply `V16` with the old writer still running,
  build the index concurrently and prove that it is valid, then stop the writer, apply
  `V17` and `V18`, start the new writer, and cut the API over last
  (`docs/operations/catalog-index-rollout.md`).
- **Never** start the old crawler after `V17` is applied, and **always** start a restart of
  the old release with the worker off. The old crawler writes the cashback in the old unit
  into a column that now holds a percentage, and the row is wrong by a factor of a hundred;
  the conversion cannot fix it afterwards without reading every row written in the meantime.
  From `V19` on the database refuses those rows itself, so the rule is enforced and not
  remembered (see `2026-11-19-the-writer-declares-the-unit-it-writes.md`).
- **Never** cut the API over on a count that stopped growing, and **always** check the
  coverage of the targets instead: every active target must have been read by the new
  crawler, successfully, since it was started. A crawler that has stopped leaves the count
  exactly where it was, and a catalog that was never filled is a mistake a visitor sees
  (`docs/operations/catalog-index-rollout.md`). The check reads the crawler's runs, which
  say which writer made them, so the check does not depend on the operator's memory of
  when a crawler started (see `2026-11-19-the-run-says-which-writer-made-it.md`).
- **Never** let the serving instance apply the migrations of a rollout, and **always** cut
  the API over after the new crawler has filled the catalog. The new release answers about
  the products that pay review points, and the rows in the table pay none until the new
  crawler has been around the circle — about seven hours. An API cut over earlier answers
  with an empty catalog for that whole time, and the migrations of the rollout are applied
  by a one-shot instance that neither serves nor crawls, so the release that serves keeps
  serving the catalog it has.
- **Never** build the "best cashback" list from the catalog's membership. The catalog is
  made of the products that pay review points, and those are the products WB pays no
  wallet cashback for: the candidates of that list are the observations that pay a
  cashback — whether or not they pay review points — and the list is empty until the
  table holds such an observation. A list built from the whole catalog would answer with
  products that pay no cashback, ordered by a column that is empty for every row.
- **Never** rename an index that is not valid. A `CREATE INDEX CONCURRENTLY` that failed
  leaves one behind, and `CREATE INDEX IF NOT EXISTS` sees only the name: without a check
  the migration would drop the index that works and rename the one the planner ignores.
  `V18` refuses to run on such an index.
- **Always** fill the review points in the synthetic data: the seed, the load script and the
  rollback drill write rows the catalog reads, and a row without points is a row the list does
  not show. A demo catalog whose rows pay nothing answers every query with an empty result, and
  the drill that asks for a page gets nothing back — a failure that looks like a broken API
  rather than a broken fixture.
- The catalog's cashback-based sort and its `minCashback` filter have nothing to sort by
  any more: no crawled product carries a wallet cashback, because the request does not ask
  for one. They keep working — they just report empty and order by recency. The top-deals
  list is the other way round: it answers about the cashback observations the table holds,
  so it is empty only while no observation pays a cashback. Sorting the catalog by review
  points is a separate decision, and this one does not make it.
