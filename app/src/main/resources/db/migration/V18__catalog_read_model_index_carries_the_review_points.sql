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
-- The column this index carries is added by `V16__scraped_items_feedback_points.sql`,
-- so the build cannot happen before that migration has been applied: on a database
-- without the column the concurrent build fails with "column feedback_points does not
-- exist". The order on a live database is therefore V16, then the concurrent build,
-- then this migration — `docs/operations/catalog-index-rollout.md` says so.
--
-- This migration is applied together with `V17__scraped_items_cashback_is_a_percentage.sql`,
-- which converts the cashback of every existing row to the new unit. That conversion
-- reads the rows as the old writer wrote them, so the writer that writes the new unit
-- must not run before it — the runbook says which writer is stopped, and when.
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
--
-- ## Why the migration refuses an index that is not valid
--
-- A `CREATE INDEX CONCURRENTLY` that failed leaves an index behind that exists and is
-- not valid: PostgreSQL marks it invalid, and the planner ignores it. `CREATE INDEX IF
-- NOT EXISTS` sees the name and does nothing — so a migration that only looked at the
-- name would drop the index that works and rename the one that does not, and the read
-- model would visit the heap for every observation, with nothing to show for the
-- minutes the build took.
--
-- So the migration checks that the index it is about to rename is valid, and refuses to
-- run otherwise: the deployment fails, the old index stays, and the operator drops the
-- invalid index and builds it again. The check is the last line of the runbook's
-- "checking that it worked" made into a gate — the runbook asks for it, and the
-- migration does not trust the answer.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'ix_scraped_items_product_latest_covering_v2'
          AND NOT i.indisvalid
    ) THEN
        RAISE EXCEPTION
            'the index ix_scraped_items_product_latest_covering_v2 is not valid: drop it with '
            'DROP INDEX CONCURRENTLY and build it again';
    END IF;
END
$$;

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
