-- Seed data for crawl_targets.
--
-- NOT part of the Flyway migration path — the schema is created by
-- app/src/main/resources/db/migration/. This file is a manual, one-off helper.
--
-- ## The canonical way to seed is the YAML flow
--
-- The categories come from the Wildberries catalog and are selected in the
-- browser UI:
--
--     just catalog::selector          # pick categories, writes wb_catalog_targets.yaml
--     just catalog::load-confirm wb_catalog_targets.yaml
--
-- That path writes wb_catalog_id, wb_parent_catalog_id and root_category_name
-- alongside the URL, which is what the crawler actually needs: CrawlRunner
-- builds its API URL from wb_catalog_id, and the storefront groups categories
-- under root_category_name. Running this file instead leaves those columns
-- null and the crawler falls back to the start_url.
--
-- ## What this file is for
--
-- It is the escape hatch for the two cases the YAML flow does not cover:
--
--   1. Importing from the legacy .NET database:
--        INSERT INTO crawl_targets (name, start_url, max_depth, is_active,
--                                   wb_catalog_id, wb_parent_catalog_id, root_category_name,
--                                   created_at, updated_at)
--        SELECT name, start_url, max_depth, true,
--               wb_catalog_id, wb_parent_catalog_id, root_category_name, now(), now()
--        FROM dblink('dbname=webcrawler', 'SELECT name, start_url, max_depth,
--                     wb_catalog_id, wb_parent_catalog_id, root_category_name FROM crawl_targets');
--
--   2. Inserting a single target by hand, for a smoke test.
--
-- The example below is a PLACEHOLDER — replace the URL and names with a real
-- catalog target before running it. crawl_targets.start_url is what the
-- downloader fetches when wb_catalog_id is null, and root_category_name is what
-- the storefront groups categories under.

-- max_depth is 1: how deep the crawl may descend into the category tree. The internal
-- catalog API returns one catalog per target, so the crawl does not descend at all and
-- the value only matters for link-following targets. How many *pages* of a catalog are
-- worth crawling is known_pages_limit, not max_depth — the two were mixed up, and the
-- loader used to default to 2 while this seed said 1.
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
--   SELECT id, name, wb_catalog_id, root_category_name, is_active
--   FROM crawl_targets ORDER BY name;
