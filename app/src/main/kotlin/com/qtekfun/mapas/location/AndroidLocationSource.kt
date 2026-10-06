package com.qtekfun.mapas.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.LocationSource

/** Which platform providers to listen to. Pure so the policy is unit-tested without a device. */
object ProviderChoice {
    const val FUSED = "fused" // LocationManager.FUSED_PROVIDER (API 31)
    const val GPS = "gps"
    const val NETWORK = "network"

    /**
     * On API 31+ use the system fused provider only if `hasProvider` says it exists (it is backed by Google on
     * devices with GMS and by AOSP/microG elsewhere, with no library on our side). Otherwise, and on older
     * versions, fall back to GPS and NETWORK, whichever exist. Empty list = no provider.
     */
    fun choose(sdkInt: Int, hasProvider: (String) -> Boolean): List<String> {
        if (sdkInt >= Build.VERSION_CODES.S && hasProvider(FUSED)) return listOf(FUSED)
        return listOf(GPS, NETWORK).filter(hasProvider)
    }
}

/**
 * [LocationSource] on the platform [LocationManager]; no play-services. The caller must hold a location
 * permission before [start]. Fixes are never logged.
 */
class AndroidLocationSource(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val intervalMillis: Long = 1000L,
) : LocationSource {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active: LocationListener? = null

    @SuppressLint("NewApi") // guarded by sdkInt (injectable for tests); lint cannot see through the field
    private fun has(provider: String): Boolean =
        if (sdkInt >= Build.VERSION_CODES.S) lm.hasProvider(provider) else lm.allProviders.contains(provider)

    fun providers(): List<String> = ProviderChoice.choose(sdkInt, ::has)

    val isAvailable: Boolean get() = providers().isNotEmpty()

    @SuppressLint("MissingPermission")
    override fun lastKnown(): LocationFix? = try {
        providers().mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.toFix()
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: LocationSource.Listener) {
        stop()
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                location.toFix()?.let(listener::onFix)
            }

            @Deprecated("Deprecated in API 29; required below API 30")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            for (p in providers()) lm.requestLocationUpdates(p, intervalMillis, 0f, l, Looper.getMainLooper())
            active = l
        } catch (_: SecurityException) {
            lm.removeUpdates(l)
        }
    }

    override fun stop() {
        active?.let { lm.removeUpdates(it) }
        active = null
    }

    private fun Location.toFix(): LocationFix? {
        val p = LatLon.ofOrNull(latitude, longitude) ?: return null
        return LocationFix(
            p,
            if (hasAccuracy()) accuracy else null,
            if (hasBearing()) bearing else null,
            if (hasSpeed()) speed else null,
            time,
        )
    }
}
