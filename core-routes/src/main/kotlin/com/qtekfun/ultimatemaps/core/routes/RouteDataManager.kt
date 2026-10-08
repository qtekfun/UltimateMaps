package com.qtekfun.ultimatemaps.core.routes

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.cameras.CAMERA_DATA_PURPOSE
import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
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

/** A catalog entry for the static route file: where it is, how big, and its SHA-256 (lowercase hex). */
data class RouteAsset(val url: String, val sizeBytes: Long, val sha256: String)

/** Why a refresh ran. Only [ENABLED], [USER] and [FOREGROUND] ever touch the network. */
enum class RouteTrigger { ENABLED, USER, FOREGROUND }

sealed interface RouteUpdateState {
    data object Idle : RouteUpdateState
    data object Running : RouteUpdateState
    data class Finished(val atMillis: Long, val failure: DownloadFailure?, val changed: Boolean) : RouteUpdateState
}

/**
 * Keeps the static route file: settings, [NetworkPolicy], download, cache and the in-memory [repository]. Same rules as
 * the camera file (`CameraDataManager`): nothing is downloaded when the manager is created or started ([start] reads only
 * the local cache), nothing while the switch is off, and a download happens only on [RouteTrigger.ENABLED] (the switch
 * was just turned on and there is no data), [RouteTrigger.USER] ("Update now") or [RouteTrigger.FOREGROUND] when the
 * catalog names a file different from a cached one that is over 30 days old (the file is several MB). The file comes from the data server the user already chose for maps
 * (catalog field `routes`); when the catalog has none, or the file is absent, the app simply works without route data.
 * The request has no query and carries no position.
 */
class RouteDataManager(
    private val settingsStore: RouteSettingsStore,
    private val policy: NetworkPolicy,
    /** The catalog's route file, or null when the catalog is not loaded or does not list one. */
    private val asset: () -> RouteAsset?,
    private val cacheDir: File,
    private val http: BoundedHttp = BoundedHttp(policy, CAMERA_DATA_PURPOSE, maxBytes = RouteFile.MAX_BYTES.toLong()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Brings the catalog up to date before the file is looked up (see `CameraDataManager`). Failures are ignored. */
    private val syncCatalog: suspend (force: Boolean) -> Unit = {},
) {
    val repository = RouteRepository()

    private val state = MutableStateFlow<RouteUpdateState>(RouteUpdateState.Idle)
    val updateState: StateFlow<RouteUpdateState> get() = state

    private val lock = Mutex()
    private val dataFile get() = File(cacheDir, DATA_NAME)
    private val shaFile get() = File(cacheDir, "$DATA_NAME.sha256")

    /** Loads the cached file (no network) and keeps the in-memory data in step with the switch. */
    fun start() {
        scope.launch {
            apply(settingsStore.settings.value)
            settingsStore.settings.drop(1).collect { apply(it) }
        }
    }

    private suspend fun apply(s: RouteSettings) = lock.withLock {
        withContext(io) {
            if (!s.enabled) {
                repository.install(null)
            } else if (repository.data === TrailDataset.EMPTY) {
                repository.install(readCache())
            }
        }
    }

    fun refreshAsync(trigger: RouteTrigger) {
        scope.launch { refresh(trigger) }
    }

    /** The app came to the foreground: refresh only when the switch is on, online, and the catalog names a newer file. */
    fun onForeground() {
        if (!settingsStore.settings.value.enabled || policy.offlineMode) return
        refreshAsync(RouteTrigger.FOREGROUND)
    }

    /** Returns the failure (null on success or when nothing needed doing). */
    suspend fun refresh(trigger: RouteTrigger): DownloadFailure? = lock.withLock {
        if (!settingsStore.settings.value.enabled) return null
        if (!policy.offlineMode) {
            try { syncCatalog(trigger != RouteTrigger.FOREGROUND) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* use the cached catalog */ }
        }
        val a = asset()
        val cachedSha = withContext(io) { runCatching { shaFile.readText().trim() }.getOrNull() }
        val haveData = repository.data !== TrailDataset.EMPTY
        if (a == null) {
            val failure = if (trigger == RouteTrigger.FOREGROUND) null else DownloadFailure.NO_CATALOG
            if (failure != null) state.value = RouteUpdateState.Finished(clock(), failure, false)
            return failure
        }
        val wanted = when (trigger) {
            RouteTrigger.USER -> true
            RouteTrigger.ENABLED -> !haveData || cachedSha != a.sha256
            RouteTrigger.FOREGROUND -> cachedSha != a.sha256 && cacheAgeMillis() > FOREGROUND_MIN_AGE_MS
        }
        if (!wanted) return null
        state.value = RouteUpdateState.Running
        val failure = withContext(io) { download(a) }
        state.value = RouteUpdateState.Finished(clock(), failure, failure == null)
        failure
    }

    /** Age of the cached file by its checksum file's time; a huge number without a cache. */
    private fun cacheAgeMillis(): Long {
        val t = runCatching { shaFile.lastModified() }.getOrDefault(0L)
        return if (t <= 0L) Long.MAX_VALUE else clock() - t
    }

    private fun download(a: RouteAsset): DownloadFailure? = try {
        if (a.sizeBytes <= 0 || a.sizeBytes > RouteFile.MAX_BYTES) throw DownloadException(DownloadFailure.TOO_LARGE, "bad size in catalog")
        val bytes = http.get(a.url, "application/octet-stream") { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(a.sha256, ignoreCase = true)) throw DownloadException(DownloadFailure.INVALID_DATA, "SHA-256 mismatch")
        val parsed = try { RouteFile.parse(bytes) } catch (e: RouteFileException) {
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

    private fun readCache(): TrailDataset? = try {
        val f = dataFile
        if (f.isFile && f.length() <= RouteFile.MAX_BYTES) RouteFile.parse(f.readBytes()) else null
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

    companion object {
        const val DATA_NAME = "routes-es.bin"

        /** The file is several MB: a newer one in the catalog is fetched in the background only when the cached one is this old. */
        const val FOREGROUND_MIN_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
