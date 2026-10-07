package com.qtekfun.mapas.regions

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DenyReason
import com.qtekfun.mapas.core.net.NetworkPolicy
import com.qtekfun.mapas.core.regions.CancelToken
import com.qtekfun.mapas.core.regions.CatalogException
import com.qtekfun.mapas.core.regions.CatalogFetcher
import com.qtekfun.mapas.core.regions.InstalledRegion
import com.qtekfun.mapas.core.regions.NetworkDeniedException
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.core.regions.RegionManager
import com.qtekfun.mapas.core.regions.ResumableDownloader
import com.qtekfun.mapas.core.regions.StorageLocation
import com.qtekfun.mapas.core.regions.StorageSelector
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class CatalogError { OFFLINE_MODE, NOT_ALLOWED, NETWORK, INVALID }

sealed interface CatalogState {
    /** No catalog server configured: nothing is ever contacted. */
    data object NoServer : CatalogState
    data object Loading : CatalogState

    /** [stale]: the list is the cached one because refreshing it failed with [refreshError]. */
    data class Loaded(val catalog: RegionCatalog, val stale: Boolean = false, val refreshError: CatalogError? = null) : CatalogState
    data class Failed(val error: CatalogError) : CatalogState
}

/** An installed region with where it lives and how much room it takes. */
data class InstalledEntry(val region: InstalledRegion, val locationId: String, val bytes: Long)

/** Free and total space of a storage, for the screen header. */
data class StorageInfo(val location: StorageLocation, val freeBytes: Long, val totalBytes: Long)

/**
 * Everything the "Maps" screen and the download service share. Observable state is Compose snapshot state
 * (safe to write from worker threads). The only network code paths are [refreshCatalog] and the downloads,
 * and both go through [policy] (`MAP_DOWNLOAD`): with offline mode on, no connection is even attempted.
 */
class RegionsController(
    private val context: Context,
    private val policy: NetworkPolicy,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val allowInsecure: Boolean = false, // tests only (local http server)
    downloadExecutor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mapas-regions-dl") },
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mapas-regions-io") },
    private val startService: (Context) -> Unit = { startForegroundDownloads(it) },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val downloader = ResumableDownloader(policy, allowInsecure)
    private val fetcher = CatalogFetcher(policy, allowInsecure)
    private val cacheFile get() = File(context.filesDir, CACHE_NAME)

    var catalogState: CatalogState by mutableStateOf(CatalogState.NoServer); private set
    var downloadStates: Map<String, DownloadState> by mutableStateOf(emptyMap()); private set
    var installed: List<InstalledEntry> by mutableStateOf(emptyList()); private set
    var locations: List<StorageLocation> by mutableStateOf(emptyList()); private set
    var selectedLocationId: String by mutableStateOf(RegionStorage.INTERNAL_ID); private set
    var storage: List<StorageInfo> by mutableStateOf(emptyList()); private set
    var offline: Boolean by mutableStateOf(false); private set
    var serverUrl: String by mutableStateOf(""); private set

    private val downloads: RegionDownloads = RegionDownloads(
        installer = ::installRegion,
        executor = downloadExecutor,
        onChange = {
            downloadStates = this.downloadsSnapshot()
            listeners.forEach { it() }
        },
        onInstalled = { refreshInstalled() },
    )

    private fun downloadsSnapshot(): Map<String, DownloadState> = downloads.states()

    val isDownloading: Boolean get() = downloads.isActive

    /** Loads preferences and applies them to the policy. Call once from `Application.onCreate`. */
    fun restore() {
        serverUrl = prefs.getString(KEY_URL, "").orEmpty()
        offline = prefs.getBoolean(KEY_OFFLINE, false)
        policy.offlineMode = offline
        selectedLocationId = prefs.getString(KEY_LOCATION, RegionStorage.INTERNAL_ID) ?: RegionStorage.INTERNAL_ID
        parsedHost(serverUrl)?.let { addEndpoint(AllowedEndpoint(it, ConnectionPurpose.MAP_DOWNLOAD, enabled = true)) }
        refreshStorage()
        refreshInstalled()
        loadCatalogFromCache()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // --- Settings ---

    fun setOfflineMode(value: Boolean) {
        offline = value
        policy.offlineMode = value
        prefs.edit().putBoolean(KEY_OFFLINE, value).apply()
        if (value) downloads.pauseAll()
    }

    /** Saves the catalog URL (https only) and whitelists its host for map downloads. Returns false if it is not a valid URL. */
    fun saveServerUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            serverUrl = ""
            prefs.edit().remove(KEY_URL).apply()
            catalogState = if (cachedCatalog() != null) catalogState else CatalogState.NoServer
            return true
        }
        val host = parsedHost(trimmed) ?: return false
        addEndpoint(AllowedEndpoint(host, ConnectionPurpose.MAP_DOWNLOAD, enabled = true))
        serverUrl = trimmed
        prefs.edit().putString(KEY_URL, trimmed).apply()
        return true
    }

    fun selectLocation(id: String) {
        if (locations.none { it.id == id }) return
        selectedLocationId = id
        prefs.edit().putString(KEY_LOCATION, id).apply()
    }

    // --- Catalog ---

    /** Downloads the catalog in the background (one small JSON). No-op without a server; denied when offline. */
    fun refreshCatalog() {
        val url = serverUrl
        if (url.isEmpty()) {
            catalogState = cachedCatalog()?.let { CatalogState.Loaded(it, stale = true) } ?: CatalogState.NoServer
            return
        }
        val before = catalogState
        if (before !is CatalogState.Loaded) catalogState = CatalogState.Loading
        ioExecutor.execute {
            try {
                val (text, catalog) = fetcher.fetch(url)
                cacheFile.writeText(text)
                whitelistAssetHosts(catalog)
                catalogState = CatalogState.Loaded(catalog)
            } catch (e: NetworkDeniedException) {
                failRefresh(if (e.reason == DenyReason.OFFLINE_MODE) CatalogError.OFFLINE_MODE else CatalogError.NOT_ALLOWED)
            } catch (e: CatalogException) {
                failRefresh(CatalogError.INVALID)
            } catch (e: Exception) {
                failRefresh(CatalogError.NETWORK)
            }
        }
    }

    private fun failRefresh(error: CatalogError) {
        val cached = cachedCatalog()
        catalogState = if (cached != null) CatalogState.Loaded(cached, stale = true, refreshError = error) else CatalogState.Failed(error)
    }

    private fun cachedCatalog(): RegionCatalog? = try {
        if (cacheFile.isFile) RegionCatalog.parse(cacheFile.readText(), serverUrl.ifEmpty { null }) else null
    } catch (e: Exception) {
        null
    }

    private fun loadCatalogFromCache() {
        cachedCatalog()?.let {
            whitelistAssetHosts(it)
            catalogState = CatalogState.Loaded(it, stale = true)
        }
    }

    /** The catalog of a server the user configured decides where its files are hosted; each host is listed as a possible connection. */
    private fun whitelistAssetHosts(catalog: RegionCatalog) {
        catalog.regions.flatMap { it.assets.values }.mapNotNull { parsedHost(it.url, requireHttps = !allowInsecure) }.toSet().forEach {
            addEndpoint(AllowedEndpoint(it, ConnectionPurpose.MAP_DOWNLOAD, enabled = true))
        }
    }

    // --- Downloads ---

    fun download(region: Region) {
        if (!region.isDownloadable) return
        downloads.enqueue(region)
        runCatching { startService(context) }
    }

    fun pause(id: String) = downloads.pause(id)

    fun pauseAll() = downloads.pauseAll()

    fun resume(region: Region) = download(region)

    /** Stops a download for good and discards its partial files. */
    fun cancel(id: String) {
        downloads.cancel(id)
        ioExecutor.execute { locations.forEach { deletePartials(it.dir, id) } }
    }

    fun dismissFailure(id: String) = downloads.clear(id)

    /** Deletes an installed region and its partials. */
    fun delete(id: String) {
        downloads.cancel(id)
        ioExecutor.execute {
            val entry = installed.firstOrNull { it.region.id == id }
            (entry?.let { e -> locations.filter { it.id == e.locationId } } ?: locations).forEach { loc ->
                managerFor(loc).delete(id)
            }
            refreshInstalled()
            refreshStorage()
        }
    }

    // --- Installed and storage ---

    fun refreshInstalled() {
        val locs = locations.ifEmpty { RegionStorage.locations(context) }
        installed = RegionStorage.installedEverywhere(locs).map { (loc, r) ->
            InstalledEntry(r, loc.id, r.files.values.sumOf { it.length() })
        }
        refreshStorage()
    }

    fun refreshStorage() {
        val locs = RegionStorage.locations(
            context,
            internalLabel = context.getString(R.string.storage_internal),
            cardLabel = context.getString(R.string.storage_card),
        )
        locations = locs
        if (locs.none { it.id == selectedLocationId }) selectedLocationId = RegionStorage.INTERNAL_ID
        storage = locs.map { l ->
            l.dir.mkdirs()
            StorageInfo(l, l.dir.usableSpace, l.dir.totalSpace)
        }
    }

    /** Installed region ids with their versions, for [RegionsModel.rows]. */
    fun installedVersions(): Map<String, String> = installed.associate { it.region.id to it.region.version }

    // --- Worker side ---

    private fun managerFor(loc: StorageLocation) = RegionManager(loc.dir, downloader)

    private fun installRegion(region: Region, cancel: CancelToken, onProgress: (Long, Long) -> Unit) {
        val locs = RegionStorage.locations(context)
        val existing = installed.firstOrNull { it.region.id == region.id }?.locationId // updates stay where they are
        val preferred = existing ?: selectedLocationId
        val already = partialBytes(locs.firstOrNull { it.id == preferred }?.dir, region.id)
        val needed = (region.totalBytes - already).coerceAtLeast(0)
        val target = StorageSelector.choose(locs, preferred, needed)
        if (target == null) {
            val free = locs.firstOrNull { it.id == preferred }?.dir?.usableSpace ?: 0L
            throw InsufficientSpaceException(needed, free)
        }
        managerFor(target).install(region, cancel, onProgress)
        managerFor(target).cleanup(keepPartialsFor = downloads.states().keys)
    }

    private fun partialBytes(root: File?, id: String): Long =
        File(root ?: return 0L, ".partial").listFiles { f -> f.name.startsWith("$id-") }.orEmpty().sumOf { it.length() }

    private fun deletePartials(root: File, id: String) {
        File(root, ".partial").listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    private fun parsedHost(url: String, requireHttps: Boolean = !allowInsecure): String? = try {
        val u = URI.create(url.trim())
        val ok = u.scheme == "https" || (!requireHttps && u.scheme == "http")
        u.host?.lowercase()?.takeIf { ok && it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    companion object {
        internal const val PREFS = "regions"
        private const val KEY_URL = "catalog_url"
        internal const val KEY_OFFLINE = "offline_mode"
        private const val KEY_LOCATION = "install_location"
        private const val CACHE_NAME = "regions-catalog.json"

        fun startForegroundDownloads(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RegionDownloadService::class.java))
        }
    }
}
