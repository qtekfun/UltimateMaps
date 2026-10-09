plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    // Storage goes through the androidx.sqlite SQLiteDriver interface: the app supplies the Android
    // driver (sqlite-framework), the tests use the bundled native SQLite on the JVM.
    api(libs.androidx.sqlite)
    implementation(libs.kotlinx.serialization.json)
    // org.xmlpull.v1 comes from the Android platform; kXML2 only for JVM tests (see :core-geo).
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.kxml2)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test { useJUnitPlatform() }
