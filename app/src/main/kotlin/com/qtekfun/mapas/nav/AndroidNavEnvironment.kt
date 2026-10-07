package com.qtekfun.mapas.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import com.qtekfun.mapas.core.nav.NavEnvironment

/** [NavEnvironment] on the platform services: permission, location switch and battery saver. */
class AndroidNavEnvironment(private val context: Context) : NavEnvironment {
    private val app = context.applicationContext

    override fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun isLocationEnabled(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                .getOrDefault(false)
        }
    }

    override fun isPowerSaveMode(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
}
