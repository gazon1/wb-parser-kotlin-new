---
title: "The image is compared with the classpath the build resolved"
date: 2026-10-19
tags: [build, runtime, gate, packaging]
---

## Context

Twice in one series, the build and the image disagreed, and nothing noticed:

- `exposed-java-time` was declared `compileOnly` in the crawler module. The code
  compiled, the unit tests passed, and the application failed at runtime with
  `NoClassDefFoundError` — because the class was never in the runtime classpath.
- Removing the unused `shedlock-provider-jdbc-template` took `spring-jdbc` with
  it. Without it Spring Boot creates no `DataSource`, and the application does
  not start. The integration suites were green, because they carry the
  dependencies the application had lost.

The startup gate catches both — but it catches them as a *symptom*, at runtime,
after the image is built. Nothing compared the artifact that ships with the
classpath the build resolved. The bootJar was built and declared "fine", and a
jar with no classes in it, or a class that the code needs and the image does not
have, is exactly the kind of thing that survives every other check.

## Idea

1. **Rely on the startup gate.** It is the most faithful check there is — it runs
   the real application — but it only tells you that something is missing, not
   that the image and the build disagree. Rejected as the only check.
2. **Compare the artifact with the classpath.** Unpack the fat jar, list what it
   holds, compare it with what the build resolved. Cheap, mechanical, and it
   names the difference instead of its symptom. Chosen.
3. **Analyse the bytecodes for unreachable references.** Stronger, but it means
   writing a class-file parser. Rejected — the JDK already ships `jdeps`, which
   does exactly this against a given classpath.

## Decision

A gate compares the fat jar with the resolved runtime classpath. It runs in
`./check.sh` (step 11) and in ci.yml's `build` job, and it is
`scripts/ci/check-runtime-classpath.py`.

Three checks, each of which has bitten this project:

1. **Nothing extra ships, nothing declared is missing.** Every artifact in
   `BOOT-INF/lib` is on the resolved runtime classpath, and every artifact on it
   is in `BOOT-INF/lib`. Two differences are Spring Boot's own packaging, and
   both are named in the script: a jar with no classes in it is dropped (the
   starters are exactly that), and `spring-boot-jarmode-tools` is added.
2. **No class ships twice.** A class in `BOOT-INF/classes` that is also inside a
   jar in `BOOT-INF/lib` can be loaded from two places, and only one of them is
   the one the build produced.
3. **Every class the code references is in the image.** `jdeps` reads the
   constant pool of the compiled application and of the compiled crawler module
   and reports what it cannot resolve against the shipped jars. This is the
   check that catches a `compileOnly` leak before the JVM does.

The crawler module's classes are no longer copied into `BOOT-INF/classes` by the
bootJar. The module ships as a jar in `BOOT-INF/lib`, the way every other
dependency does — the copy shipped all 305 crawler classes twice, and check 2 is
what found it.

The resolved classpath comes from the `:app:writeRuntimeClasspath` Gradle task,
not from `gradle dependencies`: that output collapses repeated subtrees, so the
list it prints is not the classpath.

## Rationale

The startup gate and this gate answer different questions, and the project needs
both. The startup gate asks "does the application run"; this one asks "is the
image the thing the build said it would be". The second question can be
answered without running anything, before the image is pushed, and its answer
names the artifact that differs.

`jdeps` is part of the JDK, so the check costs a process, not a dependency. It
does not fail the build on its own — it exits zero even when it reports missing
dependencies — so the script reads its output rather than trusting its exit
code.

## Consequences

- **Always** compare the artifact that ships with the classpath the build
  resolved. A build that produced a jar is not evidence that the jar is right.
- **Never** ship a class twice. A class that exists both loose and in a jar can
  come from either, and only one of them was built.
- **Never** trust `gradle dependencies` output as a classpath. It collapses
  repeated subtrees; a gate that compares against it compares against a lie.
- **Always** read `jdeps` output rather than its exit code. It reports missing
  dependencies and still exits zero.
- The startup gate stays: this gate cannot see a dependency that the framework
  wires at runtime (`spring-jdbc` was missing, and no bytecode referenced it).
