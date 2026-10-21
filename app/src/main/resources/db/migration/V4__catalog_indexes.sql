-- V4__catalog_indexes.sql
--
-- The indexes the catalog's own queries need, at the volume the catalog actually runs at.
--
-- ## Why this migration exists
--
-- The catalog's queries were written against a thousand rows of demo data, where every
-- query is fast and every plan is a sequential scan. At the volume a real crawl produces
-- — hundreds of thousands of observations — three of them stop being fast, and they stop
-- being fast for reasons that are visible in the plan rather than in the code:
--
--   1. The free-text search is `ILIKE '%q%'` over the title, the brand and the seller. A
--      pattern with a leading wildcard cannot use a btree index at all: the index does not
--      know where the word starts. The fix is a trigram index, which indexes the
--      *substrings* — the thing the pattern actually asks for.
--   2. The default order is `scraped_at DESC, id`. The tie-breaker by id is not a detail:
--      without it two rows with the same timestamp have an arbitrary order, and a page
--      boundary could then repeat one row and drop another. An index that carries the
--      tie-breaker lets the database produce that order without sorting.
--   3. The same order, restricted to one target — the shape of a category page. The
--      composite index serves both the ordering and the filter.
--
-- ## Why the indexes are built the ordinary way
--
-- `CREATE INDEX CONCURRENTLY` would not block the crawler's writes while the index is
-- built, and on a live database that matters: the crawler is the component whose work
-- would be lost while it waits for a lock.
--
-- It cannot be used here for two reasons. It cannot run inside a transaction block, and
-- the crawler's migrations are applied inside one — by Flyway in the application, and by
-- the test fixture, which executes a migration file as a single batch. And a concurrently
-- built index is only marked valid when the build finishes, which makes the migration
-- something an operator has to check afterwards rather than something that either
-- happened or did not.
--
-- So the migration builds the index the ordinary way, and the production run applies it
-- the ordinary way too: on the production database the indexes are built *before* the
-- migration is applied, concurrently, by the runbook in `docs/operations/catalog-index-rollout.md`.
-- The `IF NOT EXISTS` makes the migration a no-op when that has been done — and the
-- migration is the thing that makes the indexes exist on a fresh database, which is the
-- case every test runs.

-- The trigram extension: the index type that can answer `ILIKE '%q%'`.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Free-text search over the title, the brand and the seller.
--
-- Three separate indexes, one per column: a multicolumn trigram index would answer "the
-- pattern is in all three columns at once", which is not the question — the question is
-- "the pattern is in any of them".
CREATE INDEX IF NOT EXISTS idx_scraped_items_title_trgm
    ON scraped_items USING GIN (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_scraped_items_brand_trgm
    ON scraped_items USING GIN (brand gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_scraped_items_seller_trgm
    ON scraped_items USING GIN (seller gin_trgm_ops);

-- The default order of the catalog list: newest first, with the id as the tie-breaker.
--
-- The id is part of the index because the order is total: rows with the same timestamp
-- are ordered by id, and an index that does not carry the id forces the database to sort
-- the rows before it can drop the ones before the page.
CREATE INDEX IF NOT EXISTS idx_scraped_items_scraped_at_id
    ON scraped_items (scraped_at DESC, id);

-- The same order inside one target: the shape of a category page.
CREATE INDEX IF NOT EXISTS idx_scraped_items_target_scraped_at_id
    ON scraped_items (target_id, scraped_at DESC, id);
