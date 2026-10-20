---
title: "Loading targets is idempotent, and root categories come from WB's public tree"
date: 2026-10-19
tags: [catalog, data, tooling]
---

## Context

Two related defects in how the crawl targets are loaded and kept.

1. **Loading the same YAML twice duplicated every target.** `load_catalog_to_pg.py`
   inserted a row per page with a freshly generated uuid for the primary key.
   There is no unique constraint on `wb_catalog_id`, so nothing stopped the
   second copy: after two loads the crawler had 264 targets, of which 132 were
   stale duplicates whose crawl history the new rows did not carry. The
   duplicates are worse than a duplicate list — the scheduler crawls both, so
   every category is crawled twice.

2. **`root_category_name` was NULL for every target.** The column exists, the
   admin API returns it, and the storefront groups by it — but the value was
   never written. The selector writes it per page, so a YAML produced by the
   current selector has it; the YAML in use had been produced before that field
   existed, and re-running the selector means re-crawling WB's catalogue tree
   through a browser, against anti-bot, to obtain a field that WB publishes as
   a static JSON file.

## Idea

- Make the load a sync: a target that already carries this `wb_catalog_id` is
  updated in place, keeping its id.
- Fill `root_category_name` from WB's own menu tree — the same file the
  selector reads, fetched over plain HTTP with no browser involved.

## Decision

**The loader syncs by `wb_catalog_id`.** Before inserting, the loader reads the
existing `wb_catalog_id`s; a page whose catalog id is already present is
updated in place (`name`, `start_url`, `allowed_domains`, `max_depth`,
`known_pages_limit`, `wb_parent_catalog_id`, `root_category_name`, `is_active`,
`updated_at`), and only the pages that are new are inserted. The update leaves
`id` and `created_at` alone: the id is what the crawl history hangs off (jobs,
items, category mappings), so an update has to keep the row it updates. The
loader reports how many rows it updated and how many it inserted.

**`scripts/wb-catalog/fill_root_categories.py` backfills the YAML.** It reads
WB's public menu tree — `static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json`,
the same source the selector uses — builds the `id → name` and `child → parent`
maps, walks each page up to its top-level ancestor and writes the ancestor's
name into `root_category_name`. The tree is cached under `~/.cache/wb-catalog/`.
`just catalog::fill-roots` runs it; `--dry-run` reports without writing.

**The admin API's target list takes an optional `limit`.** `GET /api/admin/targets`
ignored a `limit` parameter that `/jobs` and `/errors` honour, so a caller had
no way to ask for a page of targets. `limit` is optional and unbounded by
default: an operator asking for "the targets" means all of them.

## Rationale

A load that cannot be repeated is a load that has to be protected by a wrapper
script and a human remembering not to run it twice. Syncing by the catalog id
makes the YAML the single source of truth it was always meant to be, and it
keeps the crawl history attached to the target it belongs to.

The uniqueness of `wb_catalog_id` is *not* enforced by a unique index. Adding
one would be the stronger statement, but it changes what the admin API may
accept — a second target with the same catalog id would start failing at the
database instead of being rejected by the API — and that is a separate decision
with its own migration. The loader's own read-then-write is enough for a
command-line tool that one person runs at a time, and the loader says how many
rows it touched either way.

The menu tree is the right source for the root names: it is WB's own data,
it is the same source the selector already trusts, and it costs one HTTP
request. Inventing names, or scraping them again through a browser, would
produce the same answer more expensively and less reliably.

## Consequences

- **Always** load through `just catalog::load-confirm` (or the script directly).
  It is idempotent now: running it twice changes nothing and reports
  "132 updated, 0 inserted".
- **Never** reintroduce a blind `INSERT` into the loader. A load that creates a
  second copy of every target doubles the crawler's work.
- A YAML written by an older selector has no `root_category_name`; run
  `just catalog::fill-roots` before loading it. Pages missing from the menu
  tree are reported by name and left alone rather than guessed.
- `GET /api/admin/targets` returns everything unless a `limit` is passed.
