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
data class InstalledRegion(val id: String, val version: String, val files: Map<AssetKind, File>)

/**
 * Installs, updates and deletes regions under [root].
 *
 * Layout: `root/<id>/installed.json` (single source of truth), `root/<id>/<version>/<file>` (verified
 * assets) and `root/.partial/` (resumable downloads). Activation order: download each asset to `.partial`,
 * verify its SHA-256, atomically rename it into `<id>/<version>/`, then atomically replace `installed.json`.
 * Until that last rename the previous version stays fully usable; a crash at any point leaves either the
 * old or the new version active, never a mix. Orphans are swept by [cleanup].
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
        region: Region, cancel: CancelToken = CancelToken(), onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstalledRegion {
        require(region.isDownloadable) { "${region.id} is not downloadable" }
        val total = region.totalBytes
        var done = 0L
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

    fun delete(id: String) {
        // Manifest first: from this instant the region is "not installed", even if we crash mid-delete.
        File(root, "$id/$MANIFEST").delete()
        File(root, id).deleteRecursively()
        partialDir.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    /** Removes unreferenced version directories, orphan regions and partials not in [keepPartialsFor]. */
    fun cleanup(keepPartialsFor: Set<String> = emptySet()) {
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().forEach { d ->
            val inst = installedOrNull(d.name)
            if (inst == null) d.deleteRecursively()
            else d.listFiles { f -> f.isDirectory && f.name != inst.version }?.forEach { it.deleteRecursively() }
        }
        partialDir.listFiles { f -> keepPartialsFor.none { f.name.startsWith("$it-") } }?.forEach { it.delete() }
    }

    private fun writeManifest(region: Region) {
        val dir = File(root, region.id).also { it.mkdirs() }
        val json = buildJsonObject {
            put("id", region.id)
            put("version", region.version)
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
            if (files.values.all { it.isFile }) InstalledRegion(id, version, files) else null
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MANIFEST = "installed.json"
    }
}
