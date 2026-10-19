---
title: "Category names come from WB's subject dictionary, and known_pages_limit is a limit"
date: 2026-10-19
tags: [catalog, data, domain-model]
---

## Context

Three fields in the schema had no working meaning, and one of them looked like a
missing data source rather than a missing feature.

1. **`scraped_items.catalog_name` was NULL for every row.** The WB catalog API
   tells each product where it sits in the category tree by id — `subjectId` and
   `subjectParentId` — and sends no name. The parser read the ids and dropped
   them on the way in, so for a while the rows carried neither. After the ids
   were carried through, the names were still missing.

2. **`category_mappings.catalog_name` and `node_name` were NULL.** Same cause.
   The table was created by V1 and nothing ever wrote to it; when it was filled,
   it was filled with ids and null names, and the level was left at 0.

3. **`known_pages_limit` was stored and returned but never read.** The crawler
   paginates — the API's response carries `nextPage`, and the pipeline follows
   it — and the runner caps the pages of a run with `maxPagesPerCatalog`. The
   per-target column sat in between: it had a column, a DTO field, an admin API
   field, and no behaviour.

The assumption behind the first two was that the names do not exist: the API
does not send them, so a crawler that wants a name has to invent one. That
assumption was wrong, and it was checked rather than assumed.

## Idea

- WB publishes a dictionary of its product subjects — id → name — as a static
  JSON file on its CDN, the same way it publishes the catalogue menu tree.
  The crawler can read it and look the ids up.
- `known_pages_limit` should be the per-target page cap, with the runner-wide
  cap as its fallback.

## Decision

**The dictionary is a table: `wb_subjects`.** Migration `V4__wb_subjects.sql`
creates it; `scripts/wb-catalog/load_subjects.py` fills it from
`static-basket-01.wbbasket.ru/vol0/data/subjects.json` (8174 subjects, every
one of them with a name). `just catalog::load-subjects` runs the loader, and
`--backfill` additionally names the rows that were scraped before the
dictionary existed — the same value the write path would have written, so the
table ends up in one state rather than two.

**The write path looks the names up.** `upsertSavedItems` takes the dictionary
as a map and writes `scraped_items.catalog_name`,
`category_mappings.catalog_name` and `category_mappings.node_name` from it. The
name is WB's own; it is never invented, and a subject the dictionary does not
know keeps a null name.

**The dictionary is read once per runner.** `SubjectNameLookup` holds it and
loads it on first use: a full table read per saved batch would be a read of
several thousand rows in front of every batch. An empty dictionary is a normal
state — a deployment that has not loaded it writes ids without names.

**`known_pages_limit` is the per-target page cap.** The runner stops a target
after `target.knownPagesLimit` pages when the target sets one, and after
`maxPagesPerCatalog` when it does not. The target's own limit wins: it says
how many pages of *this* category are worth crawling, which is a statement
about the category and not about the runner.

## Rationale

The names were available the whole time. The catalog API's silence about them
was taken as evidence that they did not exist, and the tree was written as ids
with null names — the honest thing to do with the data in hand, and the wrong
thing to do with the data available. The dictionary is WB's own file, the same
kind of source the catalogue selector already reads for the menu tree, and it
costs one HTTP request.

Storing it as a table rather than fetching it at runtime keeps the crawler
off the network for data that does not change while a crawl runs, and it makes
the names inspectable: `SELECT * FROM wb_subjects WHERE id = 180` is a fact
about the database, not about a cache.

`known_pages_limit` needed no new machinery. The pipeline already follows
`nextPage` and the runner already stops at a page count; the column was
missing only from the decision about *which* count.

## Consequences

- **Always** load the dictionary before a crawl that is expected to produce
  names: `just catalog::load-subjects`. Without it the crawl still works and
  the name columns stay null.
- **Never** write a name that did not come from the dictionary. A null name is
  a missing fact; a made-up one is a false fact.
- `wb_subjects` is a reference table the crawler only reads. It is not part of
  the crawl's write path and nothing depends on it being present.
- `known_pages_limit` is now a real cap. A target that sets it to 1 is crawled
  for one page per run; a target that leaves it NULL uses the runner-wide
  `maxPagesPerCatalog`.
