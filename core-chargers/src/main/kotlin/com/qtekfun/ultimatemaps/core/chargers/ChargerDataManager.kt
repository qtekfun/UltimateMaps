package com.qtekfun.ultimatemaps.core.chargers

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

/** A catalog entry for the static charger file: where it is, how big, and its SHA-256 (lowercase hex). */
data class ChargerAsset(val url: String, val sizeBytes: Long, val sha256: String)

/** Why a refresh ran. Only [ENABLED], [USER] and [FOREGROUND] ever touch the network. */
enum class ChargerTrigger { ENABLED, USER, FOREGROUND }

sealed interface ChargerUpdateState {
    data object Idle : ChargerUpdateState
    data object Running : ChargerUpdateState
    data class Finished(val atMillis: Long, val failure: DownloadFailure?, val changed: Boolean) : ChargerUpdateState
}

/**
 * Keeps the static charger file: settings, [NetworkPolicy], download, cache and the in-memory [repository]. Same rules as
 * the camera file (`CameraDataManager`): nothing is downloaded when the manager is created or started ([start] reads only
 * the local cache), nothing while the switch is off, and a download happens only on [ChargerTrigger.ENABLED] (the switch
 * was just turned on and there is no data), [ChargerTrigger.USER] ("Update now") or [ChargerTrigger.FOREGROUND] when the
 * catalog names a file different from the cached one. The file comes from the data server the user already chose for maps
 * (catalog field `chargers`); when the catalog has none, or the file is absent, the app simply works without charger data.
 * The request has no query and carries no position.
 */
class ChargerDataManager(
    private val settingsStore: ChargerSettingsStore,
    private val policy: NetworkPolicy,
    /** The catalog's charger file, or null when the catalog is not loaded or does not list one. */
    private val asset: () -> ChargerAsset?,
    private val cacheDir: File,
    private val http: BoundedHttp = BoundedHttp(policy, CAMERA_DATA_PURPOSE, maxBytes = ChargerFile.MAX_BYTES.toLong()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Brings the catalog up to date before the file is looked up (see `CameraDataManager`). Failures are ignored. */
    private val syncCatalog: suspend (force: Boolean) -> Unit = {},
) {
    val repository = ChargerRepository()

    private val state = MutableStateFlow<ChargerUpdateState>(ChargerUpdateState.Idle)
    val updateState: StateFlow<ChargerUpdateState> get() = state

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

    private suspend fun apply(s: ChargerSettings) = lock.withLock {
        withContext(io) {
            if (!s.enabled) {
                repository.install(null)
            } else if (repository.data === ChargerDataset.EMPTY) {
                repository.install(readCache())
            }
        }
    }

    fun refreshAsync(trigger: ChargerTrigger) {
        scope.launch { refresh(trigger) }
    }

    /** The app came to the foreground: refresh only when the switch is on, online, and the catalog names a newer file. */
    fun onForeground() {
        if (!settingsStore.settings.value.enabled || policy.offlineMode) return
        refreshAsync(ChargerTrigger.FOREGROUND)
    }

    /** Returns the failure (null on success or when nothing needed doing). */
    suspend fun refresh(trigger: ChargerTrigger): DownloadFailure? = lock.withLock {
        if (!settingsStore.settings.value.enabled) return null
        if (!policy.offlineMode) {
            try { syncCatalog(trigger != ChargerTrigger.FOREGROUND) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* use the cached catalog */ }
        }
        val a = asset()
        val cachedSha = withContext(io) { runCatching { shaFile.readText().trim() }.getOrNull() }
        val haveData = repository.data !== ChargerDataset.EMPTY
        if (a == null) {
            val failure = if (trigger == ChargerTrigger.FOREGROUND) null else DownloadFailure.NO_CATALOG
            if (failure != null) state.value = ChargerUpdateState.Finished(clock(), failure, false)
            return failure
        }
        val wanted = when (trigger) {
            ChargerTrigger.USER -> true
            ChargerTrigger.ENABLED -> !haveData || cachedSha != a.sha256
            ChargerTrigger.FOREGROUND -> cachedSha != a.sha256
        }
        if (!wanted) return null
        state.value = ChargerUpdateState.Running
        val failure = withContext(io) { download(a) }
        state.value = ChargerUpdateState.Finished(clock(), failure, failure == null)
        failure
    }

    private fun download(a: ChargerAsset): DownloadFailure? = try {
        if (a.sizeBytes <= 0 || a.sizeBytes > ChargerFile.MAX_BYTES) throw DownloadException(DownloadFailure.TOO_LARGE, "bad size in catalog")
        val bytes = http.get(a.url, "application/octet-stream") { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(a.sha256, ignoreCase = true)) throw DownloadException(DownloadFailure.INVALID_DATA, "SHA-256 mismatch")
        val parsed = try { ChargerFile.parse(bytes) } catch (e: ChargerFileException) {
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

    private fun readCache(): ChargerDataset? = try {
        val f = dataFile
        if (f.isFile && f.length() <= ChargerFile.MAX_BYTES) ChargerFile.parse(f.readBytes()) else null
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
        const val DATA_NAME = "chargers-es.bin"
    }
}
