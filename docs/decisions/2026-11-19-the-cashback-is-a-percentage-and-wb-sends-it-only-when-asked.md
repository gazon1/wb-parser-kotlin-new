---
title: "The cashback is a percentage, and WB sends it only when the request asks for it"
date: 2026-11-19
tags: [api, crawler, data-model]
---

> **Partially superseded:** the cashback here is the **WB Wallet** cashback, which is not
> what this crawler collects. The crawler collects «Баллы ВБ за отзыв» — see
> `2026-11-19-the-review-points-are-what-the-crawler-collects.md`. The rule below that
> `price.cashback` is a percentage stays in force; the rule that a catalog request must
> carry `fcashback=1` does not.

## Context

The crawler stored almost no cashback: the CLI showed «—» for nearly every product, and
the products that did carry one carried a percentage a hundred times too small.

Two independent mistakes hid each other:

1. **WB was never asked for the cashback.** The catalog request
   (`__internal/u-search/exactmatch/ru/common/v18/search`, `resultset=catalog`) answers
   `sizes[0].price.cashback = 0` for *every* product unless the request carries WB's own
   `fcashback` filter. The field is present, it is just empty.
2. **The field was read as money.** `price.cashback` is a **percentage** (1..75, whole
   numbers), not kopecks. The mapping divided it by the price to make a percentage, so a
   `3` became 0.03 %.

Measured on the live endpoint, `menu_v3_9467 карты памяти`:

| request | products | products with cashback > 0 | values |
|---|---|---|---|
| plain catalog query | 100 | 0 | — |
| `+ fcashback=1` | 15 | 15 | 1 %..4 % |

`menu_v3_9463 смартфон` behaved the same way (44 products, 44 with cashback).

The unit was measured against the prices of the same products: cashback `1..3` next to
prices of 850..10000 ₽ (85000..1000000 kopecks). Read as an amount, those numbers would be
0.01..0.03 ₽ on those prices — a value no storefront shows; read as a percentage they are
1 %..3 % of the price paid. The measurement is committed as evidence in
`devtools/wb-research/investigations/2026-11-19-feedback-points-filter/evidence/feedback-points-filter.json`
(capture `probe-cashback-pairs-fcashback-1`).

## Idea

- (a) Keep the plain catalog request and derive the cashback from the price. — Impossible:
  the field is zero in the answer, so there is nothing to derive from.
- (b) Keep the plain request and add a second, cashback-specific request per category. —
  Two requests per page, two shapes to reconcile, and the second one is WB's own filtered
  answer anyway.
- (c) Ask WB for the cashback filter in the request the crawler already makes, and read the
  field in the unit WB sends it in.

## Decision

The mapping reads `sizes[0].price.cashback` as a percentage. The second half of the
original decision — that the catalog request carries `fcashback=1` — is **superseded**: the
crawler asks for review points, not wallet cashback, and the two filters return different
products.

- `cashbackPercent` is the field as it came (`3` → `3.0`).
- `cashbackKopecks` is derived from the price actually paid (`sizes[0].price.product`, the
  discounted price) and that percentage — the same arithmetic WB does in its own bundle.
- *(Historical, superseded.)* With the request carrying `fcashback=1`, the catalog a crawl
  saw was the *cashback catalog*: the products that carry a cashback. The crawler no longer
  asks for that filter — it collects «Баллы ВБ за отзыв» (see
  `2026-11-19-the-review-points-are-what-the-crawler-collects.md`) — so the products a
  crawl sees now are the products WB pays review points for, not the cashback ones.

## Rationale

`fcashback` is not an invention — it is WB's own filter, and the storefront's
`resultset=filters` answer lists it as «Кешбэк» → «Да». Asking for it is how WB itself
asks the same question, so the answer is the one the storefront shows rather than a
second-class subset.

The percentage was confirmed three ways before it was believed: WB's own bundle clamps the
value with `Math.min(75, …)` and applies it to the price in rubles; the bit field
`saleConditions >> 13 & 31` carries the same number; and the observed values (1..4) are
percentages of four-digit prices, not plausible amounts of money. Two of those three are
independent of the API's documentation, which does not exist.

Reading the field as money was harmless while the field was always zero — it hid the unit
error. Fixing only the request would have started storing percentages that were 100× too
small; fixing only the unit would have kept storing nothing. Both had to move together.

## Consequences

- **Never** read `sizes[0].price.cashback` as an amount of money. It is a percentage.
- **MUST** derive the monetary amount from the price paid and the percentage, not from the
  field.
- *(Historical, superseded.)* With `fcashback=1` in the request, the crawl of a category
  yielded the cashback-carrying products of that category only (tens, not hundreds per
  category) — the catalog shrank because the catalog *is* the answer to "which of these
  products carry a cashback". The crawler now asks for review points instead; the catalog
  it sees is the review-points catalog.
- Products crawled before this change keep their stored `cashback_percent = NULL`; they are
  re-crawled, not migrated.
- Observations that do carry a cashback are converted by the migration
  `V17__scraped_items_cashback_is_a_percentage.sql`. The old writer took the
  percentage for kopecks, so the stored amount is the percentage divided by a hundred
  (`0.03` rubles for a 3 % cashback) and the stored percentage is a number that
  depended on the price rather than on the cashback. The migration restores the
  percentage by multiplying the stored amount by a hundred, and derives the money
  amount from the price that was paid. A column whose meaning changed cannot keep
  its old rows — they would be read as a wrong answer, not as a missing one.
