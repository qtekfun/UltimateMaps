package com.qtekfun.ultimatemaps.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import com.qtekfun.ultimatemaps.MapasApp
import com.qtekfun.ultimatemaps.places.openPlacesService
import com.qtekfun.ultimatemaps.regions.RegionsActivity
import com.qtekfun.ultimatemaps.search.PrefsHistorySettings
import com.qtekfun.ultimatemaps.search.PrefsPlaceLanguageStore
import com.qtekfun.ultimatemaps.settings.backup.AndroidPlacesBackup
import com.qtekfun.ultimatemaps.settings.backup.AndroidSettingsStorage
import com.qtekfun.ultimatemaps.settings.backup.PrefsPendingRestore
import com.qtekfun.ultimatemaps.settings.backup.SettingsBackupCoordinator
import com.qtekfun.ultimatemaps.settings.backup.SettingsFile
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import com.qtekfun.ultimatemaps.voice.VoiceModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** The Settings screen (a hub of categories). Opened from the gear in the map buttons. */
class SettingsActivity : ComponentActivity() {
    // Backup and restore: the system pickers (no storage permission, the app never sees a path). Created here, as the
    // result launchers must exist before the activity is started.
    private var coordinator: SettingsBackupCoordinator? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mapas-settings-backup") }
    private val createSettings = registerForActivityResult(ActivityResultContracts.CreateDocument(SettingsFile.MIME_JSON)) { uri ->
        if (uri != null) coordinator?.exportSettings { openOut(uri) }
    }
    private val createEverything = registerForActivityResult(ActivityResultContracts.CreateDocument(SettingsFile.MIME_ZIP)) { uri ->
        if (uri != null) coordinator?.exportEverything { openOut(uri) }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coordinator?.load { openIn(uri) }
    }

    /** The system Do Not Disturb screens need no permission; falls back to the sound settings, then does nothing. */
    /** The installed text-to-speech engines with the name the user knows them by. */
    private fun installedVoiceEngines(): List<Pair<String, String>> =
        com.qtekfun.ultimatemaps.voice.VoiceInstall.installedEngines(this).map { pkg ->
            pkg to (runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() ?: pkg)
        }.sortedBy { it.second.lowercase() }

    private fun openDoNotDisturbSettings() {
        val intents = listOf(Intent("android.settings.ZEN_MODE_PRIORITY_SETTINGS"), Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
        for (i in intents) if (runCatching { startActivity(i) }.isSuccess) return
    }

    private fun openOut(uri: Uri) = checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "cannot open the file" }
    private fun openIn(uri: Uri) = checkNotNull(contentResolver.openInputStream(uri)) { "cannot open the file" }
    /** Opens a page in the user's own browser (the app itself makes no connection for it); a phone without a browser does nothing. */
    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MapasApp
        val regions = app.regions
        val env = SettingsEnv(
            store = app.fuelSettings,
            fuel = app.fuel,
            policy = app.networkPolicy,
            offline = { regions.offline }, // Compose state: the screen recomposes when it changes
            setOffline = regions::setOfflineMode,
            catalogUrl = { regions.serverUrl },
            openMaps = { startActivity(Intent(this, RegionsActivity::class.java)) },
            navigation = NavigationSettingsEnv(VoiceModule.settings(this), VoiceModule.guide(this), transitTrip = app.transitTripSettings, openDoNotDisturb = ::openDoNotDisturbSettings, voiceEngines = ::installedVoiceEngines),
            history = HistorySettingsEnv(PrefsHistorySettings(this), clear = ::clearSearchHistory),
            cameras = CamerasSettingsEnv(
                app.cameraSettings, app.cameraData, app.incidents, offline = { regions.offline }, onChanged = app::ensureCameraAlerts,
            ),
            chargers = ChargersSettingsEnv(app.chargerSettings, app.chargerData),
            zbe = ZbeSettingsEnv(app.zbeSettings, app.zbeData, onChanged = app::ensureZbePrompter),
            trails = TrailsSettingsEnv(app.routeSettings, app.routeData),
            bikeShare = BikeShareSettingsEnv(app.bikeShareSettings, app.bikeShareData),
            weather = WeatherSettingsEnv(app.weatherAlerts, openUrl = ::openInBrowser),
            recording = RecordingSettingsEnv(app.recording),
            placeLanguage = PlaceLanguageSettingsEnv(PrefsPlaceLanguageStore(this)),
            backup = backupEnv(app),
            about = AboutSettingsEnv(
                version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty(),
                transitAttributions = { app.transit.attributions },
                notice = { runCatching { assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }.getOrDefault("") },
                diagnostics = {
                    com.qtekfun.ultimatemaps.diagnostics.Diagnostics.report(this, (application as MapasApp).alertStats.describe(System.currentTimeMillis()))
                },
                clearDiagnostics = { com.qtekfun.ultimatemaps.diagnostics.Diagnostics.clear(this) },
            ),
        )
        setContent {
            MapasTheme(darkTheme = isSystemInDarkTheme()) { SettingsScreen(env, onBack = ::finish) }
        }
    }

    /**
     * Wires the "Backup and restore" section. Settings are read from and written to the preference files; the three
     * keys whose owner is a live object (offline mode and the catalog address, the recording switch) go through that
     * object, and the stores that cache their settings in memory re-read afterwards. Nothing here starts a download.
     */
    private fun backupEnv(app: MapasApp): BackupSettingsEnv {
        val regions = app.regions
        val storage = AndroidSettingsStorage(
            context = this,
            overrides = mapOf<String, (Any) -> Unit>(
                "regions/offline_mode" to { v: Any -> regions.setOfflineMode(v as Boolean) },
                "regions/catalog_url" to { v: Any -> regions.saveServerUrl(v as String); Unit },
                "recording/enabled" to { v: Any -> app.recording.setEnabled(v as Boolean) },
            ),
            onFinish = {
                listOf(app.fuelSettings, app.cameraSettings, app.chargerSettings, app.zbeSettings, app.bikeShareSettings, app.weatherSettings, app.routeSettings, VoiceModule.settings(this), app.transitTripSettings).forEach { (it as? SettingsReloadable)?.reload() }
                app.ensureCameraAlerts()
                app.ensureZbePrompter()
            },
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val flow = SettingsBackupCoordinator(
            state = BackupUiState(),
            storage = storage,
            pending = PrefsPendingRestore(this),
            installedRegions = { regions.installed.map { it.region.id } },
            appVersion = version,
            nowMillis = System::currentTimeMillis,
            places = AndroidPlacesBackup(this),
            background = { work -> io.execute(work) },
            foreground = { work -> runOnUiThread(work) },
        )
        coordinator = flow
        return BackupSettingsEnv(
            state = flow.state,
            onExportSettings = { createSettings.launch(SettingsFile.settingsName(today())) },
            onExportEverything = { createEverything.launch(SettingsFile.everythingName(today())) },
            onImport = { openBackup.launch(arrayOf("*/*")) },
            onConfirmImport = flow::confirm,
            onCancelImport = flow::cancel,
            pendingConsent = flow::pendingConsent,
            onDismissPending = flow::dismissPending,
        )
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Deletes the stored recent searches (database work, off the main thread). */
    private fun clearSearchHistory() {
        val app = applicationContext
        Thread({
            runCatching { openPlacesService(app).also { it.clearSearches(); it.close() } }
        }, "mapas-clear-history").start()
    }
}
