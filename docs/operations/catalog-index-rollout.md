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

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_scraped_items_product_latest_covering_v2
    ON scraped_items (product_id, scraped_at DESC, id DESC)
    INCLUDE (
        target_id, title, price_kopecks, sale_price_kopecks, brand, seller,
        cashback_percent, cashback, feedback_points, in_stock, image_url,
        product_url, match_id
    );
```

The last one is the read model's index: the catalog's list is built from the current
observation of each product, and a covering index makes that build an index-only scan
instead of a visit to the heap once per product. It is the widest of the indexes — it
carries the card's columns for every observation — so it is the one whose build takes the
longest and the one most worth doing concurrently.

It is built under the name `..._v2`, because the read model now reads
`feedback_points` and the included columns of an index cannot be changed in place: the
migration `V18__catalog_read_model_index_carries_the_review_points.sql` drops the old
index and renames this one to `ix_scraped_items_product_latest_covering`. Build it
before the migration is applied, as the rest of the indexes are — the migration finds it
already there and only renames it.

### When the index carries a column that a migration adds

`feedback_points` does not exist until `V16__scraped_items_feedback_points.sql` has been
applied, so the read model's index cannot be built before that. Its rollout is three
phases, not one — and the phases are about **who writes**, not only about the order of
the SQL.

The change this index belongs to changes what the writer stores.
`V17__scraped_items_cashback_is_a_percentage.sql` converts the cashback of every
existing row from the old unit to the new one, and the new release writes rows in the
new unit from its first crawl. The conversion assumes every row it reads is in the old
unit. A row written in the new unit before the conversion is read as an old one: 3 % of
2590 ₽ is stored as 77.70 ₽, the conversion reads that as 3 % = 7770 %, and the row
ends up carrying 201243 ₽ of cashback.

So the order the phases enforce is this:

> The writer that writes the new unit starts **after** the conversion has been applied,
> and the conversion is applied while **no writer runs**.

#### Phase 1 — apply V16, with the old writer still running

`V16` adds a column: it touches no row and no index, so the release that is still
running keeps writing the old unit, and the column is simply `NULL` for the rows it
writes.

The migration is applied by an instance of the new release whose writer is off
(`CRAWLER_WORKER_ENABLED=false`), and Flyway is told where to stop:

```bash
SPRING_FLYWAY_TARGET=16 CRAWLER_WORKER_ENABLED=false \
  java -jar app/build/libs/app.jar
```

The API instance on the VPS has its writer off by design, so it is the natural instance
for this phase: there the setting is an environment variable of the container, and it is
removed again for phase 3. The crawler on the workstation is the writer, and it keeps
running the release it is running.

#### Phase 2 — build the index concurrently, and prove that it is valid

The `..._v2` build from the statement list above. Then the check that decides whether
phase 3 may start:

```sql
SELECT c.relname, i.indisvalid, i.indisready
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
 WHERE c.relname = 'ix_scraped_items_product_latest_covering_v2';
```

`indisvalid = true` is the answer we want. A `CREATE INDEX CONCURRENTLY` that failed
leaves an index that exists and is not valid, and the planner ignores it — drop it and
build it again:

```sql
DROP INDEX CONCURRENTLY ix_scraped_items_product_latest_covering_v2;
```

This is a prerequisite, not a suggestion: `V18` refuses to run on an index that is not
valid — it raises, the deployment fails, and the index that works stays in place.

#### Phase 3 — apply V17 and V18, then let the new writer run

1. **Stop the crawler.** The conversion reads every row and rewrites it. A writer that
   keeps writing the old unit during the conversion leaves rows that were never
   converted, and a writer that writes the new unit during it leaves rows the
   conversion reads the wrong way round.
2. **Apply V17 and V18** — start the new release with the writer still off and without
   `SPRING_FLYWAY_TARGET` this time. V17 converts the rows; V18 finds the index built
   in phase 2, drops the old one and renames the new one to the name the queries and
   the docs use.
3. **Start the new crawler** — the new release with the writer on. Every row it writes
   is in the new unit, and the conversion has already run.

Until step 3 the crawler that runs is the old one, and the rows it writes are in the
old unit — that is what the conversion is for. The new crawler must not be started
before step 2, whatever else happens: its rows would be read by the conversion as rows
of the old unit.

Between the first and the third phase the old index is still the one in use. It does not
carry `feedback_points`, so the read model visits the heap for every observation — the
cost the new index is built to remove, and the reason the third phase is not optional.

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
  The migration that renames the index built outside it makes that true for this index
  as well: it refuses to rename an index that is not valid, so an index that a failed
  build left behind is a failed deployment and not a broken catalog.

So the ordinary `CREATE INDEX` stays in the migration — it is the right thing for a fresh
database, which is every test and every new deployment — and this runbook is the
production path, where the table is large and the crawler is running.
