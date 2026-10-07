package com.qtekfun.mapas.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.regions.RegionsActivity
import com.qtekfun.mapas.ui.theme.MapasTheme

/** The Settings screen (privacy and petrol stations). Opened from the gear on the map. */
class SettingsActivity : ComponentActivity() {
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
        )
        setContent {
            MapasTheme(darkTheme = isSystemInDarkTheme()) { SettingsScreen(env, onBack = ::finish) }
        }
    }
}
