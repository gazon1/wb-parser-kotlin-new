# Rolling the catalog's indexes out to a live database

`V4__catalog_indexes.sql` creates five indexes on `scraped_items`. On a fresh database the
migration is the whole story: it runs, the indexes exist, and the queries use them.

On a **live** database the story is different, and the difference is about the lock.

## The problem

`CREATE INDEX` takes a lock that blocks writes to the table for the length of the build.
`scraped_items` is the table the crawler writes to, and a crawler that waits for a lock
does not crawl: the rows it did not save are rows nobody has.

The length of the build is a function of the table's size. On a table with a few hundred
thousand rows it is seconds. On a table with tens of millions it is minutes — and every
one of those minutes is a crawler that is not working.

`CREATE INDEX CONCURRENTLY` does not take that lock. It builds the index in two passes and
takes a short lock at the beginning and at the end. The price is that it cannot run
inside a transaction, and that a build which fails leaves an invalid index behind.

## The order of operations

The migration cannot use `CONCURRENTLY` — the crawler's migrations are applied inside a
transaction, by Flyway in the application and by the test fixture. So on a live database
the indexes are built **before** the migration is applied, and the migration finds them
already there:

1. Build the indexes concurrently, by hand.
2. Apply the migration. Every statement says `IF NOT EXISTS`, so it is a no-op.
3. Check that the indexes are valid and being used.

Step 1, on the production database:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_title_trgm
    ON scraped_items USING GIN (title gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_brand_trgm
    ON scraped_items USING GIN (brand gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_seller_trgm
    ON scraped_items USING GIN (seller gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_scraped_at_id
    ON scraped_items (scraped_at DESC, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_target_scraped_at_id
    ON scraped_items (target_id, scraped_at DESC, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_scraped_items_product_latest_covering
    ON scraped_items (product_id, scraped_at DESC, id DESC)
    INCLUDE (
        target_id, title, price_kopecks, sale_price_kopecks, brand, seller,
        cashback_percent, cashback, in_stock, image_url, product_url, match_id
    );
```

The last one is the read model's index (V7): the catalog's list is built from the current
observation of each product, and a covering index makes that build an index-only scan
instead of a visit to the heap once per product. It is the widest of the indexes — it
carries the card's columns for every observation — so it is the one whose build takes the
longest and the one most worth doing concurrently.

The extension has to exist first, and it is created by the migration — so create it by
hand as well, before the indexes:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Each statement runs on its own, outside a transaction block. `psql` runs a file in a
transaction by default, so use `-c` per statement, or `psql --single-transaction` **off**:

```bash
psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm;'
psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -c 'CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scraped_items_title_trgm ON scraped_items USING GIN (title gin_trgm_ops);'
```

## Checking that it worked

A build that failed leaves an index that exists and is not used. PostgreSQL says so:

```sql
SELECT c.relname, i.indisvalid, i.indisready
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
 WHERE NOT i.indisvalid;
```

An empty result is the answer we want. A row means a build failed — drop that index and
build it again:

```sql
DROP INDEX CONCURRENTLY idx_scraped_items_title_trgm;
```

## Checking that the queries use them

The plan is the proof, not the index's existence:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, title FROM scraped_items
 WHERE title ILIKE '%стул%'
 LIMIT 24;
```

With the trigram index the plan shows a bitmap heap scan; without it, a sequential scan
over the whole table. The same check for the ordering the catalog uses:

```sql
EXPLAIN (ANALYZE)
SELECT id FROM scraped_items
 ORDER BY scraped_at DESC, id
 LIMIT 24;
```

## Why the plan does not use `CONCURRENTLY` itself

Two reasons, both of them deliberate:

- It cannot run inside a transaction block, and the crawler's migrations are applied
  inside one — by Flyway in the application and by the test fixture, which executes a
  migration file as a single batch.
- A concurrently built index is only marked valid when the build finishes. A migration
  that can leave something invalid behind is a thing an operator has to check afterwards,
  and the crawler's migrations are meant to be things that either happened or did not.

So the ordinary `CREATE INDEX` stays in the migration — it is the right thing for a fresh
database, which is every test and every new deployment — and this runbook is the
production path, where the table is large and the crawler is running.
