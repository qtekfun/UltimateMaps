plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }
