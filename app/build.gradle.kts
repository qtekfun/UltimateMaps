plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * La versión vive en un solo sitio, `appVersion` en gradle.properties (SemVer, o `-rc.N`). El versionCode
 * se deriva de ella, sin depender de fechas ni de la máquina (builds reproducibles):
 * X.Y.Z-rc.N -> (X*10000 + Y*100 + Z) * 100 + N, y 99 para una versión final, que así ordena después de sus rc.
 */
val appVersion = providers.gradleProperty("appVersion").get()

fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(version)
        ?: error("appVersion debe ser X.Y.Z o X.Y.Z-rc.N: $version")
    val (major, minor, patch, rc) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (rc.isEmpty() || rc.toInt() in 1..98))
    val base = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    return base * 100 + (rc.toIntOrNull() ?: 99)
}

/** Firma de release desde el entorno (secretos de CI); sin ella, el APK de release queda sin firmar. */
val releaseKeystore: String? = System.getenv("UM_KEYSTORE_FILE")

android {
    namespace = "com.qtekfun.mapas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qtekfun.mapas" // provisional
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        ndk { abiFilters += "arm64-v8a" } // RNF-07: arm64-v8a obligatoria
    }

    // CoMaps lee los assets del APK con un lector de acceso aleatorio: no pueden ir comprimidos
    // (ver docs/phase1/native-core.md). Solo afecta a las extensiones del nucleo.
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

    // Builds reproducibles (F-Droid): sin el bloque cifrado de dependencias de Google en el APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // El commit de git no entra en el APK: una compilación desde un tarball debe coincidir.
            vcsInfo.include = false
            // Sin minificar de momento: JNI (:native-comaps) y MapLibre aún no se han probado minificados.
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
    }

    lint {
        abortOnError = true
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core-geo"))
    implementation(project(":core-net"))
    implementation(project(":core-map"))
    implementation(project(":core-search"))
    implementation(project(":core-routing"))
    implementation(project(":core-fuel")) // gasolineras: contrato de datos (RF-15)
    implementation(project(":core-nav")) // seguimiento de ruta y NavigationController (servicio de navegación)
    implementation(project(":native-comaps")) // núcleo de CoMaps (búsqueda), arranque diferido; M2
    implementation(project(":core-data")) // sitios y listas (M3)
    implementation(libs.androidx.sqlite.framework) // driver SQLite de Android para :core-data (M3)
    implementation(project(":core-regions"))
    implementation(project(":core-fuel")) // gasolineras: datos y ajustes (F2b)

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
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
