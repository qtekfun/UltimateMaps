plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    api(project(":core-net"))
    // BoundedHttp, DownloadFailure and LatLonBounds: the charger file is downloaded exactly like the camera file.
    api(project(":core-cameras"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }
