-- V7__catalog_read_model_index.sql
--
-- The index the catalog's read model is built from.
--
-- ## Why the model needs an index of its own
--
-- The catalog's read model is "the current observation of each product":
-- `DISTINCT ON (product_id) … ORDER BY product_id, scraped_at DESC, id DESC`. The key
-- that answers it exists — `ix_scraped_items_product_latest`, from V6 — but it carries
-- only the key. The twelve columns a card needs live in the table, so building the
-- model visits the heap once per product, in whatever order the index happens to be
-- in, and then sorts the result for the page.
--
-- At two hundred thousand observations that is the difference between a page that
-- arrives in a fifth of a second and one that takes four. The model is not an
-- occasional query: it is the first thing every list does.
--
-- ## Why the index is covering
--
-- `INCLUDE` puts the card's columns into the index, so the model becomes an
-- index-only scan: no heap access, no sort — the index is already in the order the
-- model needs. The index is wide on purpose: it duplicates the card's columns for
-- every *observation*, not for every product, because the price history needs every
-- observation and only the current price needs one of them.
--
-- ## Why the index is built the ordinary way
--
-- The same reasoning as V4, and the same procedure: `CREATE INDEX CONCURRENTLY` cannot
-- run inside a transaction block, and the crawler's migrations are applied inside one.
-- So the migration builds the index the ordinary way, and the production run applies
-- it the ordinary way too: on the production database the index is built *before* the
-- migration is applied, concurrently, by the runbook in
-- `docs/operations/catalog-index-rollout.md`. The `IF NOT EXISTS` makes the migration
-- a no-op when that has been done — and the migration is what makes the index exist on
-- a fresh database, which is the case every test runs.
CREATE INDEX IF NOT EXISTS ix_scraped_items_product_latest_covering
    ON scraped_items (product_id, scraped_at DESC, id DESC)
    INCLUDE (
        target_id, title, price_kopecks, sale_price_kopecks, brand, seller,
        cashback_percent, cashback, in_stock, image_url, product_url, match_id
    );
