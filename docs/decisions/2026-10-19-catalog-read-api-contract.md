---
title: "The storefront reads the catalog through the crawler's HTTP API"
date: 2026-10-19
tags: [catalog, api, database, security, contract]
---

## Context

The storefront read the crawler's database directly. It held its own copy of the
migrations, it opened its own connections with its own credentials, and every
question about the data was answered by SQL written in the storefront's
repository. Three things followed from that arrangement, and all three had
already cost time:

1. **The schema had two homes.** `dev/db/` in the storefront held copies of the
   crawler's Flyway migrations, and `scripts/sync-dev-schema.sh` kept them in
   step with a sibling checkout. The copies were a promise that somebody would
   remember to update them.
2. **The production schema is not the migrated schema.** Flyway is switched off
   on the VPS, so the running database carries whatever the previous stack left
   there. The storefront's queries were written against the *migrated* schema and
   were answering correctly only because the columns it reads happened to exist.
   One migration that adds a column the storefront reads — `image_url`,
   `sale_price_kopecks`, `in_stock`, `brand_id` — would have broken every page
   with `42703 undefined_column`, and nothing in the storefront's own test suite
   could have caught it, because that suite ran against its own copy of the
   schema.
3. **A read-only promise that was not one.** The storefront connected with a
   role that could only `SELECT`. The role was granted, and nothing checked that
   it stayed that way — `information_schema.role_table_grants` shows explicit
   grants and says nothing about inherited rights, ownership, or the rights
   `PUBLIC` holds.

The crawler's own guidance says the opposite of the arrangement: *the crawler is
the only writer of this schema*. The storefront was a second reader with a copy
of the schema and a second set of credentials, and the two repositories had to
agree about the shape of the data without anything that could make them agree.

## Idea

1. **One repository.** Move the storefront into the crawler's repository and let
   it call the crawler in-process. Rejected: the two have different release
   cycles, different languages and different deployment units, and a monorepo
   would not have made them atomic anyway — a merge is not a deployment.
2. **A separate catalog service.** A new deployable that owns the read API.
   Rejected for this transition: it doubles the operational surface — another
   image, another rollout, another pool of connections against the same database
   — to solve a problem that the application already in hand can solve.
3. **An HTTP read API inside the existing crawler application.** The crawler
   already serves HTTP, already owns the schema, and already runs where the data
   is. It gains a set of read-only routes; the storefront loses its database
   client entirely. Chosen.

The contract between the two is one document: `contract/catalog-v1.openapi.yaml`,
living in the crawler's repository, next to this decision.

## Decision

The crawler exposes a read-only catalog API under `/api/catalog/v1`, and the
storefront reads the catalog through it and has no database credentials at all.

**One contract.** `contract/catalog-v1.openapi.yaml` (OpenAPI 3.1) is the only
document that describes the boundary. There is no JSON Schema beside it: two
documents describing the same shapes are two sources of truth, and the one
nobody reads is the one that licenses the next drift. The links between the
document, the crawler's DTOs, the crawler's responses and the storefront's
TypeScript mirror are each checked by a test.

**The DTO field names are the storefront's names.** `priceKopecks`,
`cashbackPercent`, `targetId` — not the crawler's column names. The translation
from column to field happens once, in the crawler. The alternative is a second
vocabulary, and every field in it is a place where the two can disagree. The
storefront's pages do not change, because the shapes they were written against
do not change.

**Units are fixed in the contract.** Prices are kopecks. `cashbackRubles` is
rubles, because the column already holds rubles — the crawler's writer converts
kopecks to rubles before storing — and dividing by 100 again would be a second
conversion. The price filters are rubles at the API's edge and kopecks in the
database, and the conversion happens exactly once, in the API.

**Errors name what could not be interpreted, and a wrong number is not one of
them.** `400` is for a parameter the API cannot read at all: an id that is not
a UUID, a sort order that is not in the list, a number that is not a number. A
paging value outside its range is clamped into the range instead — a hostile
page number is a mistake in a URL, and the first page is a better answer than
an error. The storefront clamps as well, because the two answers have to be the
same one, and the contract states which behaviour applies, so a consumer that
reads the document and one that reads the code cannot end up with different
expectations.

**The catalog reads through its own pool and its own role.** A second Hikari
pool, five connections, connects as `wb_catalog_ro`: a role with `CONNECT`,
`USAGE` and `SELECT`, and with no ownership, no membership in any other role,
and no rights inherited from `PUBLIC`. The promise "the catalog cannot write"
is enforced by PostgreSQL's privilege system. `default_transaction_read_only`
stays as a second line of defence, not as the only one.

**The API is authenticated by a key that can be rotated.** `CATALOG_API_KEY`
holds a comma-separated list, so the new key can be added before the old one is
removed. The comparison is constant-time: both sides are hashed to a fixed
length first, which is the case in which a constant-time comparison is actually
constant-time. An empty or missing key stops the application from starting.
The admin key is a different secret and is not accepted by the catalog.

**The API is not published.** The container exposes the port to the internal
network only, and the public route is closed at the proxy before the first
deployment of the API. The boundary that is claimed is the narrow one that can
be checked: the API is not published, the public route is closed, the known
alternative paths are checked, and without the key the catalog is unreachable
from anywhere. The claim "only the storefront can reach the catalog" is not
made — a shared container network and a key do not provide that.

**The demo seed lives in the crawler, and it is guarded.** The seed writes into
`scraped_items`, the table the crawler writes into. It runs only when five
independent conditions hold at once: the flag is on (it is off by default), the
`demo` profile is active, the seeding connection resolves to exactly the
expected role, the database carries the expected marker, and the tables are
empty. A blocking gate renders the production templates and fails if any of them
mentions the demo profile or the flag.

**Compatibility with the production schema is a gate, not an assumption.** The
catalog's queries have to run against a restored copy of the production database
before the storefront is switched over. `SELECT 1` is not enough, and a
compatibility shim is not an option: a missing column produces `42703`, not a
null, and a shim would turn a hard failure into a quietly degraded storefront.
**Exactly one** variant is recorded in this decision when the phase-0 inventory
has been done — either the Flyway migration happens first, or the contract
explicitly degrades and says which fields are missing. Both are not implemented
at once. Until the inventory is done, this entry records the rule and not the
answer, and the cutover is blocked.

**The version in the path is a promise about breaking changes.** `/api/catalog/v1`
means: within v1, nothing that is in the contract today disappears or changes
its meaning. A new field, a new endpoint, a new optional parameter and a new
response code are additions and stay in v1 — the storefront ignores what it does
not know. A removed field, a changed type, a changed meaning of a field and a
parameter that stops being optional are breaking, and they are what a new version
is for. The storefront's mirror is regenerated from the contract and its types
are checked against it, so a breaking change that nobody prepared for is a red
build rather than a broken page. Two versions are served for a window that is
long enough for the storefront to be deployed against the new one; the window is
decided when the first breaking change is made, not now.

**A request is identified, and the identifier is echoed.** Every catalog answer
carries `X-Request-Id`, and every log line the request produces carries the same
value. A caller may send its own identifier; one that cannot be trusted is
replaced with a generated identifier rather than refused, because a read should
not fail over a diagnostic header. The reason is the report: "the storefront
showed an error" is not a diagnosis, and without an identifier shared by both
sides the two logs cannot be put next to each other.

**The key says who may read; the limit says how much.** The catalog refuses more
than six hundred requests a minute per key and answers `429` with a
`Retry-After`. The limit is counted per key — the key is the only thing that
identifies a consumer — and the counter is keyed by the key's digest, so the
credential is not kept anywhere the process could hand it out. A leaked key
without a limit is a script that pulls the whole catalog until the database
gives up, and the database's connections are shared with the crawler, which is
the component that loses data when they run out.

**The aggregates are cached, and the cache is small.** The list of targets with
their item counts and the counters are full passes over the table, and both are
called on every page render; they are cached for ten minutes, which is short
enough that a fresh crawl shows up within a quarter of an hour. The lock is per
key, because the two aggregates have nothing to do with each other and one of
them being slow is not a reason for the other one to wait. The cache is bounded:
the keys are not all fixed, and a cache that grows with the traffic is a memory
leak with a time-to-live.

**The count is measured, not guessed.** `scripts/load-catalog.sh` runs the
catalog against a filled table: at two hundred thousand observations a request
takes about 125 ms, of which the count is 27 ms and the page query about 99 ms.
Twenty percent of a request is not worth a number the visitor sees that can be
wrong, so the count is not cached. The pool of five connections is what the
measurement says the catalog may have at this volume: the throughput stops at
about twenty-one requests a second from four requests at a time onwards, and
past that point only the waiting grows. The budget of the database as a whole
is still the production database's to decide — this is the number that goes into
that decision, not the decision.

**Deep pages are paid for in the database, and the price is known.** The page
query skips the rows before the page, so a deep page costs proportionally more:
about 99 ms at a hundred thousand rows of offset, against about 100 ms for the
whole first page. Keyset pagination is the answer, and it changes the contract —
which is what the versioning rule above is for. Until the contract changes, the
cost stays and is measured rather than discovered.

## Rationale

The storefront's pages were written against a data shape. The change keeps that
shape and changes only how the data arrives, so the change can be tested by
comparing answers rather than by re-reading every page.

The schema copy was the real cost. It was not a copy of the schema but a copy of
the *expectations* about the schema, maintained by a script that had to know
about a sibling checkout. Removing it removes a class of bug that no test in
either repository could catch: the two copies agreeing while both disagreeing
with production.

The read-only role moves the boundary from a promise into a fact. A session
setting can be turned off by any code holding the same credentials; a missing
privilege cannot be turned on by anything.

The contract being one document is what makes a cross-repository change
possible without a flag day. The crawler's side is the only side that may
change it, the storefront's mirror is checked against it by a test, and the
crawler's DTOs are checked against it by a test — so the two repositories cannot
drift apart without something going red.

## Consequences

- **Never** give the storefront database credentials again. A fallback that reads
  the database "just in case" is a second code path that only runs when
  something is broken, which is exactly when it is least likely to be tested.
- **Always** read the catalog through the API, and let the API own the SQL. The
  moment the storefront writes SQL again, the schema copy returns with it.
- **Always** keep the catalog's pool separate from the crawler's. The crawl is
  the component that loses data when it runs out of connections.
- **Never** weaken `wb_catalog_ro` to make something work. If the catalog needs
  a column the role cannot see, that is a decision about the read set, made
  deliberately and recorded.
- **Always** check the effective rights of the catalog role, not the grants
  that were issued to it.
- **Never** change the contract from the storefront's side. The direction is
  always crawler to storefront, so that two unmerged changes cannot disagree.
- **Never** deploy the API publicly. The route stays closed at the proxy, and
  the check that it is closed runs before the first deployment, not after.
- **Always** rotate the catalog key on a schedule, and rotate it immediately if
  it appears in any log.
- The schema copy in the storefront, its `DATABASE_URL` and its read-only role
  are removed only after the rollback window has closed and a rollback drill has
  been run — not before.
