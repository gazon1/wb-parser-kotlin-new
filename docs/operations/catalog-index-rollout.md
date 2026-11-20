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
applied, so the read model's index cannot be built before that. Its rollout is four
phases, not one — and the phases are about **who writes** and **who serves**, not only
about the order of the SQL.

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

And the second rule, which the release's own API makes necessary:

> The API that answers about the new catalog is started **after** the new crawler has
> filled it — and "filled" is checked by the coverage of the targets, not by a count
> that stopped growing (phase 4).

The second rule is not about the data — it is about what the new API answers. The new
release answers about the products that pay **review points**, and the rows that are in
the table now pay none: they were crawled without the filter, so their `feedback_points`
is `NULL`. A new API over those rows answers with an empty catalog, and it keeps
answering with it until the new crawler has been around the circle — about seven hours
for the whole catalog (`docs/RUNBOOK.md`, §9). The old API has no such condition, so it
answers with the catalog it has always answered with. That is why the API is replaced
**last**, and not in the first phase.

#### Phase 1 — apply V16, without serving traffic

`V16` adds a column: it touches no row and no index, so the release that is still
running keeps writing the old unit, and the column is simply `NULL` for the rows it
writes.

The migration is applied by an instance of the new release that neither serves nor
crawls: the web server off, the worker off, and Flyway told where to stop. Build the
new release first (`just app::build bootJar`), and run it on the machine that runs the
crawler — that is where the jar is:

```bash
CRAWLER_WORKER_ENABLED=false SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  SPRING_FLYWAY_TARGET=16 \
  java -jar app/build/libs/app.jar
```

Such an instance applies the migrations and exits — a Spring Boot application without a
web server has nothing to keep it alive. It does not crawl, so it does not compete for
the crawl lock, and it writes nothing to the catalog. `V16` is a metadata-only operation,
and the lock it takes on the crawler's table is held for a moment.

The API instance on the VPS is **not** touched in this phase: it keeps the release it
has, and it keeps serving the catalog it serves. The crawler on the workstation is the
writer, and it keeps running the release it is running.

> The old release cannot be started again after this phase. Flyway compares the
> migrations it finds in the database with the ones it carries, and `V16` is not among
> them: the old instance refuses to start with "Detected applied migration not resolved
> locally: 16". The running instance does not validate — it keeps serving — but a restart
> of it needs `SPRING_FLYWAY_ENABLED=false`, because the migrations of this rollout are
> applied deliberately, by the one-shot instance, and not by the server.
>
> A restart of the old release is a restart of the **API** only, and the worker of that
> release must be off:
>
> ```bash
> CRAWLER_WORKER_ENABLED=false SPRING_FLYWAY_ENABLED=false \
>   java -jar app/build/libs/app.jar
> ```
>
> The old release's crawler writes the cashback in the old unit, and from phase 3 on that
> unit is the wrong one: the rows it writes would be read as percentages of a percentage.
> The old crawler is therefore stopped for good in phase 3, and a restart of the old
> release in any phase starts it with the worker off — an operator who starts it with the
> worker on writes the old unit into a converted column.

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
2. **Apply V17 and V18** — the same one-shot instance as in phase 1, this time without
   `SPRING_FLYWAY_TARGET`:

   ```bash
   CRAWLER_WORKER_ENABLED=false SPRING_MAIN_WEB_APPLICATION_TYPE=none \
     java -jar app/build/libs/app.jar
   ```

   V17 converts the rows; V18 finds the index built in phase 2, drops the old one and
   renames the new one to the name the queries and the docs use.
3. **Start the new crawler** — the new release with the writer on. Every row it writes
   is in the new unit, and the conversion has already run.

Until step 3 the crawler that runs is the old one, and the rows it writes are in the
old unit — that is what the conversion is for. The new crawler must not be started
before step 2, whatever else happens: its rows would be read by the conversion as rows
of the old unit.

And the old crawler must not be started **after** step 2, whatever else happens: it
writes the cashback in the old unit into a column that now holds a percentage, and the
row is wrong by a factor of a hundred. There is no conversion that can fix it afterwards
without reading every row the old crawler has written in the meantime, so the rule is
simple — after `V17`, the old crawler does not run again. If the old release has to be
restarted in this phase, it is restarted with the worker off:

```bash
CRAWLER_WORKER_ENABLED=false SPRING_FLYWAY_ENABLED=false \
  java -jar app/build/libs/app.jar
```

Between the first and the third phase the old index is still the one in use. It does not
carry `feedback_points`, so the read model visits the heap for every observation — the
cost the new index is built to remove, and the reason the third phase is not optional.

#### Phase 4 — fill the catalog, and cut the API over

The new crawler writes observations that carry review points. The new API answers about
the products whose *newest* observation carries them, so its catalog is not empty the
moment the crawler starts: it holds the products the crawler has already reached, and it
grows as the crawler goes around the circle.

The number that says how far the crawler has come:

```sql
SELECT COUNT(*) FROM latest_product_observations WHERE feedback_points IS NOT NULL;
```

**The gate is not that this number stopped growing.** A number that stopped growing is
also what a crawler that has stopped looks like — a crawler that failed on its first
target, or whose process died, leaves the number exactly where it was — and cutting the
API over to a catalog that was never filled is a mistake that a visitor sees. The gate
is the **coverage of the targets**, checked against the crawler's own record of what it
did: every active target must have been read by the *new* crawler.

```sql
SELECT t.id, t.name, t.last_crawled_at
  FROM crawl_targets t
 WHERE t.is_active
   AND (t.last_crawled_at IS NULL
        OR t.last_crawled_at < TIMESTAMPTZ '2026-11-19 12:00:00+00');
```

An empty result is the answer we want: the crawler has been around the circle, and every
target has been read since the switch. The timestamp is the moment the new crawler was
started — the operator knows it, and it is the only thing in the check that is not in the
database. `last_crawled_at` moves only for a target that was actually read, so a target
that failed stays in the list and the cutover waits for it.

The same check in terms of the crawler's runs, for a target whose read is recorded as a
run rather than as a timestamp:

```sql
SELECT t.id, t.name
  FROM crawl_targets t
 WHERE t.is_active
   AND NOT EXISTS (
       SELECT 1
         FROM crawl_target_runs r
        WHERE r.target_id = t.id
          AND r.outcome = 'ok'
          AND r.finished_at >= TIMESTAMPTZ '2026-11-19 12:00:00+00');
```

A target that can never be read — a category WB does not answer for, an address that is
blocked — keeps the check non-empty forever. Then the cutover is the operator's
decision, and it is written down in the release notes: the alternative is a catalog that
never goes live.

The number of products is the second half of the gate, and it is a floor rather than a
plateau: the catalog the new API serves must not be empty.

```sql
SELECT COUNT(*) FROM latest_product_observations WHERE feedback_points IS NOT NULL;
```

The number is not compared with the size of the old catalog: the new catalog holds the
products that pay review points, and it is smaller than the old one by exactly the
products that pay none.

Both checks pass — the target coverage is empty and the count is above zero — and the
API instance on the VPS is replaced by the new release:

```bash
just deploy <tag>
```

Before that moment the old API serves the old catalog; after it, the new API serves the
catalog of the products that pay review points. There is no moment in between where the
new API serves an empty catalog — unless the crawler has not been around the circle yet,
and the coverage check above is what says so.

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
