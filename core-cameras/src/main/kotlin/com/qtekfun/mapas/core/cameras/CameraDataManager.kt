package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.NetworkPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A catalog entry for the static camera file: where it is, how big, and its SHA-256 (lowercase hex). */
data class CameraAsset(val url: String, val sizeBytes: Long, val sha256: String)

/** The purpose under which the host of the static camera file is authorized: the same as the map downloads (same server as the catalog). */
val CAMERA_DATA_PURPOSE = ConnectionPurpose.MAP_DOWNLOAD

/** Read side for the map layer and the warner. */
class CameraDataRepository {
    @Volatile private var dataset = CameraDataset.EMPTY
    private val updated = MutableStateFlow<Long?>(null)

    /** Generation time of the data in the file (epoch millis), or null without data. Shown to the user. */
    val generatedMillis: StateFlow<Long?> get() = updated

    val data: CameraDataset get() = dataset

    fun install(d: CameraDataset?) {
        dataset = d ?: CameraDataset.EMPTY
        updated.value = d?.generatedAtEpochSeconds?.takeIf { it > 0 }?.times(1000)
    }

    fun fixedIn(bounds: LatLonBounds, limit: Int): List<SpeedCamera> =
        dataset.fixed.filterTo(ArrayList()) { bounds.contains(it.location) }.let { if (it.size > limit) it.subList(0, limit) else it }

    fun sectionsIn(bounds: LatLonBounds): List<SpeedCamera> =
        dataset.sections.filter { bounds.contains(it.location) || it.endLocation?.let(bounds::contains) == true }

    fun zonesIn(bounds: LatLonBounds): List<MobileZone> =
        dataset.zones.filter { z -> z.hasGeometry && z.line.any(bounds::contains) }

    fun camera(id: String): SpeedCamera? = dataset.fixed.firstOrNull { it.id == id } ?: dataset.sections.firstOrNull { it.id == id }

    fun zone(id: String): MobileZone? = dataset.zones.firstOrNull { it.id == id }

    /**
     * The announceable targets of the current data for the categories [settings] enables, rebuilt only when the data or
     * those switches change. Meant to be called from one thread (the warner's).
     */
    fun alertSource(settings: () -> CameraSettings): AlertSource = object : AlertSource {
        private var builtFor: Triple<CameraDataset, Boolean, Boolean>? = null
        private var grid = TargetGrid(emptyList())

        override fun forEachNear(lat: Double, lon: Double, radiusMeters: Double, visitor: AlertSource.Visitor) {
            val d = dataset
            val s = settings()
            val key = builtFor
            if (key == null || key.first !== d || key.second != s.fixedEnabled || key.third != s.mobileZonesEnabled) {
                grid = TargetGrid(CameraTargets.of(d, s))
                builtFor = Triple(d, s.fixedEnabled, s.mobileZonesEnabled)
            }
            grid.forEachNear(lat, lon, radiusMeters, visitor)
        }
    }
}

/** Why a refresh ran. Only [ENABLED], [USER] and [FOREGROUND] ever touch the network. */
enum class CameraTrigger { ENABLED, USER, FOREGROUND }

sealed interface CameraUpdateState {
    data object Idle : CameraUpdateState
    data object Running : CameraUpdateState
    data class Finished(val atMillis: Long, val failure: DownloadFailure?, val changed: Boolean) : CameraUpdateState
}

/**
 * Keeps the static camera file: settings, [NetworkPolicy], download, cache and the in-memory [repository].
 *
 * Network rules: nothing is downloaded when the manager is created or started ([start] reads only the local cache),
 * nothing while both camera switches are off, and a download happens only on [CameraTrigger.ENABLED] (a camera
 * switch was just turned on and there is no data), [CameraTrigger.USER] ("Update now") or [CameraTrigger.FOREGROUND]
 * when the catalog names a file different from the cached one. The file comes from the data server the user already
 * chose for maps (catalog field `cameras`); when the catalog has none, or the file is absent, the app simply works
 * without camera data. The request has no query and carries no position.
 */
class CameraDataManager(
    private val settingsStore: CameraSettingsStore,
    private val policy: NetworkPolicy,
    /** The catalog's camera file, or null when the catalog is not loaded or does not list one. */
    private val asset: () -> CameraAsset?,
    private val cacheDir: File,
    private val http: BoundedHttp = BoundedHttp(policy, CAMERA_DATA_PURPOSE, maxBytes = CameraFile.MAX_BYTES.toLong()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Brings the catalog up to date before the camera file is looked up (the catalog the app has cached can predate the
     * `cameras` block: without this the file would never be found until the user opened "Maps"). `force` is true when the
     * user just turned a switch on or pressed "Update now"; on a plain foreground it may do nothing. Failures are ignored:
     * the cached catalog is then used.
     */
    private val syncCatalog: suspend (force: Boolean) -> Unit = {},
) {
    val repository = CameraDataRepository()

    private val state = MutableStateFlow<CameraUpdateState>(CameraUpdateState.Idle)
    val updateState: StateFlow<CameraUpdateState> get() = state

    private val lock = Mutex()
    private val dataFile get() = File(cacheDir, DATA_NAME)
    private val shaFile get() = File(cacheDir, "$DATA_NAME.sha256")

    /** Loads the cached file (no network) and keeps the in-memory data in step with the switches. */
    fun start() {
        scope.launch {
            apply(settingsStore.settings.value)
            settingsStore.settings.drop(1).collect { apply(it) }
        }
    }

    private suspend fun apply(s: CameraSettings) = lock.withLock {
        withContext(io) {
            if (!s.anyCamera) {
                repository.install(null)
            } else if (repository.data === CameraDataset.EMPTY) {
                repository.install(readCache())
            }
        }
    }

    fun refreshAsync(trigger: CameraTrigger) {
        scope.launch { refresh(trigger) }
    }

    /** The app came to the foreground: refresh only when a camera switch is on, online, and the catalog names a newer file. */
    fun onForeground() {
        val s = settingsStore.settings.value
        if (!s.anyCamera || policy.offlineMode) return
        refreshAsync(CameraTrigger.FOREGROUND)
    }

    /** Returns the failure (null on success or when nothing needed doing). */
    suspend fun refresh(trigger: CameraTrigger): DownloadFailure? = lock.withLock {
        val s = settingsStore.settings.value
        if (!s.anyCamera) return null
        if (!policy.offlineMode) {
            try { syncCatalog(trigger != CameraTrigger.FOREGROUND) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* use the cached catalog */ }
        }
        val a = asset()
        val cachedSha = withContext(io) { runCatching { shaFile.readText().trim() }.getOrNull() }
        val haveData = repository.data !== CameraDataset.EMPTY
        if (a == null) {
            val failure = if (trigger == CameraTrigger.FOREGROUND) null else DownloadFailure.NO_CATALOG
            if (failure != null) state.value = CameraUpdateState.Finished(clock(), failure, false)
            return failure
        }
        val wanted = when (trigger) {
            CameraTrigger.USER -> true
            CameraTrigger.ENABLED -> !haveData || cachedSha != a.sha256
            CameraTrigger.FOREGROUND -> cachedSha != a.sha256
        }
        if (!wanted) return null
        state.value = CameraUpdateState.Running
        val failure = withContext(io) { download(a) }
        state.value = CameraUpdateState.Finished(clock(), failure, failure == null)
        failure
    }

    private fun download(a: CameraAsset): DownloadFailure? = try {
        if (a.sizeBytes <= 0 || a.sizeBytes > CameraFile.MAX_BYTES) throw DownloadException(DownloadFailure.TOO_LARGE, "bad size in catalog")
        val bytes = http.get(a.url, "application/octet-stream") { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(a.sha256, ignoreCase = true)) throw DownloadException(DownloadFailure.INVALID_DATA, "SHA-256 mismatch")
        val parsed = try { CameraFile.parse(bytes) } catch (e: CameraFileException) {
            throw DownloadException(DownloadFailure.INVALID_DATA, e.message ?: "invalid file", e)
        }
        writeCache(bytes, a.sha256.lowercase())
        repository.install(parsed)
        null
    } catch (e: DownloadException) {
        e.failure
    } catch (e: IOException) {
        DownloadFailure.NETWORK
    }

    private fun readCache(): CameraDataset? = try {
        val f = dataFile
        if (f.isFile && f.length() <= CameraFile.MAX_BYTES) CameraFile.parse(f.readBytes()) else null
    } catch (e: IOException) {
        null
    }

    private fun writeCache(bytes: ByteArray, sha: String) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$DATA_NAME.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        shaFile.writeText(sha)
    }

    private companion object {
        const val DATA_NAME = "speedcams-es.bin"
    }
}
