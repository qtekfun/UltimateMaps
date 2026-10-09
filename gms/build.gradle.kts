import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The ONLY module that touches Google Play Services. It is a dependency of the `play` flavor of :app and of nothing else:
// the `foss` flavor (F-Droid) never sees it (see docs/decisions.md and the verifyFossHasNoGms task of :app).
plugins {
    alias(libs.plugins.android.library)
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

android {
    namespace = "com.qtekfun.ultimatemaps.gms"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-geo"))
    implementation(project(":core-map"))
    // Proprietary (Android SDK licence), not part of the GPL source: only the `play` flavor is built with it.
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
