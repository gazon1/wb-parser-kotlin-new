plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.springframework.boot") version "3.4.1"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

springBoot {
    mainClass.set("ru.wbparser.app.WbParserMainKt")
}

val wbparserClassesDir = project(":wbparser").layout.buildDirectory.dir("classes/kotlin/main")

tasks.bootJar {
    archiveFileName.set("app.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // wbparser is an `implementation` project dependency. Its compiled classes
    // are in wbparser/build/classes/kotlin/main/ — copy them into BOOT-INF/classes
    // so they are on the application classpath at runtime.
    dependsOn(":wbparser:classes")
    from(wbparserClassesDir) {
        into("BOOT-INF/classes")
    }
}

dependencies {
    implementation(project(":wbparser"))

    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.arrow-kt:arrow-core:2.2.3")

    implementation("org.springframework.boot:spring-boot-starter-web:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-security:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.4.1")
    // The application reads `spring.datasource.*` and gets a pooled DataSource from
    // Spring Boot's auto-configuration, which only activates when spring-jdbc is on
    // the classpath. That classpath entry used to arrive by accident, through the
    // crawler's unused shedlock-provider-jdbc-template; removing it took the
    // DataSource — and with it the ability to start — away.
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.4.1")
    implementation("org.springframework.boot:spring-boot-configuration-processor:3.4.1")

    // The crawler hands this module its types, so the versions are pinned here as
    // well: leaving them to the starters would mean the application and the
    // crawler could resolve different versions of the same library.
    implementation("ch.qos.logback:logback-classic:1.5.14")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("io.micrometer:micrometer-core:1.15.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")

    // Flyway belongs to this module: the migrations live in
    // src/main/resources/db/migration and the configuration in application.yml.
    // It used to be an `implementation` dependency of the crawler module, which
    // put it on the runtime classpath by accident — no source file names it, so
    // nothing noticed, and the day it was cleaned up every migration would have
    // stopped running.
    runtimeOnly("org.flywaydb:flyway-core:11.20.3")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:11.20.3")

    testImplementation("org.springframework.security:spring-security-test:6.1.0")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}

tasks.test {
    useJUnitPlatform()
}
