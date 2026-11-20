---
title: "the current observation is decided at a grain the read names"
date: 2026-11-19
tags: [api, data-model]
---

> **Refines** `2026-11-19-the-review-points-are-what-the-crawler-collects.md`: the rule
> "decide what is current inside the target the row belongs to" stays in force for the
> detail read, and becomes one of two named grains instead of the only one.

## Context

Two review findings said the same thing from two sides: the read model had **one** rule
for "the current observation", and it was used where **two** were meant.

- The filters that name a product were applied to the rows the model was built from, and
  the model picked the newest of *those* rows. So `q=Samsung SSD` answered with the
  January observation of a product whose current observation is the February one — and the
  detail of that id answered 404, because the detail looks for the current observation.
  The list handed out an id the detail refused.
- The rule itself was target-scoped: a row was current when no newer observation of its
  product existed *in its own target*. The list without a target is built at the product's
  grain — one card per product, whichever target saw it last — and the rule did not
  implement that grain at all. The two grains were not named anywhere, so the code could
  not say which one it was answering.

## Idea

- (a) Keep the filters outside the model. — Then the search runs over every observation,
  the model cannot use the trigram index, and the answer is a price the product does not
  have.
- (b) Keep `DISTINCT ON` and filter before it. — This is what the model did: the filters
  narrow the rows, and the model keeps the newest of the narrowed rows. It answers about
  a product that is not in the catalog.
- (c) Keep the newest observation of the product, and apply the filters to *it*. — The
  model answers about the product as it is now, and every id it hands out is an id the
  detail accepts.
- (d) Name the grain in the read, and let the caller say which one it means. — The grain
  is a property of the read, not a constant buried in the model.

## Decision

The model keeps the **current observation** of every product, and the current observation
is decided by an anti-join: a row is current when no newer observation of the same product
exists. The comparison is on `(scraped_at, id)`, so two observations of one moment are
ordered by the id — the same tiebreaker the model used before, and the reason the rule has
one answer and not two.

The grain is named by the caller, as [CurrentScope]:

- `PRODUCT` — the newest observation of the product, in any target.
- `TARGET` — the newest observation of the product inside its own target.

Every read names its grain:

| read | grain | why |
|---|---|---|
| `/items` without a target | `PRODUCT` | the catalog as a whole: one card per product |
| `/items` with a target | `TARGET` | the page of that target: one card per product of it |
| `/top-deals`, `/targets` | `TARGET` | their grain is the product *and* the catalog |
| `/stats` | `PRODUCT` | the counters count the products of the catalog |
| `/items/{id}` | `TARGET` (default) | the id names an observation of a catalog |

The filters that name a product are applied to the row the model keeps, inside the model:
the search's trigram index still answers them, and the answer is about the product as it
is now. A product whose current observation does not match the search is not found — the
old title is not a product of the catalog.

The detail read keeps the target's grain as its default, and the product's grain is
available to a caller that asks for it. The default stays the target's grain because the
storefront links to the detail from the category and top-deals pages, whose rows are
target-scoped: a detail at the product's grain would answer 404 for them.

A row **without a product id** is not a product: it is an observation of a product that could
not be identified, and at the product's grain such rows are left out — the same rule the view
`latest_product_observations` states, so the API and the view name the same catalog. At the
target's grain they stay: a target holds what it holds, and the detail read of such a row
answers at that grain too. Each of them stands on its own there: two rows that only share the
absence of an id are not two observations of one product, so the target's grain compares the
products only, and every unidentified observation of the target is in the list.

## Rationale

The two grains are compatible, and the reason is an invariant: **the newest observation of
a product is also the newest observation of that product inside its own target** — a newer
observation in the same target would have been the newer one overall. So every row the
product-grain list shows is a row the target-grain detail accepts, and the storefront never
follows a link into a 404.

The grain has to be named because it decides what a read answers, and a read that does not
name it guesses: the list guessed "product", the detail guessed "target", and the guess
was invisible in the code. Now the guess is a parameter at every call site, and the
integration suite pins both grains down — including the rows without a product id and two
observations of the same moment.

The filters move onto the current row because the model's own claim is that it holds the
current observation of every product. A filter applied before that claim was kept was
answering a different question — "which products ever matched" — and the answer was a
price the product does not have.

## Consequences

- **Always** name the grain of a read. A read that does not say which one it means guesses,
  and the guess is what made the list and the detail disagree.
- **Never** apply a filter that names a product before the model keeps its current
  observation. The answer is about a product that is not in the catalog, and the id it
  hands out is an id the detail answers 404 for.
- **Always** keep the detail read's default at the target's grain. The storefront links to
  the detail from the target-scoped pages, and the product's grain would refuse their rows.
- **Always** decide the rows without a product id explicitly: at the product's grain they
  are not a product and are left out (the view leaves them out too), at the target's grain
  they stay, because a target holds what it holds — and each of them stands on its own, so
  the target's grain compares the products only. Two rows that only share the absence of an
  id are not two observations of one product, and the older one is not a stale copy of the
  newer one.
- The model and the view `latest_product_observations` still agree: the view is the
  product's grain, and the list without a target is the product's grain. The suite pins it.
- The index the model was built from (`product_id, scraped_at DESC, id DESC`, and the
  target-scoped one) answers the anti-join as well — the comparison is the same order, and
  the same tiebreaker.
- A product that changed its title is found under the new title only. The catalog shows
  what the products are now.
- The plan on the production volume has not been measured: the model was a sort with a
  `DISTINCT ON` over the observations, and it is now a semi-join over the same index. The
  order of the cost is the same — a pass over the observations — but the numbers of
  `docs/decisions/2026-10-20-the-catalog-lists-products-not-observations.md` were measured
  for the old plan and have to be measured again before they are quoted.
