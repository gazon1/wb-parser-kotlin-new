-- Seed data for crawl_targets.
--
-- NOT part of the Flyway migration path — run it once against a database that has
-- already had V1/V2 applied. Everything the crawler does starts here: with no rows in
-- crawl_targets, fetchActiveTargets() returns an empty list and the crawl does nothing.
--
-- Three ways to populate this table (choose one):
--   1. Edit and run this file.
--   2. Import from the legacy .NET database:
--        INSERT INTO crawl_targets (name, start_url, max_depth, is_active,
--                                   wb_catalog_id, root_category_name, created_at, updated_at)
--        SELECT name, start_url, max_depth, true,
--               wb_catalog_id, root_category_name, now(), now()
--        FROM dblink('dbname=webcrawler', 'SELECT name, start_url, max_depth,
--                     wb_catalog_id, root_category_name FROM crawl_targets');
--   3. Add a write path to the admin API.
--
-- The example below is a PLACEHOLDER — replace the URL and names with real catalog
-- targets before running. crawl_targets.start_url is what the downloader fetches, and
-- root_category_name is what the storefront groups categories under.

INSERT INTO crawl_targets (name, start_url, max_depth, is_active, root_category_name, created_at, updated_at)
VALUES (
    'REPLACE-ME — example row',
    'https://example.invalid/catalog',
    1,
    true,
    'Примеры',
    now(),
    now()
)
ON CONFLICT DO NOTHING;

-- Verify the seed landed:
--   SELECT id, name, root_category_name, is_active FROM crawl_targets ORDER BY name;