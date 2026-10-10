package com.qtekfun.ultimatemaps.gms

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.AvailableLocationSource
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.LocationSource

/** Whether Google Play Services is installed and usable on this device. */
object GmsAvailability {
    fun isAvailable(context: Context): Boolean =
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)
}

/**
 * Position from Google's Fused Location Provider (GPS plus Wi-Fi and cell positioning), high accuracy. Used by the `play`
 * flavor when Play Services is present; the app falls back to the platform source otherwise. The caller must hold a location
 * permission before [start]. Fixes are never logged. [lastKnown] is the latest fix delivered since [start], or the one the
 * client had cached when it started.
 */
class GmsLocationSource(context: Context, intervalMillis: Long = 1000L) : AvailableLocationSource {
    @Volatile private var intervalMillis: Long = intervalMillis
    private var listener: LocationSource.Listener? = null
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val available = GmsAvailability.isAvailable(context)

    @Volatile private var last: LocationFix? = null
    private var callback: LocationCallback? = null

    override val isAvailable: Boolean get() = available

    override fun lastKnown(): LocationFix? = last

    @SuppressLint("MissingPermission")
    override fun start(listener: LocationSource.Listener) {
        stop()
        this.listener = listener
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    val p = LatLon.ofOrNull(l.latitude, l.longitude) ?: continue
                    val fix = LocationFix(
                        p,
                        if (l.hasAccuracy()) l.accuracy else null,
                        if (l.hasBearing()) l.bearing else null,
                        if (l.hasSpeed()) l.speed else null,
                        l.time,
                    )
                    last = fix
                    listener.onFix(fix)
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            callback = cb
            // The client's cached position makes the first screen immediate instead of "waiting for GPS".
            client.lastLocation.addOnSuccessListener { l ->
                if (l != null && last == null) LatLon.ofOrNull(l.latitude, l.longitude)?.let { last = LocationFix(it, if (l.hasAccuracy()) l.accuracy else null, null, null, l.time) }
            }
        } catch (_: SecurityException) {
            callback = null
        }
    }

    override fun stop() {
        callback?.let { runCatching { client.removeLocationUpdates(it) } }
        callback = null
    }

    override fun setPace(intervalMillis: Long) {
        if (intervalMillis == this.intervalMillis) return
        this.intervalMillis = intervalMillis
        val l = listener ?: return
        if (callback != null) start(l)
    }
}
