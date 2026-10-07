package com.qtekfun.mapas.core.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What one [CoreMapsLinker.sync] did. All paths are relative to the core directory (`<version>/<name>.mwm`). */
data class LinkReport(
    val created: List<String> = emptyList(),
    /** Links whose target changed (e.g. a region moved to the SD card). */
    val retargeted: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    /** Links that are hard links because a symbolic link could not be created. */
    val hardLinked: List<String> = emptyList(),
    /** Could not be linked (neither symbolic nor hard), or a real file of someone else is in the way. */
    val failed: List<String> = emptyList(),
    /**
     * Country maps (file names such as `Spain_La Rioja.mwm`) that were linked before and no longer are at all.
     * The native core registers a map for the whole process and cannot unregister it: until the app is
     * restarted it keeps answering with these, so the UI should ask for a restart when the core was loaded.
     */
    val droppedMaps: List<String> = emptyList(),
) {
    val changed: Boolean get() = created.isNotEmpty() || retargeted.isNotEmpty() || removed.isNotEmpty()
}

/**
 * Rebuilds `<coreDir>/<version>/` (the layout the CoMaps core demands: `<version>/<comapsId>.mwm` plus
 * `World.mwm` and `WorldCoasts.mwm` in the same directory) from what the region manager has installed,
 * without copying a byte: each entry is a link to the real file.
 *
 * Symbolic links are used first: they work across file systems (the SD card is another one, where a hard
 * link is impossible), they are cheap, and the core reads through `stat`/`fopen`, which follow them (checked
 * in the CoMaps source, not yet on a device). Hard links are the fallback when the file system refuses
 * symbolic links; they cannot cross volumes, so a region on a card without symlink support is reported in
 * [LinkReport.failed]. Real files that are not ours (e.g. pushed by hand while developing) are never touched.
 *
 * [sync] is idempotent: a second call with the same input changes nothing. Ownership is recorded in
 * `<coreDir>/.links.json`, which is how orphaned hard links are recognised; symbolic links are recognised
 * by being symbolic.
 */
class CoreMapsLinker(private val coreDir: File) {

    fun sync(regions: List<InstalledRegion>, bases: List<InstalledBase>): LinkReport {
        val desired = desired(regions, bases)
        val previous = readOwned()
        val created = mutableListOf<String>()
        val retargeted = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val hard = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val owned = LinkedHashSet<String>()
        coreDir.mkdirs()

        for ((rel, target) in desired) {
            val link = File(coreDir, rel).toPath()
            val kind = existing(link)
            when {
                kind == Existing.NONE -> Unit
                kind == Existing.REAL && rel !in previous -> { failed += rel; continue } // someone else's file
                points(link, target, kind) -> { owned += rel; if (isHard(link, target)) hard += rel; continue }
                else -> { Files.delete(link); retargeted += rel }
            }
            link.parent.toFile().mkdirs()
            when (makeLink(link, target.toPath())) {
                Made.SYMBOLIC -> owned += rel
                Made.HARD -> { owned += rel; hard += rel }
                Made.FAILED -> { failed += rel; retargeted -= rel; continue }
            }
            if (rel !in retargeted) created += rel
        }

        // Orphans: every symbolic link we do not want (dangling or not), and hard links we created earlier.
        coreDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().forEach { dir ->
            dir.listFiles().orEmpty().forEach { f ->
                val rel = "${dir.name}/${f.name}"
                if (rel in desired) return@forEach
                val kind = existing(f.toPath())
                if (kind == Existing.SYMLINK || (kind == Existing.REAL && rel in previous)) {
                    Files.delete(f.toPath())
                    removed += rel
                }
            }
            if (dir.list().isNullOrEmpty()) dir.delete()
        }

        val nowNames = desired.keys.map { it.substringAfter('/') }.toSet()
        val dropped = (previous.map { it.substringAfter('/') }.toSet() - nowNames - GLOBAL).sorted()
        writeOwned(owned)
        return LinkReport(created, retargeted, removed, hard.distinct(), failed, dropped)
    }

    /** `<version>/<name>.mwm` -> real file. Each data version of an installed region gets its own `World*.mwm`. */
    private fun desired(regions: List<InstalledRegion>, bases: List<InstalledBase>): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val newestBase = bases.maxWithOrNull(compareBy({ it.version.length }, { it.version }))
        for (r in regions.sortedBy { it.id }) {
            val name = r.comapsId ?: continue
            val mwm = r.files[AssetKind.SEARCH] ?: continue
            if (!safeName(name) || !safeName(r.version)) continue
            out.putIfAbsent("${r.version}/$name.mwm", mwm)
        }
        for (version in out.keys.map { it.substringBefore('/') }.distinct()) {
            val base = bases.firstOrNull { it.version == version } ?: newestBase ?: continue
            out["$version/World.mwm"] = base.world
            out["$version/WorldCoasts.mwm"] = base.worldCoasts
        }
        return out
    }

    private enum class Existing { NONE, SYMLINK, REAL }
    private enum class Made { SYMBOLIC, HARD, FAILED }

    private fun existing(p: Path) = when {
        Files.isSymbolicLink(p) -> Existing.SYMLINK
        Files.exists(p, LinkOption.NOFOLLOW_LINKS) -> Existing.REAL
        else -> Existing.NONE
    }

    private fun points(link: Path, target: File, kind: Existing): Boolean = try {
        if (kind == Existing.SYMLINK) Files.readSymbolicLink(link) == target.absoluteFile.toPath()
        else Files.isSameFile(link, target.toPath())
    } catch (e: IOException) {
        false
    }

    private fun isHard(link: Path, target: File) =
        !Files.isSymbolicLink(link) && runCatching { Files.isSameFile(link, target.toPath()) }.getOrDefault(false)

    private fun makeLink(link: Path, target: Path): Made {
        val abs = target.toAbsolutePath()
        try {
            Files.createSymbolicLink(link, abs)
            return Made.SYMBOLIC
        } catch (e: IOException) {
            // fall through to a hard link
        } catch (e: UnsupportedOperationException) {
        }
        return try {
            Files.createLink(link, abs)
            Made.HARD
        } catch (e: IOException) {
            Made.FAILED
        } catch (e: UnsupportedOperationException) {
            Made.FAILED
        }
    }

    private val ledger get() = File(coreDir, LEDGER)

    private fun readOwned(): Set<String> = try {
        if (ledger.isFile) Json.parseToJsonElement(ledger.readText()).jsonObject.getValue("links").jsonArray.map { it.jsonPrimitive.content }.toSet()
        else emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun writeOwned(owned: Set<String>) {
        val tmp = File(coreDir, "$LEDGER.tmp")
        tmp.writeText(buildJsonObject { put("links", buildJsonArray { owned.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }.toString())
        Files.move(tmp.toPath(), ledger.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun safeName(s: String) = s.isNotEmpty() && '/' !in s && '\\' !in s && s != "." && s != ".." && s.none { it.isISOControl() }

    private companion object {
        const val LEDGER = ".links.json"
        val GLOBAL = setOf("World.mwm", "WorldCoasts.mwm")
    }
}
