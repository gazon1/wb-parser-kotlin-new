# Phase 0 — inventory and safety, before the API reaches production

**What this phase is for.** The catalog API reads the production database. Before it
does, three things have to be true, and none of them can be established by deploying
and watching:

1. we know what the production database actually looks like;
2. we know the backup can be restored, because we restored one;
3. the catalog cannot be reached from outside, and the database role the catalog uses
   cannot write.

This runbook is the procedure. It is written for the person doing it, not for the
person reading it later, so every step says what to run, what a good answer looks
like, and what to do when the answer is bad.

**What this phase changes.** It creates a database role and edits the Caddy route. It
does **not** change any application image: before phase 1 the crawler and the
storefront stay on their current versions and behave exactly as they do now. The
infrastructure changes are reversible one by one — the table at the end says how.

**The order matters.** Each step depends on the previous one: the compatibility gate
needs a restored copy, and the restored copy needs the backup, and the backup is
taken from the inventory. Do not reorder them.

---

## 0.1 — Inventory of the production database

The point of this step is to write down what the database looks like *now*, before
anything touches it. The plan's premise was that the production schema is not the
migrated schema: when this runbook was written, Flyway was switched off on the VPS,
so the running database carried whatever the previous .NET stack left there.

Flyway is on now — the production database is migrated by the application itself,
and its history matches the migrations in this repository. The inventory is still
worth running: it is the answer to "what does the database look like", and the
answer changes what the cutover has to do. If the table below shows a schema the
migrations do not describe, the deploy will apply them and the check in
`docs/operations/catalog-enablement-runbook.md` §3.2 will say so.

```bash
# On the VPS, as the database owner.
psql "$DATABASE_URL" -c "\dt public.*"
psql "$DATABASE_URL" -c "\d public.scraped_items"
psql "$DATABASE_URL" -c "\d public.crawl_targets"
psql "$DATABASE_URL" -c "SELECT to_regclass('public.flyway_schema_history')"
psql "$DATABASE_URL" -c "SHOW max_connections"
psql "$DATABASE_URL" -c "SELECT count(*) FROM public.scraped_items"
psql "$DATABASE_URL" -c "SELECT usename, application_name, state FROM pg_stat_activity"
```

Record, in the PR or the operations log:

| What | Why it matters |
|---|---|
| The tables in `public` and their columns | The catalog's projection names columns explicitly; this is the list to compare against |
| Whether `flyway_schema_history` exists | Its absence means the schema was never migrated by this codebase — the usual case |
| The uniqueness of `content_hash` | V2 scopes it to `(target_id, content_hash)`; if it is unique globally today, the migration will change it |
| The size of `scraped_items` | It decides how long the backup takes and how deep the compatibility gate's queries go |
| `max_connections` | The catalog pool is five connections; the budget of the whole database is `writer + catalog + everything else <= 0.7 * max_connections` |
| The current clients | Who else reads this database, and with which credentials |

**If `image_url`, `sale_price_kopecks`, `in_stock` or `brand_id` are missing**, the
compatibility gate in 0.5 will fail, and the answer is the migration from §9.1 of the
plan — not a change to the API. Write that down now and do not proceed past 0.5
without it.

---

## 0.2 — Take a backup, and restore it

The backup is not a step towards the compatibility gate; it is a step towards knowing
that the production data can be recovered at all. Both matter, and the second is the
one that is normally skipped.

```bash
# The backup. Keep it where the disk is not the same disk as the database.
pg_dump --format=custom --file="/var/backups/wbparser-$(date +%F-%H%M).dump" "$DATABASE_URL"

# The restore, into an isolated database.
createdb -h 127.0.0.1 -U postgres wbparser_restore_check
pg_restore --dbname=wbparser_restore_check --no-owner --no-privileges "/var/backups/wbparser-....dump"
```

Then check the restored copy, not the backup file:

```bash
psql "postgresql://postgres@127.0.0.1/wbparser_restore_check" \
  -c "SELECT count(*) FROM public.scraped_items"
```

**The restored copy holds the same data as the production database.** It is therefore
treated as production data:

- it is bound to `127.0.0.1` and its port is **not** published outside the host;
- the credentials used against it are **temporary** ones, not the production
  password, and they are dropped at the end of the phase;
- every check against it is read-only.

**If the restore does not produce a readable copy**, stop. The plan's phase 0 is
blocked: the backup does not work, and nothing that follows may proceed against a
database whose recovery is unknown.

---

## 0.3 — Provision and verify the catalog role

The catalog connects as `wb_catalog_ro`, whose rights are `CONNECT`, `USAGE` and
`SELECT`. The claim "the catalog cannot write" is enforced by PostgreSQL's privileges,
not by a setting in the application that code with the same credentials could undo.

Two scripts, in this order. The first applies, the second only looks — a script that
both applies and checks its own work cannot tell "I did what I meant" from "I did
what I always do".

```bash
# 1. Apply. The password goes in as a variable, so it does not reach the terminal,
#    the shell history or the CI log.
export CATALOG_DB_PASSWORD="$(openssl rand -base64 24)"
psql -v ON_ERROR_STOP=1 -v catalog_password="$CATALOG_DB_PASSWORD" \
     -f db/roles/provision-catalog-readonly-role.sql "$DATABASE_URL"

# 2. Verify the effective rights.
psql -v ON_ERROR_STOP=1 -f db/roles/verify-catalog-readonly-role.sql "$DATABASE_URL"
```

The verification asks PostgreSQL what the role can actually do — through
`has_table_privilege`, `has_schema_privilege` and `has_database_privilege` — rather
than reading `information_schema.role_table_grants`, which shows explicit grants and
says nothing about inherited rights, membership, ownership or the rights `PUBLIC`
holds.

**The runtime check.** The rights must be refused at the server, not merely absent
from a grant table. With the role's own credentials:

```bash
PGPASSWORD="$CATALOG_DB_PASSWORD" psql "$DATABASE_URL" \
  -c "INSERT INTO public.scraped_items (id, target_id, scraped_at) VALUES (gen_random_uuid(), gen_random_uuid(), now())"
```

The expected answer is `ERROR: permission denied for table scraped_items` — and it
must be `42501 insufficient_privilege` in the server's log, not a `400` from a proxy.
The same goes for `UPDATE`, `DELETE` and `CREATE TABLE`.

The credentials go into the application's environment as `CATALOG_DB_USER` and
`CATALOG_DB_PASSWORD`. The application's `catalog.datasource` is configured
separately from the writer's on purpose: one connection string for both would put
the writer's privileges within reach of the API.

**If the verification fails**, the script exits non-zero and says which right is
wrong. Do not work around it — a role that owns a table can do nearly everything to
it, including `ALTER` and `DROP`, and no grant row says so.

---

## 0.4 — Close the catalog route at the Caddy

The catalog must not be reachable from outside **before** the API exists. An open
route to a service that is not there is harmless; an open route to a service that is
there, before its database role has been proven, is the thing this step prevents.

In the crawler's Caddy route, add an explicit deny, and keep the operational
endpoints open:

```caddyfile
@catalog path /api/catalog/*
respond @catalog 404
```

`/api/health/live`, `/api/health/ready`, `/metrics` and `/api/admin/**` continue to
work. The deny is `404` rather than `403` on purpose: a `403` tells a scanner that
something is there.

Then check that there is no way around it:

```bash
# No published port of the crawler.
ss -tlnp | grep -E ':8080|:8088'

# Every container in the network, and every route that leads to it.
docker network inspect caddy_net --format '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{"\n"}}{{end}}'
```

**Write the list of alternative paths down.** Every container in `caddy_net` and every
Caddy route that leads to this container is an alternative path to the same service,
and that list is repeated before the cutover as an acceptance criterion. A route
closed on one path and open on another is not closed.

---

## 0.5 — The compatibility gate (§7.6)

This is the step that decides whether the cutover may proceed.

A missing column is **not** a null value: the query fails with `42703
undefined_column` and the whole endpoint fails with it. So "deploy and see" does not
work here — the API either serves the production schema or it serves nothing at all.

The gate runs the catalog's own queries against the restored copy:

```bash
# From the crawler's checkout, against the restored copy.
export PGPASSWORD="$(openssl rand -base64 24)"
psql -v ON_ERROR_STOP=1 -v catalog_password="$PGPASSWORD" \
     -f db/roles/provision-catalog-readonly-role.sql \
     "postgresql://postgres@127.0.0.1/wbparser_restore_check"

scripts/check-production-schema.py \
  --url "postgresql://wb_catalog_ro@127.0.0.1:5432/wbparser_restore_check"
```

A good answer is nine queries and no failure. A failure names the column and the
SQLSTATE, and the gate says so before it runs anything: it verifies that its
statements still cover every column the API's projection reads, because a gate that
quietly checks a subset of the API's queries is a gate that passes while the API
fails.

The gate's own control — `scripts/check-production-schema.py --self-test` — proves it
can fail: it builds the shape of the production database *before* migration V2 and
asserts that the gate reports the missing column.

### What the result means

**The gate passes.** The production schema serves the catalog's queries. The cutover
is not blocked by the schema, and phase 1 may proceed.

**The gate fails with `42703`.** The schema is behind. The cutover is blocked. The
answer is the migration of §9.1 of the plan, executed by its own approved procedure —
its own backup, its own stopping criteria, a verified restore — and the gate is then
repeated on a fresh copy.

**The gate fails for another reason.** The columns are all there and something else
is wrong: a permission, a type mismatch, a constraint. Read the message the gate
prints; it is the server's own.

> **Variant B — a temporary adaptation — exists only if the migration cannot be
> executed yet.** It requires that every absent field and its exact semantics are
> written down, that the contract and the tests explicitly confirm the degradation,
> and that the owner accepts it knowingly, with the date or event that ends it. The
> two variants are mutually exclusive, and the choice is fixed in the phase-1 ADR
> rather than made on the way.

---

## 0.6 — Clean up

The restored copy holds production data and it is not left lying on the machine:

```bash
dropdb -h 127.0.0.1 -U postgres wbparser_restore_check
# Drop the temporary role and its temporary password.
psql "$DATABASE_URL" -c "DROP OWNED BY wb_catalog_ro; DROP ROLE wb_catalog_ro;"
# Remove the temporary credentials from wherever they were stored.
```

This is a step of the phase, not something for later. A copy of the production
database on a disk is a copy of the production data on that disk.

---

## What this phase leaves behind

| Change | How it is rolled back |
|---|---|
| The deny rule for `/api/catalog/**` in Caddy | Re-applying the previous snippet |
| The role `wb_catalog_ro` | Harmless on its own — it changes no application's behaviour. Dropped separately with `DROP ROLE` if needed |
| The restored copy of the production database | Deleted at the end of the phase |
| Changes to `pg_hba.conf` or the firewall, if any were needed | The documented reverse order, recorded in the same phase |

**Done when:** the actual state of the database is known; the restore is verified; the
role exists and its rights are confirmed at the server; the catalog is unreachable
from outside; **the result of the compatibility gate is known** — either the cutover
is allowed, or the migration is declared a blocking precondition; the safe path of
the migration is defined; the route back to the current application is written down.

---

## See also

- `docs/decisions/2026-10-19-catalog-read-api-contract.md` — the contract itself.
- `docs/decisions/2026-10-20-catalog-aggregate-cache-is-in-process.md` — why the
  catalog's aggregates are cached in the process, and what that means for the
  topology of phase 5.
- `docs/operations/catalog-observability.md` — the cache's TTL, its size bound and
  what an operator sees.
- `docs/operations/catalog-index-rollout.md` — the production path for the V4
  indexes.
- `scripts/check-production-schema.py` — the compatibility gate of 0.5.
- `db/roles/` — the role's rights, their application and their verification.
