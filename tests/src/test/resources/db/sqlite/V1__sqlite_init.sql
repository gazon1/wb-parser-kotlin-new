-- SQLite-compatible test migration (derived from V1__init.sql)
-- Conversions applied:
--   UUID         → TEXT (application-side UUID.toString())
--   JSONB/JSON   → TEXT (application-side kotlinx.serialization)
--   TIMESTAMPTZ  → TEXT (ISO-8601 Instant.toString())
--   DECIMAL      → REAL (SQLite stores doubles natively)
--   BIGINT       → INTEGER (SQLite 64-bit signed)
--   gen_random_uuid() → app-side UUID.randomUUID().toString()
--   ON CONFLICT  → SQLite ≥3.24 supports identical syntax

CREATE TABLE IF NOT EXISTS crawl_targets (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    start_url       TEXT NOT NULL,
    is_active       INTEGER NOT NULL DEFAULT 1,
    max_depth       INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS scraped_items (
    id              TEXT PRIMARY KEY,
    target_id       TEXT NOT NULL,
    catalog_url     TEXT,
    product_url     TEXT NOT NULL,
    brand           TEXT,
    seller          TEXT,
    price_kopecks   INTEGER,
    title           TEXT,
    product_id      INTEGER,
    cashback        REAL,
    cashback_percent REAL,
    data            TEXT,
    content_hash    TEXT NOT NULL UNIQUE,
    scraped_at      TEXT NOT NULL,
    subject_id      INTEGER,
    subject_parent_id INTEGER,
    match_id        INTEGER,
    supplier_id     INTEGER,
    catalog_name    TEXT
);
