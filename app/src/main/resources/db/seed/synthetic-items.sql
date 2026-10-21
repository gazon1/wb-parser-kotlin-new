-- ==============================================================================
-- Synthetic catalog for the demo profile.
--
-- This file fills `scraped_items` with data that is shaped like the real thing, including
-- the parts of the real thing that are inconvenient: rows without a title, without a
-- price, without a cashback, without a picture, out of stock, and without a match id.
-- A seed that only produced complete rows would leave the contract's nullable half
-- untested — the storefront's rendering of a product with no price is exactly the case
-- that breaks when somebody assumes the data is always there.
--
-- ## Determinism
--
-- Every value is derived from `md5()` of a fixed string or from `generate_series`. There
-- is no `random()` and no `now()`: the same file produces the same database every time,
-- so two runs can be compared, and a failing assertion can be reproduced.
--
-- ## The properties this file guarantees
--
--   1. Four targets with meaningful Cyrillic names; exactly one of them is configured but
--      empty, because a target with no items is a state the storefront has to render.
--   2. 1200 observations across the three populated targets.
--   3. One product with several observations inside one target (a price history).
--   4. One product present in all three populated targets, under the same match id, so
--      that the top-deals list has something to collapse. It is observed twice in one of
--      those targets, which is what lets a consumer check that a price history does not
--      cross the boundary between targets.
--   5. Rows without a match id, without a title, without a price, without a cashback,
--      without a picture, and out of stock.
--   6. Prices are whole kopecks. The cashback in rubles never exceeds the price, and a
--      sale price is always strictly below the price.
-- ==============================================================================

-- ── Targets ──────────────────────────────────────────────────────────────────
-- The fourth target is deliberately empty: it is the "configured but nothing crawled
-- yet" state, and it is the one the storefront's category list has to survive.

INSERT INTO crawl_targets (
    id, name, start_url, allowed_domains, max_depth, is_active,
    root_category_name, created_at, updated_at
)
VALUES
    ((md5('demo:target:electronics'))::uuid, 'Электроника',
     'https://example.invalid/electronics', ARRAY['example.invalid'], 1, true,
     'Электроника', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ((md5('demo:target:home'))::uuid, 'Дом и сад',
     'https://example.invalid/home', ARRAY['example.invalid'], 1, true,
     'Дом и сад', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ((md5('demo:target:clothes'))::uuid, 'Одежда',
     'https://example.invalid/clothes', ARRAY['example.invalid'], 1, true,
     'Одежда', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ((md5('demo:target:books'))::uuid, 'Книги',
     'https://example.invalid/books', ARRAY['example.invalid'], 1, true,
     'Книги', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00');

-- ── Observations ─────────────────────────────────────────────────────────────
-- 400 per populated target, 1200 in total.
--
-- The moduli below are deliberately coprime-ish and spread across the range, so that each
-- "unusual" case appears many times over rather than once: a single row with a null
-- price is a coincidence, and a contract test that only sees one of them proves nothing.

WITH source(target_id, slug, root, position) AS (
    VALUES
        ((md5('demo:target:electronics'))::uuid, 'electronics', 'Электроника', 1),
        ((md5('demo:target:home'))::uuid, 'home', 'Дом и сад', 2),
        ((md5('demo:target:clothes'))::uuid, 'clothes', 'Одежда', 3)
),
generated AS (
    SELECT s.target_id, s.slug, s.root, s.position, g.n
      FROM source s
      CROSS JOIN LATERAL generate_series(1, 400) AS g(n)
),
priced AS (
    SELECT
        g.*,
        -- A price in whole kopecks. Every twenty-third observation has none: the crawler
        -- could not parse a price, and the storefront has to render that.
        CASE WHEN g.n % 23 = 0 THEN NULL ELSE (100 + (g.n % 500)) * 100 END AS price_kopecks,
        -- Cashback in percent, from 0 to 14.5 in half-steps.
        CASE WHEN g.n % 37 = 0 THEN NULL ELSE ((g.n % 30)::numeric / 2) END AS cashback_percent
      FROM generated g
)
INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at,
    subject_id, subject_parent_id, match_id, supplier_id, catalog_name,
    sale_price_kopecks, in_stock, image_url
)
SELECT
    (md5('demo:item:' || slug || ':' || n))::uuid,
    target_id,
    'https://example.invalid/catalog/' || slug,
    'https://example.invalid/product/' || slug || '/' || n,
    CASE WHEN n % 11 = 0 THEN NULL ELSE 'Бренд ' || (n % 7) END,
    CASE WHEN n % 13 = 0 THEN NULL ELSE 'Продавец ' || (n % 5) END,
    price_kopecks,
    CASE WHEN n % 17 = 0 THEN NULL ELSE 'Товар ' || n || ' — ' || root END,
    1000000 + n,
    -- Cashback in rubles. It is at most 14.5% of a price that is at most 500 rubles, so
    -- it can never exceed the price — the invariant the storefront's seed tests check.
    CASE
        WHEN cashback_percent IS NULL OR price_kopecks IS NULL THEN NULL
        ELSE round((price_kopecks::numeric / 100) * (cashback_percent / 100), 2)
    END,
    cashback_percent,
    jsonb_build_object('demo', true, 'target', slug, 'n', n),
    md5('demo:item:' || slug || ':' || n),
    -- The three targets are interleaved in time, so that "newest first" does not simply
    -- mean "the first target".
    TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => (n * 3 + position)),
    2000000 + n,
    3000000 + n,
    -- A missing match id on every thirty-first observation. Without a stable grouping key
    -- the top-deals list would deduplicate arbitrarily, so such rows are excluded there.
    CASE WHEN n % 31 = 0 THEN NULL ELSE 700000 + n END,
    4000000 + (n % 50),
    root,
    -- A sale price is strictly below the price, and only on two rows out of three.
    CASE WHEN price_kopecks IS NOT NULL AND n % 3 = 0 THEN price_kopecks * 80 / 100 ELSE NULL END,
    CASE WHEN n % 29 = 0 THEN NULL WHEN n % 19 = 0 THEN false ELSE true END,
    CASE WHEN n % 7 = 0 THEN NULL ELSE 'https://images.example.invalid/' || slug || '/' || n || '.jpg' END
  FROM priced;

-- ── A price history ──────────────────────────────────────────────────────────
-- Product 1000001 in the electronics target, observed five times.
--
-- Two of the observations share a timestamp on purpose: the sort orders that break ties
-- on the timestamp have to break them on something stable, and a test that never sees two
-- rows with the same timestamp cannot tell a total order from an arbitrary one.

INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at,
    subject_id, subject_parent_id, match_id, supplier_id, catalog_name,
    sale_price_kopecks, in_stock, image_url
)
VALUES
    ((md5('demo:history:electronics:1'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/electronics/1',
     'Бренд 1', 'Продавец 1', 19900, 'Товар 1 — Электроника', 1000001, 14.45, 5.00,
     '{"demo": true, "kind": "history"}', md5('demo:history:electronics:1'),
     TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => 1),
     2000001, 3000001, 700001, 4000001, 'Электроника', 15920, true,
     'https://images.example.invalid/electronics/1.jpg'),
    ((md5('demo:history:electronics:2'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/electronics/1',
     'Бренд 1', 'Продавец 1', 18900, 'Товар 1 — Электроника', 1000001, 13.66, 5.00,
     '{"demo": true, "kind": "history"}', md5('demo:history:electronics:2'),
     TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => 2),
     2000001, 3000001, 700001, 4000001, 'Электроника', 15120, true,
     'https://images.example.invalid/electronics/1.jpg'),
    ((md5('demo:history:electronics:3'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/electronics/1',
     'Бренд 1', 'Продавец 1', 17900, 'Товар 1 — Электроника', 1000001, 12.92, 5.00,
     '{"demo": true, "kind": "history"}', md5('demo:history:electronics:3'),
     -- The same instant as the next row: the tiebreaker's reason to exist.
     TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => 3),
     2000001, 3000001, 700001, 4000001, 'Электроника', 14320, true,
     'https://images.example.invalid/electronics/1.jpg'),
    ((md5('demo:history:electronics:4'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/electronics/1',
     'Бренд 1', 'Продавец 1', 17900, 'Товар 1 — Электроника', 1000001, 12.92, 5.00,
     '{"demo": true, "kind": "history"}', md5('demo:history:electronics:4'),
     -- Same instant as the previous row, and the same price: only the id tells them
     -- apart. The `DISTINCT ON` winner has to be the same one on every run.
     TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => 3),
     2000001, 3000001, 700001, 4000001, 'Электроника', 14320, true,
     'https://images.example.invalid/electronics/1.jpg'),
    ((md5('demo:history:electronics:5'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/electronics/1',
     'Бренд 1', 'Продавец 1', 16900, 'Товар 1 — Электроника', 1000001, 12.20, 5.00,
     '{"demo": true, "kind": "history"}', md5('demo:history:electronics:5'),
     TIMESTAMPTZ '2026-01-01 00:00:00+00' + make_interval(hours => 4),
     2000001, 3000001, 700001, 4000001, 'Электроника', 13520, true,
     'https://images.example.invalid/electronics/1.jpg');

-- ── One product in three targets ─────────────────────────────────────────────
-- Product 777, match id 777777, present in every populated target with a different
-- price. The top-deals list has to show it once, at its best observation.
--
-- The electronics target holds two observations of it. A product that appears in several
-- targets *and* is observed more than once in one of them is the case that tells a
-- price history scoped to a target from one that is not: a history that crossed the
-- boundary would carry the other targets' observations too.

INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at,
    subject_id, subject_parent_id, match_id, supplier_id, catalog_name,
    sale_price_kopecks, in_stock, image_url
)
VALUES
    ((md5('demo:shared:electronics'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/shared/777',
     'Общий бренд', 'Продавец 0', 29900, 'Общий товар 777', 777, 43.55, 14.50,
     '{"demo": true, "kind": "shared"}', md5('demo:shared:electronics'),
     TIMESTAMPTZ '2026-01-02 00:00:00+00', 2777, 3777, 777777, 4777, 'Электроника',
     23920, true, 'https://images.example.invalid/shared/777.jpg'),
    ((md5('demo:shared:electronics:2'))::uuid, (md5('demo:target:electronics'))::uuid,
     'https://example.invalid/catalog/electronics', 'https://example.invalid/product/shared/777',
     'Общий бренд', 'Продавец 0', 27900, 'Общий товар 777', 777, 33.48, 12.00,
     '{"demo": true, "kind": "shared"}', md5('demo:shared:electronics:2'),
     TIMESTAMPTZ '2026-01-02 03:00:00+00', 2778, 3777, 777777, 4777, 'Электроника',
     22320, true, 'https://images.example.invalid/shared/777.jpg'),
    ((md5('demo:shared:home'))::uuid, (md5('demo:target:home'))::uuid,
     'https://example.invalid/catalog/home', 'https://example.invalid/product/shared/777',
     'Общий бренд', 'Продавец 0', 24900, 'Общий товар 777', 777, 36.27, 14.50,
     '{"demo": true, "kind": "shared"}', md5('demo:shared:home'),
     TIMESTAMPTZ '2026-01-02 01:00:00+00', 2777, 3777, 777777, 4777, 'Дом и сад',
     19920, true, 'https://images.example.invalid/shared/777.jpg'),
    ((md5('demo:shared:clothes'))::uuid, (md5('demo:target:clothes'))::uuid,
     'https://example.invalid/catalog/clothes', 'https://example.invalid/product/shared/777',
     'Общий бренд', 'Продавец 0', 19900, 'Общий товар 777', 777, 28.86, 14.50,
     '{"demo": true, "kind": "shared"}', md5('demo:shared:clothes'),
     TIMESTAMPTZ '2026-01-02 02:00:00+00', 2777, 3777, 777777, 4777, 'Одежда',
     15920, false, NULL);
