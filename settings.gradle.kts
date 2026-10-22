pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.spring.dependency-management") version "1.1.7"
    }
}

plugins {
    // The toolchain resolver. The build asks for a JDK 22 toolchain; when the
    // machine does not have one, this plugin downloads a matching JDK instead of
    // failing with "No matching toolchains found for requested specification".
    //
    // It is here because of the rule that made this repository unbuildable on a
    // fresh clone: `org.gradle.java.home` used to be pinned to one developer's
    // local JDK path, so a clone on any other machine could not compile at all.
    // The toolchain provisions itself — no machine-specific paths, no setup step.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"

    // The dependency-analysis plugin (DAGP). The settings plugin applies
    // `com.autonomousapps.dependency-analysis` to every project via `beforeProject`,
    // so wbparser, app and tests are analyzed without touching their build scripts.
    id("com.autonomousapps.build-health") version "3.19.2"

    // The Kotlin plugins must be on the settings script classloader (or a parent of it):
    // DAGP resolves `KotlinProjectExtension` to analyze Kotlin modules, and without it
    // the plugin reports "KGP not found on classpath" and analyzes no Kotlin code.
    // `apply false` puts them on the classpath without applying them here.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

rootProject.name = "wb-parser-kotlin"

include("browser")
include("wbparser")
include("app")
include("tests")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
