plugins {
    // No `version` here: the Kotlin plugins are already on the settings classloader
    // (see settings.gradle.kts) and Gradle rejects a version for a plugin that is
    // already on the classpath.
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.spring") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    // The Gradle plugin's own version is independent of the ktlint CLI version pinned in
    // the version catalogue (`ktlint = "1.3.0"`), which the ktlint extension below reuses.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    // One version for the coverage plugin: it is applied in more than one module,
    // and two versions of the same plugin do not share a report.
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
}

allprojects {
    group = "ru.wbparser"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    // The JDK version of the whole project, in one place.
    //
    // It used to be repeated as `jvmToolchain(21)` in each of the three module
    // scripts, which meant three places to forget: a module could compile against
    // a different JVM than its neighbours, and nothing would say so until a
    // class-file version mismatch showed up at runtime. CI and the Docker image
    // pinned their own copies on top of that.
    //
    // The toolchain provisions itself: the foojay resolver in settings.gradle.kts
    // downloads a matching JDK when the machine does not have one, so a fresh
    // clone builds without installing anything.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
            jvmToolchain(22)
        }
    }

    // Testcontainers talks to the Docker daemon over its API, and recent daemons
    // (>= 25) reject the version docker-java negotiates by default (1.32) with
    // "client version 1.32 is too old". The pin used to live in one module's test
    // task — the one that needed it first — so every other module that started a
    // container failed with an error that says nothing about the database.
    tasks.withType<Test>().configureEach {
        systemProperty("api.version", "1.44")
        environment("DOCKER_API_VERSION", "1.44")
    }

    // The linters were declared in the version catalogue but never applied, so the
    // `ktlintCheck` / `detekt` tasks the CI workflow invokes did not exist — the lint
    // job could only ever fail with "Task not found". Applying them here makes the
    // tasks real and lets `./gradlew check` enforce style and complexity rules.
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // The detekt plugin adds its `detekt` task to `check` on its own. The ktlint
    // plugin does not add `ktlintCheck` — verified with `gradlew check --dry-run`,
    // which is now Part A3 of scripts/check-gate-wiring.py.
    //
    // The consequence was invisible: `./gradlew build` compiled and tested but did
    // not enforce style, and the CI build job had to pass `-x ktlintCheck -x detekt`
    // explicitly. That exclusion is the tell — a lifecycle that needed to be told
    // not to run a linter was not running it in the first place.
    //
    // `tasks.matching { }.configureEach { }` rather than `tasks.named("check")`:
    // a `subprojects { }` block runs before each subproject's own build script, so
    // `named` is evaluated while `check` does not exist yet and fails with
    // "Task with name 'check' not found". `matching` is lazy, so it attaches when
    // the task appears and does nothing when it never does — which is the correct
    // outcome for a module with no lifecycle task.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("ktlintCheck")
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // Use the project's own ruleset (gradle/detekt.yml) rather than detekt defaults —
        // it already encodes this codebase's chosen trade-offs (MagicNumber off, etc.).
        buildUponDefaultConfig = false
        config.setFrom(rootProject.files("gradle/detekt.yml"))
        // One baseline per module: subprojects running in parallel would overwrite a
        // single shared file, each dropping the other's entries.
        baseline = file("detekt-baseline.xml")
    }

    // "1.3.0" mirrors `ktlint` in gradle/libs.versions.toml (ktlint CLI, not the plugin).
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.0")
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

// ==============================================================================
// Coverage: the report that sees the whole repository
// ==============================================================================
//
// A module's own report only contains the classes of that module. The integration
// module therefore contributed nothing to the coverage of the code it exercises:
// `CrawlRunner` showed 0 of 153 covered lines even though the integration suite
// drove it against a real PostgreSQL.
//
// The root project is the merging module: it has no sources of its own, and the
// `kover` dependencies make its report the union of the modules' classes and of
// the coverage their own suites produced. The report below is therefore the
// honest number for the repository.
dependencies {
    kover(project(":app"))
    kover(project(":wbparser"))
    kover(project(":tests"))
}

// ==============================================================================
// Dependency analysis (DAGP)
// ==============================================================================
// The plugin compares what the build declares against what the bytecode
// actually uses. Anything it reports and we have not consciously accepted
// fails the build.
//
// The accepted-findings list lives here, in the plugin's own DSL, and it is
// the baseline: `exclude("group:artifact")` suppresses that dependency, and
// everything outside the list breaks the build. The list may only shrink in
// substance: an entry that describes how a dependency is packaged — a starter
// is a pom that carries no classes, an aggregator brings parts that cannot be
// declared apart — cannot be removed by any change in this repository, while an
// entry that hides a finding a line of source could close must never be added.
// See docs/decisions/2026-10-19-dependency-analysis-plugin.md.
configure<com.autonomousapps.DependencyAnalysisExtension> {
    issues {
        all {
            onAny {
                severity("fail")

                // ── The accepted-findings list (the baseline) ──────────────────
                // Every entry is a finding we have consciously accepted. Entries
                // are removed as the underlying problems are fixed; the list may
                // only shrink in substance, and a new entry needs a reason in a
                // comment. The two kinds below are the exceptions that no change
                // in this repository can close: a pom does not start carrying
                // classes, and an aggregator does not stop aggregating.

                // -- Spring Boot starters -----------------------------------------
                // A starter is a pom that carries no classes of its own: it pulls
                // other artifacts in. Bytecode analysis sees no class in it and
                // calls the dependency unused, which is a true statement about the
                // starter and a false statement about the application.
                //
                // This used to be one broad regex — `org.springframework(\.[a-z]+)?:.*`
                // — which hid the whole of Spring from the gate, real libraries
                // included. It is narrowed to the starters: everything behind them
                // is now declared by the module that uses it, so the gate can see
                // it.
                excludeRegex(
                    "org\\.springframework\\.boot:spring-boot-starter-.*",
                )

                // -- Aggregator artifacts -----------------------------------------
                // Kotest and MockK are declared as one artifact each; the parts
                // they are built from are implementation detail.
                exclude(
                    "io.kotest:kotest-assertions-shared",
                    "io.kotest:kotest-common",
                    "io.kotest:kotest-framework-engine",
                    "io.mockk:mockk-core",
                    "io.mockk:mockk-dsl",
                )

            }
        }
    }
    reporting {
        // The report goes to a file (CI uploads it as an artifact); the console
        // stays readable.
        printBuildHealth(false)
    }
}

