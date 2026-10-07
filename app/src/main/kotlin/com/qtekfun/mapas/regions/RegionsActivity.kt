package com.qtekfun.mapas.regions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.settings.backup.PrefsPendingRestore
import com.qtekfun.mapas.ui.theme.MapasTheme

/** The "Maps" screen: hierarchical catalog, downloads, storage and offline mode. */
class RegionsActivity : ComponentActivity() {
    private val controller get() = (application as MapasApp).regions
    private val pendingRestore by lazy { PrefsPendingRestore(this) }
    private var restoredRegions by mutableStateOf(emptySet<String>())
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the download runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = RegionsActions(
            onClose = ::finish,
            onRefresh = controller::refreshCatalog,
            onSetOffline = controller::setOfflineMode,
            onSaveServer = controller::saveServerUrl,
            onSelectLocation = controller::selectLocation,
            onDownload = { region ->
                askForNotifications()
                controller.download(region)
                // A map the owner asked for is no longer an open offer of a restore.
                if (region.id in restoredRegions) {
                    restoredRegions = restoredRegions - region.id
                    pendingRestore.regions = restoredRegions
                }
            },
            onPause = controller::pause,
            onCancel = controller::cancel,
            onDelete = controller::delete,
            onDismissFailure = controller::dismissFailure,
            onDismissRestored = { pendingRestore.regions = emptySet(); restoredRegions = emptySet() },
        )
        setContent {
            MapasTheme(darkTheme = isSystemInDarkTheme()) { RegionsScreen(controller.uiState().copy(restoredRegionIds = restoredRegions), actions) }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.refreshInstalled()
        restoredRegions = pendingRestore.regions // maps listed by a settings restore, offered for download (never automatic)
        // Opening this screen is the user's request to browse maps: refresh the catalog (policy-gated; with offline
        // mode on or no server configured nothing is contacted and the saved copy, if any, is shown).
        if (controller.serverUrl.isNotEmpty()) controller.refreshCatalog()
    }

    /** Android 13+: the progress notification of the foreground service needs the runtime permission to be visible. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
