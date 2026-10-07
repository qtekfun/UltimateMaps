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
import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.ui.theme.MapasTheme

/** The "Maps" screen: hierarchical catalog, downloads, storage and offline mode. */
class RegionsActivity : ComponentActivity() {
    private val controller get() = (application as MapasApp).regions
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
            },
            onPause = controller::pause,
            onCancel = controller::cancel,
            onDelete = controller::delete,
            onDismissFailure = controller::dismissFailure,
            onTransitDownload = { id -> (application as MapasApp).transit.download(id) },
            onTransitDelete = { id -> (application as MapasApp).transit.delete(id) },
        )
        setContent {
            MapasTheme(darkTheme = isSystemInDarkTheme()) { RegionsScreen(controller.uiState().copy(transit = (application as MapasApp).transit.rows()), actions) }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.refreshInstalled()
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
