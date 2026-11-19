-- V18__catalog_read_model_index_carries_the_review_points.sql
--
-- The index the catalog's read model is built from, with the column the model now
-- reads.
--
-- ## Why the index is replaced
--
-- The model reads `feedback_points` — an observation is in the catalog because it
-- carries the review points — and the included columns of an index cannot be changed
-- in place. So the index is built again, with the column, and the old one is dropped.
--
-- Without the column the model is not an index-only scan any more: it visits the heap
-- for every observation of the table, which is the cost the index was built to avoid.
--
-- ## Why the index is built under a second name
--
-- The new index is built under a second name and renamed, so that a live database can
-- build it concurrently *before* the migration is applied: the same order of operations
-- as V7, for the same reason — `scraped_items` is the table the crawler writes to, and
-- a build that holds a lock is a crawler that is not crawling. See
-- `docs/operations/catalog-index-rollout.md`.
--
-- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block, and the crawler's
-- migrations are applied inside one, so the build here is the ordinary one: on the
-- production database the index is built before the migration is applied, concurrently,
-- by the runbook. `IF NOT EXISTS` makes the build a no-op when that has been done.
--
-- The drop of the old index is not `CONCURRENTLY` either, for the same reason: a
-- `CONCURRENTLY` statement cannot run inside a transaction. What it locks is the
-- catalog, not the table's rows — the drop is a metadata operation and it is over in a
-- moment; the build, the part that takes minutes on a big table, is the one the runbook
-- does concurrently. The rename keeps the index's name the one the docs and the runbook
-- name, so nothing outside the migration has to learn a second name.
CREATE INDEX IF NOT EXISTS ix_scraped_items_product_latest_covering_v2
    ON scraped_items (product_id, scraped_at DESC, id DESC)
    INCLUDE (
        target_id, title, price_kopecks, sale_price_kopecks, brand, seller,
        cashback_percent, cashback, feedback_points, in_stock, image_url,
        product_url, match_id
    );

DROP INDEX IF EXISTS ix_scraped_items_product_latest_covering;

ALTER INDEX ix_scraped_items_product_latest_covering_v2
    RENAME TO ix_scraped_items_product_latest_covering;
