plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core-geo"))
    api(project(":core-regions")) // TransitAsset, ResumableDownloader (download + SHA-256 verification)
    api(project(":core-net"))
    api(project(":core-map")) // LocationFix / LocationSource for the live follower
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
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
    providers.gradleProperty("transitDebug").orNull?.let { systemProperty("transit.debug", it) }
    args(providers.gradleProperty("transitData").orElse("").get())
}

// Builds one city's index from GTFS zips that are already on disk (nothing is downloaded). See docs/phase2/transit.md
// and scripts/build-transit.sh. Usage:
//   ./gradlew :core-transit:buildTransit -Pmanifest=scripts/transit/madrid.json -PtransitInput=DIR -PtransitOut=DIR
//   [-PtransitToday=2026-10-07] [-PtransitAllowExpired]
tasks.register<JavaExec>("buildTransit") {
    group = "build"
    description = "Turns GTFS zips into the binary transit index and its metadata sidecar."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.qtekfun.mapas.core.transit.build.TransitBuildCliKt")
    maxHeapSize = "3g"
    fun prop(name: String) = providers.gradleProperty(name).orNull
    val extra = buildList {
        prop("transitToday")?.let { add("--today"); add(it) }
        if (prop("transitAllowExpired") != null) add("--allow-expired")
    }
    args(listOf("--manifest", prop("manifest") ?: "", "--input-dir", prop("transitInput") ?: "", "--output-dir", prop("transitOut") ?: "") + extra)
}
