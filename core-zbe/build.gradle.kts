plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    api(project(":core-net"))
    // BoundedHttp, DownloadFailure, AlertSoundMode/AlertDeliveryPolicy and the voice classes: the zone file is downloaded
    // like the camera file and announced through the same delivery policy.
    api(project(":core-cameras"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
