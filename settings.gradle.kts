pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.spring.dependency-management") version "1.1.7"
    }
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
