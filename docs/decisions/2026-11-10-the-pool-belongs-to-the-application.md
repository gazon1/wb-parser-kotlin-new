---
title: "The pool belongs to the application, and the application says so in one place"
date: 2026-11-10
tags: [architecture, cleanup, database]
---

## Context

`ru.wbparser.infra.db.connect(url, user, password, poolSize)` built a HikariCP pool and
returned a `DatabaseHandle`. It had **no caller**: not in the application, not in the
tests. Every call to `connect(...)` in the repository passes a `DataSource`.

The pool that the crawler actually uses is Spring Boot's, configured in
`application.yml`:

| | maximum | minimum idle | idle-timeout |
|---|---|---|---|
| `application.yml` — the pool that runs | 10 | 2 | 30 000 |
| `connect(url, …)` — the pool nobody builds | `poolSize` (10) | 2 | **600 000** |

So the module carried a **second copy of the pool's decisions**, with a different
number in it, and nobody read either copy at runtime. Fourteen lines of it, shipped in
the production artifact and never executed.

## Idea

1. **Keep it and test it.** Rejected: a test of a factory nobody calls proves that the
   factory works, and the factory still decides a pool that no deployment has. The
   coverage would go up and the decision would stay in two places.
2. **Keep it as the module's public API for other consumers.** Rejected: the module's
   consumers are in this repository, and the app already builds the catalog's pool
   itself — thirty lines above, in the same language, with the pool that runs.
3. **Remove it, and say in the code who owns the pool.** Chosen.

## Decision

**The application owns the pools; the crawler module wraps a `DataSource` it is given.**

- `connect(url, user, password, poolSize)` is gone.
- `connect(ds: DataSource)` stays — it is what the application and the tests use, and
  what the repositories depend on.
- The HikariCP dependency leaves the `wbparser` module: a module that names a class
  has to declare its dependency, and after the removal this one names none.
- The KDoc of what remains says who owns the pool, so the next reader does not have to
  find out by looking for a caller.

The application's own pools are unchanged: the crawler's is Spring Boot's, and the
catalog's read-only one is `CatalogDataSource.create` — a second pool with its own
metrics, its own timeouts and its own read-only transactions, which is why it is
separate and why it belongs to the application that reads the catalog.

## Rationale

This repository already made this argument once, about a different number: the
coverage floor used to live in the build script *and* in `config/gates/`, and the two
disagreed — "a floor that exists in two places is a floor nobody can trust". The pool's
size was in the same state, and the ADR that removed the dead code («код, который никто
не вызывает, — уходит») already set the rule that a function with no caller is not
kept.

A public API is not a reason to keep a function. The module is published to the
application in this repository, and the application does not want this one: it wants
Spring's pool, because Spring is what owns the deployment's configuration, and the
deployment's configuration is `application.yml`.

Removing the function also removed fourteen uncovered lines from the report — but that
is a consequence, not the reason. The reason is that the decision lived in two places.

## Consequences

- **Always** let the application own the pools it uses, and let the module wrap the
  `DataSource` it is given.
- **Never** keep a second copy of a decision that lives in the configuration. The copy
  is the one that rots: it changes on its own schedule, and nobody reads it.
- **Always** remove a dependency when the module stops naming its class. A declaration
  nobody needs is a promise that somebody maintains it.
- **Never** raise coverage by testing a function that should not exist. The number
  goes up; the code stays.
