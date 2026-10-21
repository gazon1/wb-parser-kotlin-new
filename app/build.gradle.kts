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
    // WB's subject dictionary is JSON, and the startup bootstrap parses it here.
    // The library arrived on the classpath through the crawler's dependencies until
    // the dependency-analysis gate pointed at the use without the declaration.
    implementation(libs.kotlin.serialization.json)

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
    implementation(libs.spring.beans)
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

    // The DTOs name their properties with @JsonProperty, so the annotations artifact
    // is used by name and has to be declared by the module that names it.
    implementation(libs.jackson.annotations)

    // The catalog's routes are Spring MVC controllers: @RestController, @GetMapping
    // and the rest of the annotations live in spring-webmvc, not in spring-web.
    implementation(libs.spring.webmvc)

    // HikariCP is named directly by the catalog's own pool (CatalogDataSource). It arrives
    // through spring-boot-starter-jdbc as well, but a class that a module's own source
    // names has to be declared by that module — otherwise the dependency-analysis gate
    // cannot see who uses it, and a starter upgrade could take the pool away.
    implementation(libs.hikaricp)

    // SnakeYAML parses the OpenAPI contract document: the API serves it at
    // /api/catalog/v1/contract, and the crawler's tests compare the document's schemas
    // with the Kotlin DTOs. The dependency is declared rather than picked up from whatever
    // happens to be on the classpath — the classpath version of the Gradle plugin is not
    // the classpath version of the application.
    implementation(libs.snakeyaml)

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

    // The contract test serialises the DTOs with the same JavaTime module the
    // application uses, so that a timestamp in the JSON is a date-time string and
    // not a number.
    testImplementation(libs.jackson.datatype.jsr310)

    // The contract test reads the DTOs' property names and nullability with Kotlin
    // reflection — that is how the document and the classes are compared.
    testImplementation(libs.kotlin.reflect)
    // MockHttpServletRequest / MockHttpServletResponse — the servlet API itself
    // arrives with the embedded Tomcat, which is an `implementation` dependency.
    testImplementation(libs.spring.test)
    // SQLite for the health probe: a real database that needs no server and no
    // container, so the readiness check is tested against a working DataSource
    // rather than against a stand-in for one.
    testImplementation(libs.sqlite.jdbc)
    // The janitor test implements the crawler's browser contract, and that contract
    // speaks in Playwright's page type. The dependency arrives with the crawler
    // module, but the test uses it directly — so it is declared here.
    testImplementation(libs.playwright)
    testImplementation(libs.mockk)
    testImplementation(libs.exposed.core)
}

// The contract document lives at the root of the repository, next to the ADR that explains
// it, and is copied onto the classpath here.
//
// One copy, not two: a second file under src/main/resources would be a second thing to
// forget to update, and the document is the contract — two copies of a contract are two
// contracts.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contract")) {
        into("contract")
    }
    // The role scripts are carried into the image as well, so that the operator of a
    // running container can apply them without a checkout of the repository next to it.
    // The tests read them from the classpath and run them against a real database, which
    // is the only way to know that the script in the repository is the script that works.
    from(rootProject.layout.projectDirectory.dir("db")) {
        into("db")
    }
}

tasks.test {
    useJUnitPlatform()
}
