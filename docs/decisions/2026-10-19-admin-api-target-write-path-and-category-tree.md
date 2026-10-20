---
title: "Admin API — one Target mapping, a write path, and the category tree"
date: 2026-10-19
tags: [api, database, architecture]
---

## Context

Three separate problems, all in the same corner of the codebase, all of them
silent:

1. **The admin API lied about loaded targets.** `AdminRoutes.fetchAdminTargets()`
   carried its own `ResultSet` → DTO mapping, separate from the Exposed mapping
   in `TargetQueries.kt`. It selected five columns and dropped the rest, so
   `wb_catalog_id` — the field `CrawlRunner` uses to build
   `wildberries.ru/catalog/<id>/list.aspx` — came back as `null` while the
   database said `60808`. Two mappings for one entity means the next column added
   is a column one of them forgets.

2. **There was no write path.** The admin API was read-only, so every change to a
   crawl target meant editing the database by hand and re-running the loader. The
   seed file's own third option ("add a write path to the admin API") was never
   done.

3. **The category tree was unreachable.** `category_mappings` was created by V1
   and nothing ever wrote to it. Worse, the ids needed to fill it were being
   dropped on the way in: `WbItemDto` had no `subjectParentId` field, so
   `scraped_items.subject_parent_id` was null for 100% of rows — 13 200 of them
   at the time of writing. The legacy .NET pipeline extracted that field, and WB
   sends it on every product.

## Idea

- One mapping for `Target`, in one place, used by every reader.
- The admin API gets a write path, so targets are manageable without SQL.
- The category tree is filled from the data the crawl actually has.

The tempting alternative for the third point was to fetch WB's category tree from
its own endpoint and store names and levels. A live capture of the
`__internal/u-recom/personal/v9/search` response showed the envelope is
`{metadata, products}` and nothing else — **there is no `nodes` array**. The
products carry `subjectId`, `subjectParentId` and `semanticId`, and no category
names. A name would have to be invented, so it is left null.

## Decision

**One mapping.** `TargetQueries.kt` grew a shared `ResultRow.toTarget()`, used by
both `fetchActiveTargets()` and a new `fetchAllTargets()`. The admin API serves
`fetchAllTargets()` and its hand-written JDBC mapping is gone. The `Target` domain
model gained `wbParentCatalogId`, `rootCategoryName` and `knownPagesLimit` — the
columns that were previously invisible, and which the storefront reads.

**A write path.** `POST /api/admin/targets`, `PUT /api/admin/targets/{id}` and
`DELETE /api/admin/targets/{id}`, backed by `insertTarget`, `updateTarget` and
`deleteTarget` in the repository. `updateTarget` and `deleteTarget` return false
for an unknown id, which the controller turns into a 404 rather than a silent
success. The request body is a separate `TargetDraft` type, because `Target`
carries derived fields (`lastScheduledAt`, `nextScheduledAt`, `priority`) that a
client cannot set.

**The category tree is filled with ids, not names.** `subjectParentId` was added
to `WbItemDto`, `ParsedItem` and `SavedItem`, and `upsertSavedItems` now upserts
`category_mappings` in the same transaction: `catalog_id` from `subjectId`,
`parent_node_id` from `subjectParentId`, names null, `level` 0. Rows without a
`subjectId` are skipped — a null `catalog_id` never conflicts in the
`(target_id, catalog_id)` unique index, so such a row would be re-inserted on
every crawl.

**`root_category_name` is produced by the selector.** `catalog_selector.py` now
writes the label of each page's top-level ancestor into the YAML, and the loader
reads it per page. The `--root-category` CLI flag still overrides everything.

## Rationale

- The admin API is the tool an operator uses to check what a load produced. A
  tool that reports `null` for a populated column is worse than no tool: it
  sends you looking for a bug that is not there.
- Two mappings for one table is the actual defect. Fixing only the missing column
  would have left the next column to be forgotten.
- A null name is honest; a made-up name is not. The ids are what the crawl
  attests to, and the storefront can join them against its own dictionary.
- The write path removes the reason to reach for SQL. It also gives the delete
  case a defined behaviour: `scraped_items` and `category_mappings` cascade, so
  deleting a target takes its scraped history with it — the schema's stated
  behaviour, now documented on the function.

## Consequences

- **Always** read targets through `TargetQueries.kt`. The admin API has no SQL of
  its own for `crawl_targets` any more.
- **Never** add a second `ResultSet` → DTO mapping for a table the repository
  already maps.
- `catalog_mappings.catalog_name` and `node_name` stay null until WB gives the
  crawler a source for category names. Filling them with a guess would be worse
  than leaving them empty.
- `scraped_items.subject_parent_id` is now populated. Rows scraped before this
  change keep their nulls — there is no backfill, and the crawler will fill them
  in on the next visit to each target.
- Deleting a target through the admin API deletes its scraped items and category
  mappings too.
