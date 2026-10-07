plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// Measured run on the real Madrid feeds (spike). Not part of `test`: it needs the downloaded feeds.
// Usage: ./gradlew :core-transit:transitBench -PtransitData=$HOME/mapas-data/transit/raw
tasks.register<JavaExec>("transitBench") {
    group = "verification"
    description = "Builds the Madrid index from real GTFS feeds and measures queries."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.qtekfun.mapas.core.transit.bench.TransitBenchKt")
    maxHeapSize = "3g"
    args(providers.gradleProperty("transitData").orElse("").get())
}
