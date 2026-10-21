-- V10__measurement_metadata.sql
--
-- The page limit of a target, and the evidence behind it.
--
-- ## Why the metadata belongs to the target
--
-- `crawl_targets.known_pages_limit` used to be a number with nothing around it: no
-- date, no boundary, no source. It was `5` on every one of the 132 targets — a round
-- number, not a measurement — and after the measurement it became `60`, which is a
-- number about a catalog but says nothing about *when* it was true or *which* of the
-- two possible pagination boundaries produced it.
--
-- The two boundaries are not the same fact:
--
--   - `catalog_end` — the catalog ended by itself. The crawl reads one page past the
--     last product page, gets an empty list, and the target is complete.
--   - `api_cap` — WB refused to serve the next page number. There is no empty page
--     to read: the crawl stops at the cap, and the target is a *slice* of the
--     category. The truncation counter fires, and it is right to.
--
-- A limit without its boundary cannot be judged: the same number means "the whole
-- category" in one case and "a third of the category" in the other.
--
-- The evidence is the measurement report (`catalog_measurements.json`, kept in the
-- repository), and these columns are the database's copy of it. The catalog YAML is
-- the source of truth; `scripts/wb-catalog/load_catalog_to_pg.py` writes it here.
--
-- ## Why every column is nullable
--
-- A target whose limit was never measured has to be able to say so: NULL is that
-- statement. Making the columns NOT NULL would force a guess into the rows that
-- were written before the measurement existed, and a guess in a NOT NULL column
-- cannot be told apart from a measurement.
--
-- ## Why `wb_subject_id` has no foreign key
--
-- The subject id is *measured*: the products of a catalog carry a single `subjectId`,
-- and that is the id this column holds. It is not a foreign key to `wb_subjects`
-- because that table is a dictionary WB publishes and the crawler loads on demand
-- (`just catalog::load-subjects`) — an empty dictionary would then refuse a target
-- that was measured perfectly well.
ALTER TABLE crawl_targets
    ADD COLUMN IF NOT EXISTS known_pages_limit_measured_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS known_pages_limit_boundary     TEXT,
    ADD COLUMN IF NOT EXISTS known_pages_limit_source       TEXT,
    ADD COLUMN IF NOT EXISTS wb_subject_id                  BIGINT;

-- The boundary is one of two things, and a third one would be a bug rather than a
-- new fact. NULL is allowed: it says the limit was never measured.
ALTER TABLE crawl_targets
    DROP CONSTRAINT IF EXISTS ck_crawl_targets_known_pages_limit_boundary;

ALTER TABLE crawl_targets
    ADD CONSTRAINT ck_crawl_targets_known_pages_limit_boundary
    CHECK (known_pages_limit_boundary IS NULL
           OR known_pages_limit_boundary IN ('catalog_end', 'api_cap'));

COMMENT ON COLUMN crawl_targets.known_pages_limit_measured_at IS
    'When known_pages_limit was measured (NULL: it was never measured)';
COMMENT ON COLUMN crawl_targets.known_pages_limit_boundary IS
    'Where the pagination ended: catalog_end (the catalog ended by itself) or api_cap (WB refused the next page)';
COMMENT ON COLUMN crawl_targets.known_pages_limit_source IS
    'Where the limit came from, e.g. measurement';
COMMENT ON COLUMN crawl_targets.wb_subject_id IS
    'The WB subject id the products of this target carry';
