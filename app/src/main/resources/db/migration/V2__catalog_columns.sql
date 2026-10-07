-- V2__catalog_columns.sql
--
-- Makes the catalog data actually consumable by a read-only storefront:
--
--   1. Persist fields the domain already parses but the writer was dropping
--      (image_url, sale_price_kopecks, in_stock, brand_id). Until now they existed
--      only inside the serialized `data` JSONB, which is the crawler's internal shape.
--   2. Scope content_hash to a target. A global unique index pinned every product to
--      the first category it was ever scraped under, because target_id was absent from
--      the ON CONFLICT ... DO UPDATE SET list.
--   3. Add indexes for the storefront's hot queries (cashback_percent, match_id).

-- ---------------------------------------------------------------------------
-- 1. Columns that were being dropped
-- ---------------------------------------------------------------------------

ALTER TABLE scraped_items ADD COLUMN IF NOT EXISTS image_url          TEXT;
ALTER TABLE scraped_items ADD COLUMN IF NOT EXISTS sale_price_kopecks BIGINT;
ALTER TABLE scraped_items ADD COLUMN IF NOT EXISTS in_stock           BOOLEAN;
ALTER TABLE scraped_items ADD COLUMN IF NOT EXISTS brand_id           BIGINT;

-- ---------------------------------------------------------------------------
-- 2. content_hash is per (target, product, price) — not global
-- ---------------------------------------------------------------------------

ALTER TABLE scraped_items DROP CONSTRAINT IF EXISTS uq_scraped_items_content_hash;

-- A row is one observation of one product inside one target at one price.
-- Existing rows keep their hash value; only the *scope* of uniqueness changes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_scraped_items_target_content_hash
    ON scraped_items (target_id, content_hash);

-- ---------------------------------------------------------------------------
-- 3. Indexes for the storefront
-- ---------------------------------------------------------------------------

-- Top-deals orders by cashback_percent; the filter is minCashbackPercent.
CREATE INDEX IF NOT EXISTS idx_scraped_items_cashback_percent
    ON scraped_items (cashback_percent DESC NULLS LAST, scraped_at DESC)
    WHERE cashback_percent IS NOT NULL;

-- Top-deals groups by match_id (DISTINCT ON).
CREATE INDEX IF NOT EXISTS idx_scraped_items_match_id
    ON scraped_items (match_id)
    WHERE match_id IS NOT NULL;

-- Image is read on nearly every card, but only for the newest observation.
CREATE INDEX IF NOT EXISTS idx_scraped_items_target_scraped_at
    ON scraped_items (target_id, scraped_at DESC);