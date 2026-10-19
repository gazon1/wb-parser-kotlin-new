---
title: "The application must be startable, and something must prove it"
date: 2026-10-19
tags: [testing, gates, spring, database, flyway]
---

## Context

Nothing in this repository started the application.

The domain is tested in isolation, the repositories are tested against a Testcontainers
PostgreSQL whose schema the suite applied *by hand* (`PostgresFixture.applyMigrations` runs the
SQL files itself), the HTTP adapters are tested against fakes, and the Spring wiring is
exercised by no test at all. `application.yml`, the security chains, the Flyway
auto-configuration and the bean graph are verified only by deploying.

That gap is not neutral, because the defects that reach production through this repository are
wiring defects, not logic defects:

- `flyway-core` was declared in `wbparser` and reached the runtime classpath by accident. No
  Kotlin file imports it, so nothing — the compiler, the linters, the suites — could see the
  dependency, and the day it was cleaned up the application would have started against an empty
  database. The Flyway path had no coverage at all: the integration suites apply the migrations
  themselves, so they would have kept passing.
- `logback-classic` is an SLF4J binding. Nothing imports it, and an application without one
  starts and then logs nothing.
- `jackson-databind` was present only transitively, through `jackson-dataformat-yaml`, while the
  code uses `ObjectMapper` directly. Removing the yaml module breaks the build of a class that
  compiles today.
- The repository has already shipped one such defect: `exposed-java-time` was declared
  `compileOnly`, the production jar crashed on the first request, and the tests were green.

## Alternatives considered

1. **A shell script that starts the compose stack and curls the health endpoint.** Rejected: it
   needs its own infrastructure, its own timeouts and its own failure modes, and it would not run
   in `./check.sh` or in `tests:test` — so it would be a gate that only exists in one place, which
   is the exact arrangement `check-gate-wiring.py` exists to prevent.
2. **A CI job that runs the built jar as a process.** Rejected: slower than an in-process test,
   duplicates the assertions between two surfaces, and tells a developer nothing until they push.
3. **A `@SpringBootTest` in the `tests` module (chosen).** It starts the real application — the
   same `@SpringBootApplication` class, the same `application.yml`, the same Flyway
   auto-configuration, the same security chain — against a real PostgreSQL, inside the test task
   that both surfaces already run.

## Decision

`ApplicationStartupTest` boots the application in the `tests` module and asserts three things:

1. The context starts. A dependency that is missing at runtime fails here, at startup, with the
   class name in the error.
2. Flyway applied every migration that exists on the classpath — and only those. The expected
   set is discovered with `Migrations.discover()`, not listed: a list goes stale at V3 and the
   suite keeps asserting the V1+V2 schema while production runs something newer.
3. The tables the crawler's read and write path needs exist, and the health endpoint answers
   `UP` — the application is serving, not merely started.

The profile is `public`, which sets `crawler.worker.enabled: false`, so the scheduled crawler
never starts and the test reaches neither a browser nor the network.

`ADMIN_API_KEY` is set on the test task in `tests/build.gradle.kts`, as an environment variable:
`AdminApiKeyFilter` reads it with `System.getenv` and throws in its constructor when it is
missing, and the security chain creates that filter in every profile but `once`.

## Rationale

The suite is a gate, not a test of a component: it exists to fail when the application cannot
start. That is the property the repository lacked, and it is the property that turns "the
dependency is declared" into "the dependency is present when it matters".

It reuses what already works — the Testcontainers fixture, the discovered migration set, the
`tests:test` task — so it adds no new infrastructure and runs in both surfaces for free.

The negative control is what makes it trustworthy: removing `flyway-core` from the runtime
classpath makes the suite fail on the missing `flyway_schema_history`, instead of leaving a
green suite that would have shipped the defect.

## Consequences

- **Always** keep the startup gate green: `./check.sh` runs it as part of `tests:test`, and a
  failure there means the application does not start.
- **Never** add a dependency that is needed only at runtime to `implementation` when the
  compiler never sees it — declare it as `runtimeOnly`, so the plugin and the compiler can both
  reason about it.
- **Never** weaken the migration assertion to a fixed list of versions. The expected set is
  discovered from the classpath, so a new migration is covered the day it is added.
- **Never** make the startup gate depend on a browser, the network or AdsPower. The `public`
  profile exists for that reason: the crawler must not start in a unit test.
- **Always** supply `ADMIN_API_KEY` to the test JVM as an environment variable. The filter reads
  it with `System.getenv`; a system property is not read, and the failure looks like a
  configuration error rather than a missing variable.
- The gate needs Docker, like the rest of the integration suites, and is skipped by
  `SKIP_DB=1 ./check.sh` — which is reported explicitly, never silently.
