import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

val comapsData = rootProject.layout.projectDirectory.dir("third_party/comaps/data")
val comapsAssets = layout.buildDirectory.dir("generated/comaps-assets")

android {
    namespace = "com.qtekfun.ultimatemaps.nativecomaps"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" } // arm64-v8a only (Phase 1 decision)
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_TOOLCHAIN=clang",
                    // At most 6 parallel compile/link jobs (limited RAM).
                    "-DNJOBS=${providers.gradleProperty("comaps.njobs").getOrElse("6")}",
                )
                targets += "umcomaps"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

// Core runtime data, with an allowlist. Deliberately left OUT:
//  - fonts/ (includes 06_code2000.ttf, shareware, incompatible with GPLv3),
//  - symbols/, symbols-svg/, search-icons/, styles/ (Entypo icons CC BY-SA 3.0 and render data),
//  - drules_proto*.bin except drules_proto_default_light.bin (core startup requires it), vulkan_shaders/ (only used by the renderer, which is MapLibre's).
// The generated files (classificator.txt, categories.txt, ...) are produced by scripts/comaps-prepare.sh.
abstract class PrepareComapsAssets @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val data = dataDir.get().asFile
        val needed = listOf("classificator.txt", "categories.txt", "countries.txt", "packed_polygons.bin")
        val missing = needed.filter { !File(data, it).exists() }
        if (missing.isNotEmpty()) throw GradleException("Missing CoMaps data $missing: run scripts/comaps-prepare.sh")
        fs.sync {
            from(data) {
                include(
                    "categories.txt", "categories_brands.txt", "classificator.txt", "types.txt", "visibility.txt",
                    "colors.txt", "patterns.txt", "countries.txt", "countries_meta.txt", "countries-strings/**",
                    "editor.config", "icudt75l.dat", "languages.txt", "mapcss-mapping.csv", "packed_polygons.bin",
                    "subtypes.csv", "transit_colors.txt", "drules_proto_default_light.bin",
                )
                exclude("fonts/**", "symbols/**", "symbols-svg/**", "search-icons/**", "styles/**", "**/*code2000*", "**/*ntypo*")
            }
            into(outDir)
        }
    }
}

val prepareComapsAssets = tasks.register<PrepareComapsAssets>("prepareComapsAssets") {
    dataDir.set(comapsData)
    outDir.set(comapsAssets)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareComapsAssets, PrepareComapsAssets::outDir)
    }
}

dependencies {
    api(project(":core-geo"))
    api(project(":core-search"))
    api(project(":core-routing"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
