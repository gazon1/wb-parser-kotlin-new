---
title: "The catalog is opened by a procedure, and the schema is never rolled back"
date: 2026-10-20
tags: [catalog, operations, database]
---

## Context

The catalog API is implemented, contracted, pinned and checked against the
storefront. It serves nothing: nobody has run the sequence that turns "the API
works" into "the API answers the storefront's pages".

Two things about the plan's premise turned out to be different from the
production reality, and both change what the cutover is:

- **Flyway is on.** The plan's §9.1 assumed Flyway was switched off on the VPS,
  and that the migrations would be applied by a separate manual procedure. The
  deploy role now migrates the database itself: the schema and the code have one
  owner, and a deploy applies a migration instead of refusing to start.
- **The deploy role does not carry the catalog key.** `CATALOG_API_KEY` is
  required — the application refuses to start without it, so a catalog API can
  never be reachable without a credential — and the role's `app.env` template
  does not provide one. A deploy of the current image to production therefore
  produces a container that does not start.

The third thing is smaller and was never in doubt: the crawler's Caddy route
already serves `/api/catalog/v1/**`, because the route sends everything but the
metrics and the actuator to the application. The key is the gate. The route was
never closed.

## Idea

- Treat the cutover as a procedure: one document, one order, and a way back for
  every step that changes something.
- Let the deploy apply the migrations, and keep the schema check as the
  *verification* of that deploy rather than as the way the schema is changed.
- Gate the catalog with the key, which is what the code already does, instead of
  with the route, which would mean closing and reopening it.

## Decision

The cutover is `docs/operations/catalog-enablement-runbook.md`. It applies the
migrations by deploying, builds the two indexes concurrently *before* the
migration that names them, verifies the schema and the API from outside, and only
then deploys the storefront.

The schema is not rolled back. The migrations are additive, and Flyway's history
is the record of what was applied; a schema rolled back under the crawler's feet
is the one move in the procedure that cannot be undone.

## Rationale

A procedure with a way back per step is what makes a cutover safe to start at
nine in the evening. Each step in the runbook lists what it changes and what
undoing it costs, so the decision to stop at any point is a decision somebody can
make without first working out how to get back.

The indexes are built before the migration because the migration is a no-op when
the index already exists (`CREATE INDEX IF NOT EXISTS`), and because a
`CREATE INDEX` inside the migration's transaction would hold a write lock on the
production table for the length of the build. The order makes the lock
impossible instead of unlikely.

The route is left alone. Closing it and reopening it would be a configuration
change with its own failure modes — and it would protect nothing the key does
not already protect. The key is the credential the storefront sends on every
request; a closed route would be a second door to the same room.

## Consequences

- **Always** write a cutover as a procedure, with the way back for every step
  that changes something, before the first step is taken.
- **Never** roll the production schema back under the crawler. The migrations
  are additive and the history is the record; a rollback is not reversible.
- **Always** build an index concurrently, before the migration that names it, and
  let the migration find it already there.
- **Never** close the crawler's route to gate the catalog. The key is the gate.
- **Always** carry the credential an image needs before deploying that image. A
  deploy that produces a container which does not start is a failure that looks
  like a crash, not like a missing configuration.
- **Always** verify the API from outside, with the key, before the storefront is
  pointed at it.
