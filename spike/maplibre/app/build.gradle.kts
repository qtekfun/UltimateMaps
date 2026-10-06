plugins { id("com.android.application") }
android {
    namespace = "org.ultimatemaps.spike"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.ultimatemaps.spike.maplibre"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0"
        ndk { abiFilters += "arm64-v8a" }
    }
}
dependencies { implementation("org.maplibre.gl:android-sdk:13.6.1") }
