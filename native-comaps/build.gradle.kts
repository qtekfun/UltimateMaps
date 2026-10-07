import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

val comapsData = rootProject.layout.projectDirectory.dir("third_party/comaps/data")
val comapsAssets = layout.buildDirectory.dir("generated/comaps-assets")

android {
    namespace = "com.qtekfun.mapas.nativecomaps"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" } // solo arm64-v8a (decision de Fase 1)
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_TOOLCHAIN=clang",
                    // Maximo 6 trabajos de compilacion/enlace en paralelo (RAM limitada).
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

// Datos de runtime del nucleo, con lista blanca. Quedan FUERA a proposito:
//  - fonts/ (incluye 06_code2000.ttf, shareware, incompatible con GPLv3),
//  - symbols/, symbols-svg/, search-icons/, styles/ (iconos Entypo CC BY-SA 3.0 y datos de render),
//  - drules_proto*.bin salvo drules_proto_walking_light.bin (el arranque del nucleo lo exige), vulkan_shaders/ (solo los usa el render, que es de MapLibre).
// Los ficheros generados (classificator.txt, categories.txt, ...) los produce scripts/comaps-prepare.sh.
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
        if (missing.isNotEmpty()) throw GradleException("Faltan datos de CoMaps $missing: ejecuta scripts/comaps-prepare.sh")
        fs.sync {
            from(data) {
                include(
                    "categories.txt", "categories_brands.txt", "classificator.txt", "types.txt", "visibility.txt",
                    "colors.txt", "patterns.txt", "countries.txt", "countries_meta.txt", "countries-strings/**",
                    "editor.config", "icudt75l.dat", "languages.txt", "mapcss-mapping.csv", "packed_polygons.bin",
                    "subtypes.csv", "transit_colors.txt", "drules_proto_walking_light.bin",
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
