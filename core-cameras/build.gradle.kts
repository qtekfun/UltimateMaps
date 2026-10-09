plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    api(project(":core-net"))
    api(project(":core-map")) // LocationFix, for the warner's simulated-source tests and the free-driving feed
    api(project(":core-voice")) // VoiceGuide, Utterance, spoken distances
    api(libs.kotlinx.coroutines.core)
    // org.xmlpull.v1 is part of the Android platform; on the JVM (tests) kXML2 provides it.
    compileOnly(libs.kxml2)

    testImplementation(libs.kxml2)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }
