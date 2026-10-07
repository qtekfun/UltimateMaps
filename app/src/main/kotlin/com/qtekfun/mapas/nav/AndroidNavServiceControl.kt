package com.qtekfun.mapas.nav

import android.content.Context
import android.content.Intent
import com.qtekfun.mapas.core.nav.NavEnvironment

/**
 * [NavServiceControl] on the real [NavigationService]. Starting needs a visible activity (Android 12+ forbids a
 * foreground start from the background); a refusal is not an error here: the navigation goes on while the app is
 * open and the saved state lets the UI offer "resume" later. Stopping uses `stopService`, never a start intent: a
 * `startForegroundService` that is answered by "stop" without ever calling `startForeground` crashes the app.
 */
class AndroidNavServiceControl(context: Context) : NavServiceControl {
    private val app = context.applicationContext

    override fun start() {
        runCatching { NavigationService.start(app) }
    }

    override fun resume() {
        runCatching { NavigationService.resume(app) }
    }

    override fun stop() {
        runCatching { app.stopService(Intent(app, NavigationService::class.java)) }
    }
}

/** Glove mode in the app's private preferences (key `glove` of `nav_ui`); Settings can bind to the same key. */
class SharedNavUiPrefs(context: Context) : NavUiPrefs {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var glove: Boolean
        get() = prefs.getBoolean(KEY_GLOVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOVE, value).apply()
        }

    companion object {
        const val PREFS = "nav_ui"
        const val KEY_GLOVE = "glove"
    }
}

/** A simulated trip needs neither permission nor GPS: the environment says so while the simulation runs. */
class SimulationAwareEnvironment(private val real: NavEnvironment, private val location: SwitchableLocationSource) : NavEnvironment {
    override fun hasLocationPermission() = location.isSimulated || real.hasLocationPermission()
    override fun isLocationEnabled() = location.isSimulated || real.isLocationEnabled()
    override fun isPowerSaveMode() = real.isPowerSaveMode()
}
