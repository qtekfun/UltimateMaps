package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.regions.CancelToken
import com.qtekfun.ultimatemaps.core.regions.DownloadCancelledException
import com.qtekfun.ultimatemaps.core.regions.HashMismatchException
import com.qtekfun.ultimatemaps.core.regions.NetworkDeniedException
import com.qtekfun.ultimatemaps.core.regions.ResumableDownloader
import com.qtekfun.ultimatemaps.core.regions.TransitAsset
import com.qtekfun.ultimatemaps.core.regions.TransitBounds
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** Why a transit download did not end in an installed index. Stable: the UI maps each one to a message. */
enum class TransitFailure { OFFLINE_MODE, NOT_ALLOWED, NETWORK, INTEGRITY, INVALID_DATA, EXPIRED, CANCELLED }

/** What is known about an installed city index without opening it (written next to the file at install time). */
data class InstalledTransit(
    val id: String,
    val city: String,
    val timezone: String,
    val bounds: TransitBounds?,
    val validFrom: String,
    val validTo: String,
    val attribution: List<String>,
    val sha256: String,
)

/**
 * Keeps the per-city transit indexes in [dir] (`<id>.umti` plus `<id>.sha256`). Blocking: call off the main thread.
 *
 * The download is always user-initiated (the caller decides). Every connection goes through the [NetworkPolicy]
 * (`MAP_DOWNLOAD`, like the maps; offline mode denies it), over https, and the file must match the size and SHA-256 of
 * the catalog entry and parse as a transit index before it replaces the installed one. The request carries no position.
 * An index whose calendar already ended is refused: it could not plan anything.
 */
class TransitDataManager(
    private val dir: File,
    policy: NetworkPolicy,
    private val today: () -> LocalDate,
    allowInsecure: Boolean = false,
) {
    private val downloader = ResumableDownloader(policy, allowInsecure)

    private fun file(id: String) = File(dir, "$id.umti")
    private fun shaFile(id: String) = File(dir, "$id.sha256")

    /** SHA-256 of the installed index of [id], or null when none is installed. */
    fun installedSha(id: String): String? =
        if (file(id).isFile) runCatching { shaFile(id).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } else null

    private fun infoFile(id: String) = File(dir, "$id.properties")

    /** Every installed city index with the facts recorded at install time, sorted by city. */
    fun installed(): List<InstalledTransit> {
        val names = dir.list() ?: return emptyList()
        return names.filter { it.endsWith(".properties") }.mapNotNull { n ->
            val id = n.removeSuffix(".properties")
            if (!file(id).isFile) return@mapNotNull null
            runCatching {
                val p = java.util.Properties()
                infoFile(id).inputStream().use { p.load(it) }
                val b = p.getProperty("bounds")?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
                InstalledTransit(
                    id, p.getProperty("city") ?: id, p.getProperty("timezone") ?: "UTC",
                    b?.let { TransitBounds(it[0], it[1], it[2], it[3]) },
                    p.getProperty("validFrom").orEmpty(), p.getProperty("validTo").orEmpty(),
                    p.getProperty("attribution").orEmpty().split('\n').filter { it.isNotBlank() },
                    shaFile(id).takeIf { it.isFile }?.readText()?.trim().orEmpty(),
                )
            }.getOrNull()
        }.sortedBy { it.city }
    }

    fun isInstalled(id: String): Boolean = file(id).isFile

    /** Reads the installed index, or null when absent or unreadable (a damaged file is treated as absent). */
    fun open(id: String): TransitIndex? = try {
        if (file(id).isFile) file(id).inputStream().buffered().use { TransitIndexIo.read(it) } else null
    } catch (e: Exception) {
        null
    }

    fun delete(id: String) {
        file(id).delete()
        shaFile(id).delete()
        infoFile(id).delete()
        File(dir, "$id.umti.part").delete()
    }

    /** Downloads, verifies and installs [asset]. Returns null on success, otherwise why not. */
    fun download(asset: TransitAsset, cancel: CancelToken = CancelToken(), onProgress: (Long, Long) -> Unit = { _, _ -> }): TransitFailure? {
        val validTo = runCatching { LocalDate.parse(asset.validTo) }.getOrNull() ?: return TransitFailure.INVALID_DATA
        if (validTo.isBefore(today())) return TransitFailure.EXPIRED
        if (asset.asset.sizeBytes > MAX_BYTES) return TransitFailure.INVALID_DATA
        dir.mkdirs()
        val part = File(dir, "${asset.id}.umti.part")
        try {
            downloader.download(asset.asset.url, part, asset.asset.sizeBytes, asset.asset.sha256, cancel, onProgress)
        } catch (e: NetworkDeniedException) {
            return if (e.reason == DenyReason.OFFLINE_MODE) TransitFailure.OFFLINE_MODE else TransitFailure.NOT_ALLOWED
        } catch (e: HashMismatchException) {
            return TransitFailure.INTEGRITY
        } catch (e: DownloadCancelledException) {
            return TransitFailure.CANCELLED
        } catch (e: IOException) {
            // a size mismatch deletes the partial file; anything else keeps it for a later retry
            return if (e.message?.startsWith("size mismatch") == true) TransitFailure.INTEGRITY else TransitFailure.NETWORK
        }
        val index = try {
            part.inputStream().buffered().use { TransitIndexIo.read(it) }
        } catch (e: Exception) {
            part.delete()
            return TransitFailure.INVALID_DATA
        }
        val last = index.validity()?.lastDay
        if (last != null && last != Int.MAX_VALUE && LocalDate.ofEpochDay(last.toLong()).isBefore(today())) {
            part.delete()
            return TransitFailure.EXPIRED
        }
        val target = file(asset.id)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        shaFile(asset.id).writeText(asset.asset.sha256.lowercase())
        val p = java.util.Properties()
        p.setProperty("city", asset.city)
        p.setProperty("timezone", asset.timezone)
        asset.bounds?.let { p.setProperty("bounds", "${it.south},${it.west},${it.north},${it.east}") }
        p.setProperty("validFrom", asset.validFrom)
        p.setProperty("validTo", asset.validTo)
        p.setProperty("attribution", asset.attribution.joinToString("\n"))
        infoFile(asset.id).outputStream().use { p.store(it, null) }
        return null
    }

    companion object {
        /** Upper bound for one index (Madrid is a few MB). */
        const val MAX_BYTES = 64L shl 20
    }
}
