plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    api(project(":core-net"))
    // BoundedHttp and DownloadFailure: the same bounded, policy-checked GET as the other live feeds
    api(project(":core-cameras"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json) // the small JSON envelope of the AEMET OpenData API

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
