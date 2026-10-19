plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.springframework.boot") version "3.4.1"
    // The coverage plugin, applied here as well: the application module is where
    // the wiring lives, and wiring is exactly what nothing measures.
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    // The JDK version is set once, in the root build's `subprojects { }` block.
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

springBoot {
    mainClass.set("ru.wbparser.app.WbParserMainKt")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The crawler module reaches the image as a jar in BOOT-INF/lib, the way every
    // other dependency does. It used to be copied into BOOT-INF/classes as well, on
    // the reasoning that a project dependency's classes are not in its jar — they
    // are. The copy shipped every crawler class twice, and the runtime-classpath
    // gate reports exactly that: a class present both loose and in a jar can come
    // from two places, and only one of them is the one the build produced.
}

/**
 * Writes the resolved runtime classpath — one absolute path per line.
 *
 * The runtime-classpath gate compares this with what the fat jar actually
 * contains. `gradle dependencies` cannot be used for that: it collapses repeated
 * subtrees, so the list it prints is not the classpath.
 */
tasks.register("writeRuntimeClasspath") {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val outputFile = layout.buildDirectory.file("runtime-classpath.txt")
    inputs.files(runtimeClasspath)
    outputs.file(outputFile)
    doLast {
        val paths =
            runtimeClasspath
                .get()
                .files
                .map { it.absolutePath }
                .sorted()
        outputFile.get().asFile.writeText(paths.joinToString(separator = "\n", postfix = "\n"))
    }
}

kover {
    // The report only — the floor lives in config/gates/coverage-floor.txt and is
    // enforced by scripts/check-coverage.py.
    reports {
        total {
            xml { onCheck = true }
        }
    }
}

dependencies {
    implementation(project(":wbparser"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.arrow.core)
    implementation(libs.resilience4j.circuitbreaker)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.aop)
    // The application reads `spring.datasource.*` and gets a pooled DataSource from
    // Spring Boot's auto-configuration, which only activates when spring-jdbc is on
    // the classpath. That classpath entry used to arrive by accident, through the
    // crawler's unused shedlock-provider-jdbc-template; removing it took the
    // DataSource — and with it the ability to start — away.
    implementation(libs.spring.boot.starter.jdbc)

    // The framework jars behind the starters.
    //
    // A starter carries no classes of its own — it is a pom that pulls other jars
    // in — so the dependency-analysis plugin could not see through it, and the
    // whole of Spring was invisible to the gate. The regex that used to hide
    // that (`org\.springframework(\.[a-z]+)?:.*`) also hid the real libraries
    // from analysis. Declaring them here makes the runtime classpath reviewable
    // and lets the gate see what the application actually uses.
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.core)
    implementation(libs.spring.context)
    implementation(libs.spring.web)
    implementation(libs.spring.security.core)
    implementation(libs.spring.security.config)
    implementation(libs.spring.security.web)
    // The embedded Tomcat carries the jakarta.servlet API that the security filter
    // compiles against. The starter deliberately excludes `tomcat-annotations-api`
    // from it, and declaring the artifact directly would silently undo that: the
    // annotations jar would come back into the runtime classpath, and into the image.
    implementation(libs.tomcat.embed.core) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }

    // The Spring Boot configuration processor used to be declared here, as
    // `implementation` — which put it on the runtime classpath, and into the image.
    // It never did anything: the processor is a javac annotation processor, so on a
    // Kotlin project it needs kapt to see the @ConfigurationProperties classes, and
    // this project has neither kapt nor Java sources. The dependency-analysis plugin
    // flagged it as unused, and it was right. If the metadata is ever wanted, add
    // kapt and declare the processor on the `annotationProcessor` configuration.

    // The crawler hands this module its types, so the versions are pinned here as
    // well: leaving them to the starters would mean the application and the
    // crawler could resolve different versions of the same library.
    runtimeOnly(libs.logback.classic)
    implementation(libs.slf4j.api)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)

    // Flyway belongs to this module: the migrations live in
    // src/main/resources/db/migration and the configuration in application.yml.
    // It used to be an `implementation` dependency of the crawler module, which
    // put it on the runtime classpath by accident — no source file names it, so
    // nothing noticed, and the day it was cleaned up every migration would have
    // stopped running.
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)

    // The application module had no tests at all: every line of wiring — the admin
    // filter, the configuration binding, the health probe — was exercised only by the
    // startup gate in the `tests` module, which boots the whole context and asserts
    // that it starts. "It starts" is not "it works": nothing noticed that the admin
    // filter accepted every key, or that the lock timeout was read as minutes when
    // the code wanted milliseconds.
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // MockHttpServletRequest / MockHttpServletResponse — the servlet API itself
    // arrives with the embedded Tomcat, which is an `implementation` dependency.
    testImplementation(libs.spring.test)
    // SQLite for the health probe: a real database that needs no server and no
    // container, so the readiness check is tested against a working DataSource
    // rather than against a stand-in for one.
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    useJUnitPlatform()
}
