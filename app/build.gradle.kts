plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qtekfun.mapas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qtekfun.mapas" // provisional
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" } // RNF-07: arm64-v8a obligatoria
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
