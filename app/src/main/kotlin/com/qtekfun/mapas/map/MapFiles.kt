package com.qtekfun.mapas.map

import android.content.Context
import android.content.res.AssetManager
import com.qtekfun.mapas.core.map.MapTheme
import com.qtekfun.mapas.regions.RegionStorage
import java.io.File

/** Turns the packaged style template into a style JSON for a concrete device. Pure, JVM-testable. */
object StyleTemplate {
    const val DIR_MARKER = "@MAPDIR@"
    const val PMTILES_MARKER = "@PMTILES@"

    fun render(template: String, mapDir: String, pmtilesPath: String): String =
        template.replace(DIR_MARKER, jsonEscape(mapDir)).replace(PMTILES_MARKER, jsonEscape(pmtilesPath))

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun assetName(theme: MapTheme) = if (theme == MapTheme.DARK) "map/style-dark.json" else "map/style-light.json"
}

/**
 * Locates the map data. The native engine cannot read `file://` under `Android/data` (spike finding), so
 * everything lives in the internal `filesDir`: `maps` (any .pmtiles file) for tiles, `map/` for sprites and glyphs
 * copied once from the APK assets.
 */
class MapFiles(private val context: Context) {
    private val assetsDir get() = File(context.filesDir, "map")
    private val tilesDir get() = File(context.filesDir, "maps")

    /**
     * Every PMTiles file to draw: each installed region's render asset (sorted by region id, see
     * `regions.RegionStorage`), else any .pmtiles left by hand in `filesDir/maps`. Empty when nothing is installed.
     */
    fun pmtilesList(): List<File> = RegionStorage.installedRenders(context).ifEmpty {
        tilesDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name }.orEmpty()
    }

    /** Changes when a region is installed, deleted or replaced in place (path, size and mtime of each file). */
    fun tilesSignature(): String = pmtilesList().joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }

    fun isInstalled(): Boolean = File(assetsDir, MARKER).readTextOrNull() == ASSET_VERSION

    /** Copies sprites and glyphs from the APK to `filesDir/map` if the packaged version changed. Blocking IO: call off the main thread. */
    fun install() {
        if (isInstalled()) return
        copyTree(context.assets, "map", assetsDir)
        File(assetsDir, MARKER).writeText(ASSET_VERSION)
    }

    /** Style for [theme] with one source per installed region; the result carries size metrics. No region: background only. */
    fun style(theme: MapTheme): MultiRegionStyle.Result {
        val template = context.assets.open(StyleTemplate.assetName(theme)).use { it.readBytes().toString(Charsets.UTF_8) }
        return MultiRegionStyle.build(template, assetsDir.absolutePath, pmtilesList().map { it.absolutePath })
    }

    private fun copyTree(am: AssetManager, path: String, dest: File) {
        val children = am.list(path).orEmpty()
        if (children.isEmpty()) {
            if (path.endsWith(".json") && path.substringAfterLast('/').startsWith("style-")) return // read straight from assets
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyTree(am, "$path/$c", File(dest, c))
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private companion object {
        const val MARKER = ".version"
        const val ASSET_VERSION = "2" // 2: sprites, glyphs and label layers (M0)
    }
}
