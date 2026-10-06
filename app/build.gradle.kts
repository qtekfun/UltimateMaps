plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.qtekfun.mapas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qtekfun.mapas" // provisional
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "dist"
    productFlavors {
        create("foss") { dimension = "dist" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-geo"))
    implementation(project(":core-net"))
    implementation(project(":core-map"))
    implementation(project(":core-search"))
    implementation(project(":core-routing"))
}
