-- V6__catalog_search_query_and_latest_view.sql
--
-- 1. crawl_targets.wb_search_query — WB's own catalog search token, e.g.
--    "menu_v3_9467 карты памяти".
--
--    Why this column exists: the internal catalog API
--    (__internal/u-search/exactmatch/ru/common/v18/search) does NOT take the catalog id
--    as a parameter. The category is selected by the `query` parameter, and WB's menu
--    token is what that parameter has to contain. Without the token the API answers with
--    its rotating "popular presets" list — a pool of ~1200 products unrelated to the
--    requested category, differing from request to request. Every catalog then returned
--    nearly the same goods, all filed under the category name of a target that had
--    nothing to do with them.
--
--    The token is published by WB in main-menu-by-ru-v3.json (field `searchQuery`) and is
--    loaded into this column by scripts/wb-catalog/load_catalog_to_pg.py.
--
-- 2. latest_product_observations — one row per product: the most recent observation.
--
--    Why: scraped_items keeps one row per (target, product, price) — the price is part of
--    the content hash, so a price change writes a new row and the product ends up with
--    several. "What does this product cost right now" therefore needs a view, not a
--    table scan over 50k+ rows.

ALTER TABLE crawl_targets
    ADD COLUMN IF NOT EXISTS wb_search_query TEXT;

COMMENT ON COLUMN crawl_targets.wb_search_query IS
    'WB catalog search token, e.g. "menu_v3_9467 карты памяти" — selects the category in the internal catalog API';

-- The latest observation of every product, across all targets.
--
-- DISTINCT ON (product_id) with ORDER BY product_id, scraped_at DESC picks the newest row
-- per product. The id tiebreaker keeps the result deterministic when two rows share a
-- timestamp — a view that returns a different row on two identical queries is a view
-- nobody can reason about.
CREATE OR REPLACE VIEW latest_product_observations AS
SELECT DISTINCT ON (si.product_id)
    si.*
FROM scraped_items si
WHERE si.product_id IS NOT NULL
ORDER BY si.product_id, si.scraped_at DESC, si.id DESC;

COMMENT ON VIEW latest_product_observations IS
    'The most recent observation of every product (scraped_items is append-only per price)';

-- Supports both the view above and the storefront's "latest price" lookups.
CREATE INDEX IF NOT EXISTS ix_scraped_items_product_latest
    ON scraped_items (product_id, scraped_at DESC);
