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

include("wbparser")
include("app")
include("tests")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
