---
title: "Business rules are wired into the filter stage, and filtering is now on by default"
date: 2026-10-09
tags: [pipeline, behaviour, configuration]
---

## Context

`BusinessRules` and `dropIfInvalid` had been written and tested since PR 4, but
`PipelineFactory` wired the filter stage as an identity function:

```kotlin
filter = { item: ParsedItem -> Step.Done<ParsedItem, ParsedItem?>(item) }
```

So the rules were only ever called from `BusinessRulesTest`. In production every parsed item
was enriched and saved, and `Side.Drop` — one of only three `Side` variants the domain
actually emits — was unreachable.

A second defect sat behind the first: `processItems` read `filter`'s output but discarded the
`sides` the stage returned, so even after wiring the rules every rejection would have been
logged as the generic `Dropped.Filtered` with no indication of which rule fired.

## Idea

- (a) Delete `BusinessRules` as dead code and keep four stages.
- (b) Wire the rules and accept that fewer items are stored.
- (c) Wire the rules but make them configurable, so the blast radius is visible and tunable.

## Decision

We wire the rules, expose them under `crawler.spider.rules`, and fix `processItems` to carry
the filter stage's own `Side` effects.

`Pipeline.processItems` now emits the generic `Dropped.Filtered` only when the filter stage
did not already report a reason for that item, so a rejected item produces exactly one
`Side.Drop` naming the rule that fired.

## Rationale

Option (a) would have deleted tested, well-formed domain logic on the grounds that nothing
called it — but the rules describe what a catalog scraper is *for*. Option (b) leaves the
change invisible: with no configuration knob, the next person to read `BusinessRules()` would
reasonably assume its defaults are inert. Option (c) makes the behaviour inspectable.

**This is a behaviour change and the defaults are not inert.** With no configuration at all,
one rule is active: an item whose price is zero *and* which has no sale price is dropped with
`Dropped.EmptyPrice`. That is defensible — an unpriced item is not worth a storefront row — but
it will reduce stored rows on the first run after this change, and it is not something a
caller can disable without editing code.

## Consequences

- **Always** configure `crawler.spider.rules` explicitly when deploying, even to the defaults,
  so the effective policy is visible in the environment rather than in a code default.
- **Never** widen a rule's boundary without adding the boundary case to a test. The comparison
  is `priceKopecks < min` and `priceKopecks > max`, so a price exactly equal to a limit is
  **kept**. This is pinned by tests because "at the limit" is the case most likely to be
  assumed the other way.
- Rules read `priceKopecks`, `salePriceKopecks`, `inStock` and `category` from `ParsedItem`,
  before the `enrich` stage. Any future rule that needs a field only present on `SavedItem`
  would have to move to a later stage.
- A dropped item is counted separately: `Crawled.itemsSaved` excludes it, and one
  `Side.Drop` reaches the interpreter per rejected item.