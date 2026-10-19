---
title: "The JDK version is one number in one place, and the toolchain provisions itself"
date: 2026-10-19
tags: [build, toolchain, ci, docker]
---

## Context

The project ran on JDK 21, and the version was written down in seven places that had
to agree with each other and did not:

- `jvmToolchain(21)` in each of the three module build scripts,
- `java-version: "21"` in five `actions/setup-java` steps in `ci.yml`,
- `eclipse-temurin:21-jdk-alpine` and `eclipse-temurin:21-jre` in `Dockerfile.app`,
  and `eclipse-temurin:21-jre` in `Dockerfile.debug`,
- prose in `gradle.properties` and in `deploy/docker-compose.vps.yml`.

Bumping the JDK therefore meant finding all of them, and a missed one would not fail
loudly: a module compiled on a different JVM than its neighbours still compiles, and
the mismatch surfaces later as a class-file version error at runtime, in whichever
place happens to load the class first.

There was a second, older problem underneath. `gradle.properties` used to pin
`org.gradle.java.home=/home/max/.jdks/temurin-21.0.10`, which made the repository
buildable on exactly one machine: Gradle rejects that path everywhere else, so a fresh
clone could not compile at all — including in CI, where the failure reads as a
mysterious "Java home supplied is invalid". The pin was removed, but nothing replaced
it: a machine without a JDK 21 simply could not build.

## Alternatives considered

1. **Keep the version repeated, and bump every occurrence by hand.** Rejected. Seven
   copies of one number is the defect, not the configuration; the next bump has the
   same seven chances to miss one.
2. **Pin the JDK in CI and the image only, and require a local install.** Rejected for
   the same reason the old pin was removed: it makes "can I build this?" a property of
   the machine rather than of the repository.
3. **One version in the build, and a toolchain that provisions itself (chosen).** The
   version lives in the root build script; the foojay resolver downloads a matching
   JDK when the machine has none.

## Decision

The JDK version is `22`, and it is written down once, in the root build's
`subprojects { }` block:

```kotlin
plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
        jvmToolchain(22)
    }
}
```

Every Kotlin module gets it from there. The module build scripts no longer mention a
Java version at all, so there is nothing left in them to forget.

The toolchain provisions itself. `settings.gradle.kts` applies
`org.gradle.toolchains.foojay-resolver-convention`, so a machine without a JDK 22
downloads a matching one (Temurin) into the Gradle user home instead of failing with
"No matching toolchains found for requested specification".

CI and the Docker image keep pinning the version explicitly, because they should not
depend on a download at build time:

- `ci.yml`: `actions/setup-java` with `java-version: "22"` in every job that needs a
  JVM.
- `Dockerfile.app`: `eclipse-temurin:22-jdk-alpine` for the builder (it needs javac),
  `eclipse-temurin:22-jre` for the runtime stage.
- `Dockerfile.debug`: `eclipse-temurin:22-jre`.

`gradle.properties` keeps no `org.gradle.java.home`. A machine that must use a
specific JDK sets it in `~/.gradle/gradle.properties`, user-level and never
committed.

## Rationale

The toolchain is the mechanism Gradle offers for exactly this problem, and it is the
one the repository was already half using — it just never let it provision anything.
The old pin and the toolchain solve the same concern (a build must not silently use
the wrong JDK) in opposite ways: the pin made the build depend on one machine's
filesystem, the toolchain makes it depend on nothing but the declared version.

The foojay resolver is a download at build time, and that is a cost worth naming: on
a machine without a JDK 22 the first build fetches one (~200 MB) and every later
build reuses it. CI and the image never pay it, because both provide the JDK
themselves.

Kotlin 2.4.21, Spring Boot 3.4.1 and Gradle 8.14.6 all support Java 22, and the
compiler now emits class files at version 66 (Java 22) instead of 65.

## Consequences

- **Always** set the JDK through the toolchain in the root build's `subprojects { }`
  block. Never repeat the version in a module script — a copy is a place to forget.
- **Never** pin `org.gradle.java.home` in a committed `gradle.properties`. A path that
  exists on one machine cannot exist on another, and the failure looks like a Gradle
  bug rather than a configuration error.
- **Always** keep the CI and the Docker image on the same JDK version as the
  toolchain. The build may run on a downloaded JDK locally, but CI and the image must
  not silently drift to another version.
- **Never** bump one surface alone. The version, `ci.yml`, `Dockerfile.app` and
  `Dockerfile.debug` change in the same commit; `./check.sh` and a Docker build are
  the two halves of the verification.
