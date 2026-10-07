package com.qtekfun.mapas.settings.backup

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.mapas.core.data.BackupService
import com.qtekfun.mapas.core.data.ImportResult
import com.qtekfun.mapas.core.data.RestoreMode
import com.qtekfun.mapas.core.data.SqlitePlacesRepository
import com.qtekfun.mapas.settings.BackupOutcome
import com.qtekfun.mapas.settings.BackupUiState
import com.qtekfun.mapas.settings.ImportPreview
import java.io.InputStream
import java.io.OutputStream

/** The places half of the combined file. Blocking: called off the main thread. */
interface PlacesBackupAccess {
    /** Writes the places backup ZIP with [extraEntries] added. */
    fun write(out: OutputStream, extraEntries: Map<String, ByteArray>)

    /** Adds what is missing from the places backup in [input] (nothing is deleted). */
    fun restore(input: InputStream): ImportResult
}

/** [PlacesBackupAccess] over the on-device database, through `BackupService`. */
class AndroidPlacesBackup(private val context: Context) : PlacesBackupAccess {
    private fun <T> withService(block: (BackupService) -> T): T {
        val file = context.applicationContext.getDatabasePath("places.db")
        file.parentFile?.mkdirs()
        val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), file.path)
        try {
            return block(BackupService(repo))
        } finally {
            repo.close()
        }
    }

    override fun write(out: OutputStream, extraEntries: Map<String, ByteArray>) = withService { it.write(out, extraEntries) }

    override fun restore(input: InputStream): ImportResult = withService { it.restore(input, RestoreMode.MERGE) }
}

/**
 * Runs the export and import flows behind the Settings section and keeps [state] up to date. File access and parsing
 * happen through [background]; every change of [state] and every write of settings goes through [foreground] (the
 * main thread), so a restore never races with the screen. Both are injected so tests run everything in line.
 * Nothing here touches the network.
 */
class SettingsBackupCoordinator(
    val state: BackupUiState,
    private val storage: SettingsStorage,
    private val pending: PendingRestore,
    private val installedRegions: () -> Collection<String>,
    private val appVersion: String,
    private val nowMillis: () -> Long,
    private val places: PlacesBackupAccess?,
    private val background: (() -> Unit) -> Unit,
    private val foreground: (() -> Unit) -> Unit,
) {
    private var loaded: Pair<SettingsFileContent, () -> InputStream>? = null

    /** The settings file text for right now. */
    private fun settingsText(): String =
        SettingsBackup.export(storage, installedRegions(), appVersion, nowMillis(), pending.consent)

    /** Writes the settings JSON to the stream [open] returns. */
    fun exportSettings(open: () -> OutputStream) = run(everything = false) {
        open().use { it.write(settingsText().toByteArray(Charsets.UTF_8)) }
    }

    /** Writes places and settings as one ZIP (the places backup with the settings added). */
    fun exportEverything(open: () -> OutputStream) = run(everything = true) {
        val access = checkNotNull(places) { "no places backup available" }
        val extra = mapOf(SettingsFile.ENTRY to settingsText().toByteArray(Charsets.UTF_8))
        open().use { access.write(it, extra) }
    }

    private fun run(everything: Boolean, work: () -> Unit) {
        state.busy = true
        background {
            val outcome = runCatching(work).fold({ BackupOutcome.Exported(everything) }, { BackupOutcome.Failed })
            foreground { state.outcome = outcome; state.busy = false }
        }
    }

    /**
     * Reads a picked file and shows what it would change. Nothing is written yet. [open] may be called again by
     * [confirm] (for the places part of a combined file).
     */
    fun load(open: () -> InputStream) {
        state.busy = true
        background {
            val result = runCatching {
                val content = open().use(SettingsFile::read)
                content to content.snapshot?.let { SettingsBackup.plan(it, storage) }
            }
            foreground {
                state.busy = false
                result.fold(
                    onSuccess = { (content, plan) ->
                        loaded = content to open
                        state.outcome = null
                        state.preview = ImportPreview(plan, content.hasPlaces && places != null)
                    },
                    onFailure = { e ->
                        loaded = null
                        state.outcome = if (e is SettingsBackupException) BackupOutcome.Rejected(e.reason, e.fileSchema) else BackupOutcome.Failed
                    },
                )
            }
        }
    }

    fun cancel() {
        loaded = null
        state.preview = null
    }

    /** Applies what [load] read. Call from the main thread. The places part runs first: if it fails nothing changed. */
    fun confirm() {
        val (content, open) = loaded ?: return
        loaded = null
        state.preview = null
        state.busy = true
        val access = places
        background {
            val placesResult: Result<ImportResult>? =
                if (content.hasPlaces && access != null) runCatching { open().use { access.restore(it) } } else null
            foreground {
                if (placesResult != null && placesResult.isFailure) {
                    state.outcome = BackupOutcome.Failed
                } else {
                    val settings: Result<RestoreResult>? = content.snapshot?.let { runCatching { SettingsBackup.apply(it, storage, pending) } }
                    state.outcome = if (settings != null && settings.isFailure) {
                        BackupOutcome.Failed
                    } else {
                        BackupOutcome.Restored(settings?.getOrNull(), placesResult?.getOrNull())
                    }
                    state.revision++
                }
                state.busy = false
            }
        }
    }

    /** Switches restored as intents that are still off, for the hint under the buttons. */
    fun pendingConsent(): List<SettingSpec> =
        pending.consent.mapNotNull(SettingsSchema::byId).filter { storage.read(it) != true }.sortedBy { it.id }

    fun dismissPending() {
        pending.consent = emptySet()
        state.revision++
    }
}
