package buildlogic

import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.pinterest.ktlint")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/gradle/detekt.yml")
    baseline = file("$rootDir/gradle/detekt-baseline.xml")
    source.setFrom(files("src"))
}
