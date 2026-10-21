-- How many products WB says the search behind a run holds.
--
-- WB answers a catalog request with a `total` — the size of the *search* that the
-- request asks for. It is not the length of the catalog: the catalog's own pagination
-- ends at the API cap (60 pages), and the catalog's length is measured at that
-- boundary and lives in `known_pages_limit`. A catalog measured at 60 pages can carry
-- a `total` of 161 479 — the search behind it counts everything that matches the query
-- across the marketplace.
--
-- The number is written down because a report that cannot say how big WB says the
-- search is cannot show the operator the scale of what was read. It is stored under
-- the name `wb_total` so that nobody reads it as the length of the catalog, and it is
-- nullable because a run that failed before WB answered has no such number: a zero
-- there would say "the search is empty", which is a different and much worse claim.
ALTER TABLE crawl_target_runs
    ADD COLUMN IF NOT EXISTS wb_total INTEGER;
