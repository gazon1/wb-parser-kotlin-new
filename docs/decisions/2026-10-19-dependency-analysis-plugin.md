---
title: "Dependency drift is a gate, not a reviewer's job"
date: 2026-10-19
tags: [build, dependencies, gates, ci]
---

## Context

The build declared dependencies nobody used, and the modules compensated for each other's
visibility by hand:

- `wbparser` declared `jsoup`, `crawler-commons`, `caffeine`, `commons-csv`, `ktor-client-mock`,
  `resilience4j-retry`, `shedlock-spring` and `shedlock-provider-jdbc-template`. No file in the
  module imports any of them.
- `wbparser` is consumed as `implementation(project(":wbparser"))`, so nothing it declares reaches
  a consumer's compile classpath. `app` and `tests` each re-declared `exposed-core`, `postgresql`,
  `ktor-*`, `arrow-core` and `kotlinx-serialization-json` by hand to make their own code compile.
- `flyway-core` reached the runtime classpath through `wbparser` by accident, even though the
  migrations and the Flyway configuration live in `app`.

Nothing caught any of this. The repository has a strong gate culture — a shared registry, floors
for coverage and executed test counts, a lint gate, a gate-wiring audit — but every one of those
gates looks at *code*, not at the dependency graph. A dependency could be declared, unused, and
version-drifted (the version catalogue in `gradle/libs.versions.toml` is dead: the modules hardcode
versions, and the catalogue still says `flyway = 10.17.0` where the build uses `11.20.3`).

The failure mode this creates is not cosmetic. `flyway-core` is invisible to a compiler: no Kotlin
file imports it, yet removing it stops every migration from running. The same is true of
`logback-classic` (an SLF4J binding) and of `jackson-databind`, which the code uses but only
receives transitively through `jackson-dataformat-yaml`. This repository has already shipped one
such defect — `exposed-java-time` was declared `compileOnly` and the production jar crashed on the
first request while the tests stayed green.

## Alternatives considered

1. **Write our own ratchet: a script that parses the report and compares it with a committed
   baseline file.** Rejected. The text report's format is explicitly not stable, so the script
   would be a permanent liability; and it would be our own code to maintain, in a repository whose
   rules say gates should be small and provable. A machine-readable JSON report does exist and
   could back such a script later, but the plugin's own DSL already does the job.
2. **Version catalogue plus Dependabot only.** Rejected. It fixes version drift but says nothing
   about dependencies that are declared and never used, nor about types that are used and never
   declared — the two failures that actually cost us a production incident.
3. **The plugin's own DSL as the baseline (chosen).** `exclude(...)` in the plugin's DSL is a
   suppression list, and the plugin fails the build on everything outside it. The list lives in
   `build.gradle.kts`, next to the code it describes, and is reviewed as code.

## Decision

Adopt `com.autonomousapps.dependency-analysis` 3.19.2, applied to every project through the
`com.autonomousapps.build-health` settings plugin.

The accepted-findings list is the `exclude(...)`/`excludeRegex(...)` block in the root
`build.gradle.kts`. It is the baseline: every finding outside it fails the build, and entries are
removed one at a time as the underlying problems are fixed.

The Kotlin plugins move to the settings script classloader (`plugins { … apply false }` in
`settings.gradle.kts`). The plugin resolves `KotlinProjectExtension` to analyze Kotlin modules;
without the Kotlin plugin on the settings classloader it reports "KGP not found on classpath" and
analyzes no Kotlin code at all.

The gate runs on both surfaces: `./check.sh` (step 10) and the `dependency-health` job in
`ci.yml`. It needs a JVM and build output, so it cannot live in `scripts/ci/static-gates.sh` —
that registry is for gates that need neither.

## Rationale

The plugin is the only tool in this stack that compares declarations against bytecode, and it
found the whole list in one run: ten unused dependencies in `wbparser`, four in `app`, and the
whole set of hand-written compensations between the modules. The first analysis produced 106
lines of findings; the accepted list is what is left after deciding, one by one, which findings
are real.

Using the plugin's DSL instead of a bespoke script keeps the baseline reviewable in a diff of a
build file. It also means the "baseline may only shrink" rule is not enforced by code — it is
enforced by review, by the rule below, and by the fact that the list is visible in every change
to the build.

## Consequences

- **Never** add an entry to the accepted-findings list in `build.gradle.kts` without a reason in a
  comment next to it. The list is a record of known problems, not a place to make the gate quiet.
- **Always** remove the accepted-findings entry in the same change that fixes the problem it
  describes. The list may only shrink.
- **Never** run `fixDependencies` in CI. It rewrites build scripts and cannot see runtime-only
  dependencies, so it can remove one that the application needs at startup. It is a manual tool,
  and `./check.sh` must pass afterwards.
- **Never** declare a dependency in `implementation` when only the runtime needs it. A runtime-only
  dependency belongs in `runtimeOnly`; that is what the plugin asks for, and it is the difference
  between a dependency the compiler can check and one it cannot.
- **Always** report a dependency that the plugin flags but that we know to be false as an accepted
  finding with a reason — never by deleting the report, weakening the severity, or turning the
  gate off.
- The gate must stay symmetric: the same task runs in `./check.sh` and in `ci.yml`. Part E of
  `scripts/check-gate-wiring.py` compares the two surfaces.
- Gradle was upgraded to 8.14.6 in the same series: the plugin requires Gradle 8.11.1 or newer.
