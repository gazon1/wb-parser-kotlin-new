plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    compileOnly("org.springframework.boot:spring-boot-gradle-plugin:3.4.1")
    compileOnly("io.spring.gradle:dependency-management-plugin:1.1.7")
    compileOnly("io.gitlab.arturbosch.detekt:detekt-cli:1.23.7")
    compileOnly("com.pinterest.ktlint:ktlint-cli:1.3.0")
}

gradlePlugin {
    plugins {
        register("ru.wbparser.kotlin-jvm") {
            id = "ru.wbparser.kotlin-jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
        register("ru.wbparser.kotlin-spring") {
            id = "ru.wbparser.kotlin-spring"
            implementationClass = "KotlinSpringConventionPlugin"
        }
        register("ru.wbparser.kotlin-library") {
            id = "ru.wbparser.kotlin-library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
        register("ru.wbparser.spring-boot-app") {
            id = "ru.wbparser.spring-boot-app"
            implementationClass = "SpringBootAppConventionPlugin"
        }
        register("ru.wbparser.testing") {
            id = "ru.wbparser.testing"
            implementationClass = "TestingConventionPlugin"
        }
        register("ru.wbparser.lint") {
            id = "ru.wbparser.lint"
            implementationClass = "LintConventionPlugin"
        }
    }
}
