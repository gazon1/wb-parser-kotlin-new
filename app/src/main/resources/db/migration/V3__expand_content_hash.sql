-- V3__expand_content_hash.sql
-- content_hash is built as "$targetId:$productId:$priceKopecks":
--   UUID max 36 chars + ':' + Long.MAX_VALUE 19 chars + ':' + Long.MAX_VALUE 19 chars = 76.
-- The VARCHAR(64) limit in V1 can be exceeded by a large price on a Long product ID,
-- silently truncating the hash and either violating the unique index or creating a
-- duplicate that points to the wrong product. Expanding to VARCHAR(128) provides headroom
-- well beyond the theoretical maximum.

ALTER TABLE scraped_items
    ALTER COLUMN content_hash TYPE VARCHAR(128);
