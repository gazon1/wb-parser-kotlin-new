plugins {
    // No `version` here: the Kotlin plugins are already on the settings classloader
    // (see settings.gradle.kts) and Gradle rejects a version for a plugin that is
    // already on the classpath.
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.spring") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    // The Gradle plugin's own version is independent of the ktlint CLI version pinned in
    // the version catalogue (`ktlint = "1.3.0"`), which the ktlint extension below reuses.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

allprojects {
    group = "ru.wbparser"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
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
// Dependency analysis (DAGP)
// ==============================================================================
// The plugin compares what the build declares against what the bytecode
// actually uses. Anything it reports and we have not consciously accepted
// fails the build.
//
// The accepted-findings list lives here, in the plugin's own DSL, and it is
// the baseline: `exclude("group:artifact")` suppresses that dependency, and
// everything outside the list breaks the build. The list may only shrink —
// see docs/decisions/2026-10-19-dependency-analysis-plugin.md.
configure<com.autonomousapps.DependencyAnalysisExtension> {
    issues {
        all {
            onAny {
                severity("fail")

                // ── The accepted-findings list (the baseline) ──────────────────
                // Every entry is a finding we have consciously accepted. Entries
                // are removed as the underlying problems are fixed; the list may
                // only shrink, and a new entry needs a reason in a comment.

                // -- Spring Boot application module ------------------------------
                // A starter artifact carries no classes of its own, and the
                // framework behind it is wired by auto-configuration, not by a
                // bytecode reference. Bytecode analysis cannot see that usage,
                // so the whole family is accepted as-is.
                excludeRegex(
                    "org\\.springframework(\\.[a-z]+)?:.*",
                    "org\\.apache\\.tomcat\\.embed:.*",
                    "org\\.hibernate\\.validator:.*",
                    "org\\.apache\\.logging\\.log4j:.*",
                    "com\\.fasterxml\\.jackson\\.(datatype|module):.*",
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

                // -- The test module needs the app's resources, not its classes ---
                // `db/migration` and `application.yml` live in the app module and
                // reach the test classpath through it. Bytecode analysis sees no
                // class reference and calls the dependency unused; it is not.
                exclude(":app")

            }
        }

        project(":tests") {
            // Temporary: until the wbparser → app boundary is explicit, the
            // test module legitimately re-declares what wbparser hides behind
            // `implementation`. Removed once the boundary uses `api`.
            onUsedTransitiveDependencies { severity("ignore") }
        }
    }
    reporting {
        // The report goes to a file (CI uploads it as an artifact); the console
        // stays readable.
        printBuildHealth(false)
    }
}

