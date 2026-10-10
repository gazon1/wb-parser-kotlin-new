plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
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

tasks.bootJar {
    archiveFileName.set("app.jar")
}

dependencies {
    implementation(project(":wbparser"))

    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.14.0")
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.arrow-kt:arrow-core:2.2.3")

    implementation("org.springframework.boot:spring-boot-starter-web:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-security:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.4.1")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.4.1")
    implementation("org.springframework.boot:spring-boot-configuration-processor:3.4.1")

    implementation("ch.qos.logback:logback-classic")
    implementation("io.github.microutils:kotlin-logging:3.0.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.1")
    testImplementation("org.springframework.security:spring-security-test:6.1.0")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}

tasks.test {
    useJUnitPlatform()
}
