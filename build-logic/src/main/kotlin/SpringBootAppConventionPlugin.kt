package buildlogic

plugins {
    id("ru.wbparser.kotlin-spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
    }
}

springBoot {
    mainClass.set("ru.wbparser.app.WbParserApplicationKt")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
