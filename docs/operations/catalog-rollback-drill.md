# The rollback drill — proving the way back before it is needed

**What this is.** A procedure for putting the previous version back, and a script
that rehearses it against a local stack. The rehearsal is the point: a rollback
procedure that has never been executed is a paragraph, and a paragraph is not a
way back.

**When it runs.** Before the production cutover of the catalog API, and again
whenever the deployment arrangement changes — a new image, a new compose file, a
new route. The drill is cheap; discovering that the way back does not work is
not.

**What it does not do.** It does not touch production. The rehearsal runs the
whole cycle against containers on this machine, with the same steps the
production rollback uses and the same commands.

---

## What is being rolled back, and what is not

The catalog API's cutover changes three things, and they do not roll back
together:

| What | How it is rolled back |
|---|---|
| The crawler's image | Redeploy the previous tag. The crawler is stateless with respect to the catalog: it reads the database and writes its own tables, and the catalog's columns are not its own. |
| The storefront's image | Redeploy the previous tag. The storefront holds no database credentials and cannot alter the schema, so there is nothing in it to undo. |
| The database schema | **Not by a rollback.** A migration is undone by a migration, not by putting an old image back. The catalog's columns are added, never dropped, so an older image keeps working against a newer schema — but a *newer* image does not work against an older schema. |

That last row is the one to remember. The catalog's projection reads columns
that migration V2 adds; an image that reads them cannot serve a database
without them, and the failure is `42703 undefined_column` — a `500` on every
page, not a graceful degradation. So the schema is the one thing whose rollback
is a separate, deliberate operation, and it is the one the phase-0 compatibility
gate (§7.6) exists to prevent.

---

## The production procedure

### The crawler

The crawler runs as a compose service on the VPS. Its image is named by `TAG` in
the compose file's `.env`, so the rollback is a tag and a restart:

```bash
cd /var/www/wb-parser          # the app directory the Ansible role owns
docker inspect wb-parser --format '{{index .Config.Image}}'   # what is running now
export TAG=<the previous SHA tag>                             # the image to go back to
docker compose pull
docker compose up -d
docker compose ps
```

The health check is the container's own: `/api/health/live` every fifteen
seconds. The catalog's readiness is a separate question and is answered by
`/api/health/ready`, which also checks that the columns the catalog reads are
there.

### The storefront

The storefront is deployed by its own workflow, from `main`. The rollback is the
same workflow with the previous commit:

```bash
# From the storefront repository, on the previous commit's tag.
git checkout <the previous SHA>
gh workflow run deploy.yml --ref <the previous SHA>
```

The deploy workflow records the running image before it replaces it
(`/tmp/product-catalog-prev-image`), so the previous image is known even when
the previous commit is not.

### The route

The Caddy route is changed by the Caddy-vps Ansible role. Its rollback is the
role's own previous state, and it is applied by re-running the role with the
previous revision — not by hand-editing the Caddyfile on the server, because a
hand-edited Caddyfile is a change the repository does not know about and the
next deploy overwrites.

### What must not be rolled back

- **The catalog role.** `wb_catalog_ro` is harmless: it changes no
  application's behaviour, and the API reads through it whether or not the
  storefront is deployed yet. Dropping it during a rollback would break the
  *next* forward deploy for no gain.
- **The denied `/api/catalog/**` route.** It is what keeps the catalog
  unreachable from outside while the API is not ready to serve.

---

## The rehearsal

The rehearsal runs the whole cycle on this machine: a stack goes up, the image
is swapped, the service comes back, and the catalog answers again.

```bash
scripts/rollback-drill.sh
```

It starts a PostgreSQL and two crawlers — the "new" one and the "old" one,
named by two image references — and does the following:

1. brings the stack up with the **new** image and waits for the catalog to
   become ready;
2. asks the catalog for a page of products and checks the answer is a page
   (not an error);
3. swaps the running container to the **old** image, exactly as the production
   rollback does;
4. waits for the catalog to become ready again;
5. asks for a page again and checks the answer is a page;
6. reports what happened, and leaves the stack in the state it found it.

**What it proves.** That the way back works with the real commands: the
container is recreated, the health check passes again, the catalog answers
again, and the storefront's contract still holds against the older image — the
storefront reads a page, so a rollback does not leave the storefront asking
for something the old crawler cannot answer.

**What it does not prove.** That the production route and the production
credentials are right. Those are the deploy workflow's job, and the rehearsal
does not pretend otherwise.

**The two images.** By default the drill uses the crawler's own local build and
the image the site is pinned to:

```bash
scripts/rollback-drill.sh --new-image wb-parser:local --old-image wb-parser:previous
```

The drill refuses to run with the same image twice: a rollback to the same
image proves nothing, and a drill that reports success in that case is worse
than no drill.

---

## What a failed rehearsal means

A rehearsal that fails is not a nuisance — it is the entire value of the drill.
The failures it can find, and what each of them means:

| What fails | What it means |
|---|---|
| The old image does not start | The old image is not runnable on this machine — a broken tag, a missing architecture, or an image that was never pushed. A rollback to it would fail in production too. |
| The old image starts but the catalog is not ready | The old image cannot serve the catalog against this database. Either the schema is ahead of the image, or the old image's configuration is wrong. |
| The catalog answers but the storefront's contract does not hold | The storefront depends on something the old crawler does not have. The rollback is not a way back for the storefront, and the cutover must not happen until that is resolved. |
| The container does not come back after the swap | The compose arrangement does not recreate the service correctly. The production rollback would leave the service down. |

The last one is the worst, and it is the one a rehearsal is most likely to
catch: the rollback path is the path nobody walks until the day it is needed.
