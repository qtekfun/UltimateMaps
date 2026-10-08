plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The version lives in a single place, `appVersion` in gradle.properties (SemVer, or `-rc.N`). The versionCode
 * is derived from it, independent of dates or the machine (reproducible builds):
 * X.Y.Z-rc.N -> (X*10000 + Y*100 + Z) * 100 + N, and 99 for a final version, which therefore sorts after its rcs.
 */
val appVersion = providers.gradleProperty("appVersion").get()

fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(version)
        ?: error("appVersion must be X.Y.Z or X.Y.Z-rc.N: $version")
    val (major, minor, patch, rc) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (rc.isEmpty() || rc.toInt() in 1..98))
    val base = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    return base * 100 + (rc.toIntOrNull() ?: 99)
}

/** Release signing from the environment (CI secrets); without it, the release APK is left unsigned. */
val releaseKeystore: String? = System.getenv("UM_KEYSTORE_FILE")

android {
    namespace = "com.qtekfun.ultimatemaps"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qtekfun.ultimatemaps"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        ndk { abiFilters += "arm64-v8a" } // RNF-07: arm64-v8a is mandatory
    }

    // CoMaps reads the APK assets with a random-access reader: they cannot be compressed
    // (see docs/phase1/native-core.md). Only affects the core's extensions.
    androidResources {
        noCompress += listOf("txt", "bin", "json", "config", "csv", "mwm", "dat")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("UM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UM_KEY_ALIAS")
                keyPassword = System.getenv("UM_KEY_PASSWORD")
            }
        }
    }

    // Reproducible builds (F-Droid): no encrypted Google dependency block in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // The git commit does not go into the APK: a build from a tarball must match.
            vcsInfo.include = false
            // Not minified for now: JNI (:native-comaps) and MapLibre have not been tested minified yet.
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("foss") { dimension = "dist" }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 (SDK 36) environment reaches into java.base internals (FileDescriptor, shared memory).
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    lint {
        abortOnError = true
    }
}

// The repository's NOTICE (third-party notices and licence texts) is shown in the About settings as an asset.
abstract class CopyNotice @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun copy() {
        fs.copy {
            from(notice)
            rename { "NOTICE.txt" }
            into(outDir)
        }
    }
}

val copyNotice = tasks.register<CopyNotice>("copyNotice") {
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyNotice, CopyNotice::outDir)
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core-geo"))
    implementation(project(":core-net"))
    implementation(project(":core-map"))
    implementation(project(":core-search"))
    implementation(project(":core-routing"))
    implementation(project(":core-fuel")) // gas stations: data contract (RF-15)
    implementation(project(":core-transit")) // public-transport timetables: index, planner, data manager
    implementation(project(":core-cameras")) // optional speed-camera and traffic layers, warner
    implementation(project(":core-chargers")) // optional EV-charging-station layer
    implementation(project(":core-zbe")) // optional low-emission-zone layer, route warning and prompt
    implementation(project(":core-routes")) // optional hiking and cycling route overlay
    implementation(project(":core-nav")) // route tracking and NavigationController (navigation service)
    implementation(project(":core-voice")) // instruction text, voice queue and navigation settings
    implementation(project(":native-comaps")) // CoMaps core (search), deferred startup; M2
    implementation(project(":core-data")) // places and lists (M3)
    implementation(libs.androidx.sqlite.framework) // Android SQLite driver for :core-data (M3)
    implementation(project(":core-regions"))
    implementation(project(":core-fuel")) // gas stations: data and settings (F2b)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test) // virtual time for the transit trip host tests
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
