package com.qtekfun.mapas.core.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A region as activated on disk: version and the verified file of each asset. */
data class InstalledRegion(val id: String, val version: String, val files: Map<AssetKind, File>, val comapsId: String? = null)

/** `World.mwm` and `WorldCoasts.mwm` of one data [version], as installed under `root/.base/<version>/`. */
data class InstalledBase(val version: String, val world: File, val worldCoasts: File)

/**
 * Installs, updates and deletes regions under [root].
 *
 * Layout: `root/<id>/installed.json` (single source of truth), `root/<id>/<version>/<file>` (verified
 * assets) and `root/.partial/` (resumable downloads). Activation order: download each asset to `.partial`,
 * verify its SHA-256, atomically rename it into `<id>/<version>/`, then atomically replace `installed.json`.
 * Until that last rename the previous version stays fully usable; a crash at any point leaves either the
 * old or the new version active, never a mix. Orphans are swept by [cleanup].
 *
 * The core also needs `World.mwm` and `WorldCoasts.mwm` ([BaseMaps]); they are installed once per data version
 * under `root/.base/<version>/` (hidden, so they are never taken for a region) before the first region that
 * comes with them.
 */
class RegionManager(private val root: File, private val downloader: ResumableDownloader) {

    private val partialDir get() = File(root, ".partial")

    fun installed(): List<InstalledRegion> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .sortedBy { it.name }.mapNotNull { installedOrNull(it.name) }

    fun installed(id: String): InstalledRegion? = installedOrNull(id)

    /** Installed regions whose catalog version differs from the installed one. */
    fun updatesAvailable(catalog: RegionCatalog): List<Region> =
        installed().mapNotNull { i -> catalog[i.id]?.takeIf { it.isDownloadable && it.version != i.version } }

    /**
     * Installs or updates [region] (both downloads). Interrupted calls can simply be repeated: partial
     * files resume. Throws on network denial, hash mismatch, cancellation or I/O errors; the previously
     * installed version, if any, is untouched.
     */
    fun install(
        region: Region, cancel: CancelToken = CancelToken(), base: BaseMaps? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstalledRegion {
        require(region.isDownloadable) { "${region.id} is not downloadable" }
        val baseBytes = if (base != null && installedBase(base.version) == null) base.totalBytes else 0L
        val total = region.totalBytes + baseBytes
        var done = 0L
        if (base != null && baseBytes > 0) {
            installBase(base, cancel) { d, _ -> onProgress(d, total) }
            done += baseBytes
        }
        val dir = File(root, "${region.id}/${region.version}")
        for ((kind, a) in region.assets) {
            val part = File(partialDir, "${region.id}-${region.version}-${kind.key}.part")
            val base = done
            downloader.download(a.url, part, a.sizeBytes, a.sha256, cancel) { d, _ -> onProgress(base + d, total) }
            dir.mkdirs()
            Files.move(part.toPath(), File(dir, a.fileName).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            done += a.sizeBytes
        }
        val previous = installedOrNull(region.id)
        writeManifest(region)
        if (previous != null && previous.version != region.version) File(root, "${region.id}/${previous.version}").deleteRecursively()
        return installedOrNull(region.id)!!
    }

    /** The base maps of [version] if both files are present (the marker is written last). */
    fun installedBase(version: String): InstalledBase? = installedBases().firstOrNull { it.version == version }

    /** Every complete base, oldest data version first. */
    fun installedBases(): List<InstalledBase> =
        File(root, BASE_DIR).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
            val m = File(d, BASE_MARKER)
            if (!m.isFile) return@mapNotNull null
            try {
                val o = Json.parseToJsonElement(m.readText()).jsonObject
                val w = File(d, o.getValue("world").jsonPrimitive.content)
                val c = File(d, o.getValue("worldCoasts").jsonPrimitive.content)
                if (w.isFile && c.isFile) InstalledBase(d.name, w, c) else null
            } catch (e: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.version.length }, { it.version }))

    private fun installBase(base: BaseMaps, cancel: CancelToken, onProgress: (Long, Long) -> Unit) {
        val dir = File(root, "$BASE_DIR/${base.version}")
        var done = 0L
        for ((key, a) in listOf("world" to base.world, "worldCoasts" to base.worldCoasts)) {
            val part = File(partialDir, "base-${base.version}-$key.part")
            val start = done
            downloader.download(a.url, part, a.sizeBytes, a.sha256, cancel) { d, _ -> onProgress(start + d, base.totalBytes) }
            dir.mkdirs()
            Files.move(part.toPath(), File(dir, a.fileName).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            done += a.sizeBytes
        }
        val tmp = File(dir, "$BASE_MARKER.tmp")
        tmp.writeText(buildJsonObject { put("world", base.world.fileName); put("worldCoasts", base.worldCoasts.fileName) }.toString())
        Files.move(tmp.toPath(), File(dir, BASE_MARKER).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Fills in `comapsId` for regions installed before the catalog carried it (so the core link can be built).
     * Returns true if any manifest changed.
     */
    fun backfillComapsIds(catalog: RegionCatalog): Boolean {
        var changed = false
        for (i in installed()) {
            val r = catalog[i.id] ?: continue
            if (i.comapsId == null && r.comapsId != null && r.version == i.version) {
                writeManifest(r)
                changed = true
            }
        }
        return changed
    }

    /** Deletes every base map under this root. Only for when no region is installed on any storage. */
    fun removeBases() {
        File(root, BASE_DIR).deleteRecursively()
    }

    fun delete(id: String) {
        // Manifest first: from this instant the region is "not installed", even if we crash mid-delete.
        File(root, "$id/$MANIFEST").delete()
        File(root, id).deleteRecursively()
        partialDir.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    /**
     * Removes unreferenced version directories, orphan regions, partials not in [keepPartialsFor], base maps
     * older than the newest one (the newest also serves regions still on an older data version). Bases are kept
     * while a region might need them, even from another storage: see [removeBases].
     */
    fun cleanup(keepPartialsFor: Set<String> = emptySet()) {
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().forEach { d ->
            val inst = installedOrNull(d.name)
            if (inst == null) d.deleteRecursively()
            else d.listFiles { f -> f.isDirectory && f.name != inst.version }?.forEach { it.deleteRecursively() }
        }
        partialDir.listFiles { f -> keepPartialsFor.none { f.name.startsWith("$it-") } }?.forEach { it.delete() }
        val keep = installedBases().lastOrNull()?.version
        File(root, BASE_DIR).listFiles { f -> f.isDirectory && f.name != keep }?.forEach { it.deleteRecursively() }
    }

    private fun writeManifest(region: Region) {
        val dir = File(root, region.id).also { it.mkdirs() }
        val json = buildJsonObject {
            put("id", region.id)
            put("version", region.version)
            if (region.comapsId != null) put("comapsId", region.comapsId)
            put("files", buildJsonObject { region.assets.forEach { (k, a) -> put(k.key, a.fileName) } })
        }.toString()
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(json)
        Files.move(tmp.toPath(), File(dir, MANIFEST).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun installedOrNull(id: String): InstalledRegion? = try {
        val m = File(root, "$id/$MANIFEST")
        if (!m.isFile) {
            null
        } else {
            val o = Json.parseToJsonElement(m.readText()).jsonObject
            val version = o.getValue("version").jsonPrimitive.content
            val files = (o.getValue("files") as JsonObject).entries.associate { (k, v) ->
                AssetKind.entries.first { it.key == k } to File(root, "$id/$version/${v.jsonPrimitive.content}")
            }
            val comapsId = o["comapsId"]?.jsonPrimitive?.content
            if (files.values.all { it.isFile }) InstalledRegion(id, version, files, comapsId) else null
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MANIFEST = "installed.json"
        const val BASE_DIR = ".base"
        const val BASE_MARKER = "base.json"
    }
}
