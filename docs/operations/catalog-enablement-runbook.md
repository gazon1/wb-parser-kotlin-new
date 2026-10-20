# Cutover — opening the catalog API in production

**What this document is for.** The catalog API is implemented, pinned and checked, and
it is not serving anything: the storefront's own pages still come from the old
database path, the production schema has never been migrated by this codebase, and
nobody has run the sequence that turns "the API works" into "the API serves the
storefront". This is that sequence.

**What it changes.** A database, a container, one Caddy route and one storefront
deployment. Each of those is listed below with the way back.

**What it does not change.** The crawler's behaviour. The crawler keeps crawling; the
catalog API only reads what the crawler wrote. Nothing here asks the crawler to do
something new.

**The order matters.** The key comes before the deploy (the application refuses to
start without it), the indexes come before the migration that names them (the
migration is a no-op when the index already exists, and a lock on a production table
is not a thing to discover during a deploy), the schema check comes after the deploy
and before anybody trusts a page, and the storefront comes last, because a storefront
that reads an API nobody has verified reads an empty catalog and renders it as a
success.

---

## 0. Before anything is touched

Four things have to be true. Each of them is checkable, and each of them has a
command that says so.

| # | What has to be true | How to check it |
|---|---|---|
| 0.1 | The storefront pins an image whose compatibility run is green | `cat contract/crawler-image.txt` in the storefront; the run is named in the pull request that moved the pin |
| 0.2 | The deploy role carries the catalog key | `grep CATALOG_API_KEY <role>/templates/wbparser.app.env.j2` — see 1.1, this is the one that is not true yet |
| 0.3 | The production schema has the columns the projection names | `python3 scripts/check-production-schema.py --url "$DATABASE_URL"` — see 3.2 |
| 0.4 | The indexes exist | `docs/operations/catalog-index-rollout.md` — see 2 |

**If 0.1 is false**, stop. A storefront that points at an image nobody checked it
against is the exact failure the pin exists to prevent.

**If 0.2 is false**, the deploy in step 3 produces a container that does not start:
`CATALOG_API_KEY` is required, and the application refuses to run without it. This
is deliberate — a catalog API reachable without a key is a catalog API anybody can
read. Fix the role first; do not work around it by starting the container by hand.

---

## 1. The key

The catalog API is authenticated by `CATALOG_API_KEY`. It is a different key from
`ADMIN_API_KEY`, and the application refuses to start if one contains the other —
two keys that are the same string are one key, and one key with two meanings is a
key whose meaning depends on which endpoint answered.

```bash
# On the deploy host, from the environment the role reads:
export WBPARSER_CATALOG_API_KEY="$(openssl rand -hex 32)"

# The same value goes to the storefront as its CRAWLER_API_KEY. The storefront
# sends it as X-Api-Key on every request; without it the API answers 401.
```

**Where the key lives.** The role writes it into the crawler's `app.env`, which
compose passes to the container literally — not into `.env`, which compose
interpolates and would expand a `$` in the value. The storefront keeps its copy in
its own deployment secret.

**Rotating it.** `CATALOG_API_KEY` holds a *list* of accepted keys, comma-separated,
and every one of them is valid. The order of a rotation is therefore a procedure,
not a mechanism: the new key is added to the list first, the storefront is switched
over to it, and only then is the old key removed from the list. The other order is
an outage with a 401 in it. The log names the key that was used by its id — eight
hexadecimal characters of the key's own digest — so an operator can see which of the
keys a request came in with, without the log holding a secret.

---

## 2. The indexes, concurrently

Two migrations create indexes on `scraped_items`: the read model's covering index
(`V7`) and the latest-observation index (`V6` creates it). Inside a transaction they
would take a write lock on the table for as long as they take to build — on a
production table with hundreds of thousands of rows, that is minutes of the crawler
not writing anything.

So the indexes are built first, concurrently, outside any transaction. The full
procedure, with the SQL and the checks, is in
[`docs/operations/catalog-index-rollout.md`](catalog-index-rollout.md). Both
migrations use `IF NOT EXISTS`, so applying them afterwards is a no-op.

```bash
# The runbook's own check, after the concurrent build:
psql "$DATABASE_URL" -c "\di ix_scraped_items_product_latest*"
```

**A good answer** is an index whose size is proportional to the table and whose
`valid` flag is true. An invalid index — the state a failed concurrent build leaves
behind — is worse than no index: the planner may use it and the planner may trust
it.

---

## 3. The deploy

### 3.1 Deploy the crawler by digest

The deploy names a digest, not a tag. A tag moves; a digest names one set of bytes,
and the storefront pins the same digest — so the image that is deployed is the image
that was checked.

```bash
just deploy <sha>
```

What the deploy does, in order: resolves the tag to a digest, writes
`/opt/wbparser/.deployed-digest`, renders `app.env` and `browser.env`, applies the
migrations (Flyway is on: the production database has been migrated by the
application itself, and its history matches the migrations in this repository), and
finally probes the route end to end.

**A good answer** is a container that reports healthy and a `.deployed-digest` file
whose contents are the digest the storefront pins.

```bash
docker compose -f deploy/docker-compose.vps.yml ps
cat /opt/wbparser/.deployed-digest
```

### 3.2 Check the schema against the migrations

The deploy applies the migrations, so the schema and the code cannot drift — but the
check is still worth running once, because it answers a different question: not
"were the migrations applied?" but "does the schema answer the projection the API
writes?".

```bash
python3 scripts/check-production-schema.py --url "$DATABASE_URL"
python3 scripts/check-production-schema.py --self-test
```

**A good answer** is a pass on the first command. The second command proves the
checker can fail — a gate that cannot fail is not a gate.

### 3.3 Ask the API, from outside

The catalog is served on the crawler's public route, under
`/api/catalog/v1`. The key is the gate.

```bash
curl -sS -H "X-Api-Key: $CATALOG_API_KEY" \
  "https://wb-parser.maxdrobin.ru/api/catalog/v1/items?pageSize=1" | head -c 400
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "X-Api-Key: $CATALOG_API_KEY" \
  "https://wb-parser.maxdrobin.ru/api/catalog/v1/ready"
```

**A good answer** is a JSON body with `items` and a `200` from `/ready`. A `401`
means the key is not what the container has; a `503` from `/ready` means the
catalog's database pool cannot connect, and no page will work until it can.

---

## 4. Watch

For the first hour, watch the three numbers that say what the catalog is doing:

| Where | What | A good answer |
|---|---|---|
| `/metrics` of the crawler | `catalog_cache_hits`, `catalog_cache_misses` | hits climbing — the aggregates are cached in the process and are being reused |
| `/api/catalog/v1/ready` | the readiness answer | `200`, with the instance id in the `checks` |
| the crawler's logs | the key id (`keyId`) of the requests | one id — the storefront, not a crowd |

**A bad answer** is a miss rate that stays high: the aggregates are being rebuilt
for every request, which means the cache is not holding. The catalog's aggregate
cache lives in the process, so this is also what a restart looks like.

---

## 5. The storefront

The storefront reads the catalog over HTTP and pins the crawler's image by digest.
Its deploy refuses to run against a crawler whose deployed digest is not the pinned
one — so the order is: the crawler's digest is deployed first (step 3), and only
then the storefront.

```bash
# In the storefront repository:
gh workflow run deploy.yml
```

**A good answer** is a storefront whose pages carry prices, and whose
`/api/catalog/v1` calls come from the storefront's key id in the crawler's logs.

---

## 6. The ways back

| Step | What to undo | How | What it costs |
|---|---|---|---|
| 1 — the key | none needed | — | — |
| 2 — the indexes | `DROP INDEX CONCURRENTLY ix_scraped_items_product_latest_covering;` | the catalog gets slower; the crawler is untouched | minutes |
| 3 — the deploy | deploy the previous digest | `just deploy <previous-sha>`; the role restores the previous `.deployed-digest` | the storefront sees the old API, which is the old contract |
| 5 — the storefront | deploy the previous storefront image | the old storefront reads the old way | — |

**The schema is not rolled back.** The migrations are additive, and Flyway's history
is the record of what was applied. Rolling a schema back under the crawler's feet is
the one move in this document that is not reversible, which is why nothing here does
it.

**The route stays.** The crawler's route already serves `/api/catalog/v1/**`, and it
did before this procedure: the key is what gates it, not the route. A route that is
closed and reopened is a configuration change with its own risk, and the key already
does the work.

---

## What this procedure does not decide

- **Whether the catalog is the only source of truth.** It is a read path over what
  the crawler wrote. The crawler still owns the data.
- **How the storefront behaves when the catalog is empty.** It renders an empty
  catalog, and that is correct: a catalog with no data and a catalog with no
  connection look the same from the outside, and only one of them is a fact about
  the database. The readiness probe and the metrics are where the difference shows.
- **How often this is repeated.** It is not. The schema is migrated by deploying,
  the indexes are built once, and the key is rotated on its own schedule.
