plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // org.xmlpull.v1 is part of the Android platform; on the JVM (tests) kXML2 provides it.
    compileOnly(libs.kxml2)

    testImplementation(libs.kxml2)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }
