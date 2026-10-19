---
title: "The boundary between the crawler and the application is declared, not implied"
date: 2026-10-19
tags: [build, architecture, dependencies, spring]
---

## Context

`wbparser` is a library and `app` is a Spring Boot application, but the build did not say so.
`app` consumed the crawler as `implementation(project(":wbparser"))`, which means the crawler's
types are not on the consumer's compile classpath, and the build compensated for that by hand:

- `app` re-declared `exposed-core` and `exposed-jdbc` — not to use them, but so that the
  crawler's types would resolve while compiling.
- `tests` re-declared `exposed-core`, `exposed-java-time`, `postgresql`, `ktor-*`, `arrow-core`
  and `kotlinx-serialization-json` for the same reason, and the build file said so in comments.
- `flyway-core` reached the runtime classpath through `wbparser` — by accident. The migrations
  live in `app/src/main/resources/db/migration` and the Flyway configuration in
  `app/application.yml`, but the dependency that runs them was declared in the crawler module.

The compensation is not a style problem, it is a correctness problem. A dependency that is only
present because something else drags it in is invisible to the compiler: `flyway-core` is named
by no source file, so the day the crawler module was cleaned up, every migration would have
stopped running — with the integration suites still green, because they apply the migrations
themselves.

## Alternatives considered

1. **Leave the boundary as it is and keep the findings in the accepted list.** Rejected: the
   accepted list would grow to cover the compensations, and the accident would become a
   documented feature.
2. **Seal the boundary: no Arrow, no Exposed in the crawler's public signatures.** Rejected for
   now. It is the better end state, but it is a refactor of production code — every call site in
   `app` and `tests` changes — and it does not answer the question this repository actually has,
   which is "which of these types cross the boundary".
3. **Declare the boundary (chosen).** `wbparser` becomes a `java-library` module, and every
   dependency is declared as `api`, `implementation` or `runtimeOnly` according to what actually
   crosses it.

## Decision

`wbparser` applies `java-library`, and the dependency-analysis plugin decides what is `api`: it
reads the bytecode and reports the types that appear in the module's public signatures. The
result is the `api` set — Arrow, Exposed's core, coroutines, resilience4j's circuit breaker,
Playwright, Micrometer, kotlinx-serialization's core and Jackson's databind.

Everything else is `implementation` (used here, not named by a consumer) or `runtimeOnly` (loaded,
but named by no source file): the PostgreSQL driver, the Exposed DAO and JDBC modules, the
Kotlin reactor coroutines, the SLF4J binding.

`flyway-core` and `flyway-database-postgresql` move to `app` as `runtimeOnly`, next to the
migrations and the configuration that use them.

The `tests` module depends on both projects through `testImplementation`: it has no main source
set, and it needs the app for its resources rather than for its classes.

## Rationale

The plugin's judgement is better than ours here, because it is mechanical: "does a consumer's
source have to name this type to call ours" is a question about the compiled bytecode, not about
intention. The first run named six `api` dependencies the build had declared as `implementation`,
and it was right about each of them.

The runtime classpath does not change. `api` and `implementation` are both on a consumer's
runtime classpath; `api` additionally reaches the consumer's *compile* classpath, which is
exactly what the hand-written compensations were imitating.

The change also makes the accidental dependency impossible to keep: a dependency that is not
declared where it is used is now a finding, not a coincidence that survives until a cleanup.

## Consequences

- **Always** declare a dependency in the module that uses it. A dependency that reaches the
  runtime classpath through another module is invisible to the compiler and to the plugin.
- **Never** use `implementation` for a type that appears in a public signature. It belongs in
  `api`, or the signature has to change.
- **Always** use `runtimeOnly` for a dependency that is loaded but never named in source — a
  JDBC driver, an SLF4J binding, Flyway. It says what is true, and the plugin agrees.
- **Never** let a module's dependencies be the application's dependencies by accident. The
  migrations and the Flyway configuration are in `app`, so Flyway is in `app`.
- **Never** duplicate a dependency in a consumer to make it compile. If the consumer needs the
  type, the producer exposes it with `api`.
- The `tests` module needs the `app` project for its resources. That is a legitimate reason for
  the dependency, and it is the one case where a module appears unused to bytecode analysis.
