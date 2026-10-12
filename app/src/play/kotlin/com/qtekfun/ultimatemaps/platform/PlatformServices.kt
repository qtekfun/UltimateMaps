package com.qtekfun.ultimatemaps.platform

import android.content.Context
import androidx.compose.runtime.Composable
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.SystemGeocoderFallback
import com.qtekfun.ultimatemaps.search.PrefsSystemGeocoderSettings
import com.qtekfun.ultimatemaps.core.map.AvailableLocationSource
import com.qtekfun.ultimatemaps.core.map.Geofencer
import com.qtekfun.ultimatemaps.core.map.MovementHint
import com.qtekfun.ultimatemaps.core.map.NoGeofencer
import com.qtekfun.ultimatemaps.core.map.NoMovementHint
import com.qtekfun.ultimatemaps.gms.GmsAvailability
import com.qtekfun.ultimatemaps.gms.GmsGeofencer
import com.qtekfun.ultimatemaps.gms.GmsLocationSource
import com.qtekfun.ultimatemaps.gms.GmsMovementHint
import com.qtekfun.ultimatemaps.location.AndroidLocationSource

/**
 * What the `play` flavor uses when Google Play Services is installed: Google's Fused Location Provider (GPS plus Wi-Fi and
 * cell positioning) and Google's activity recognition as a weak "in a vehicle" hint for the public-transport trip. Without
 * Play Services (or without the permission) everything falls back to what the `foss` flavor does.
 */
object PlatformServices {
    const val GOOGLE_SERVICES_FLAVOR = true

    fun locationSource(context: Context): AvailableLocationSource =
        if (GmsAvailability.isAvailable(context)) GmsLocationSource(context) else AndroidLocationSource(context)

    fun movementHint(context: Context): MovementHint =
        if (GmsAvailability.isAvailable(context)) GmsMovementHint(context) else NoMovementHint

    fun geofencer(context: Context): Geofencer =
        if (GmsAvailability.isAvailable(context)) GmsGeofencer(context) else NoGeofencer

    fun googleServicesInUse(context: Context): Boolean = GmsAvailability.isAvailable(context)

    /**
     * The optional online address lookup through the framework `Geocoder` (Google's backend on a phone with Play
     * Services). It asks only while the owner's switch is on (default off) and while [allowed] says so (offline mode,
     * network policy). Nothing in the default search path calls it yet: the caller decides when the offline search is empty.
     */
    fun onlineGeocoder(context: Context, allowed: () -> Boolean = { true }): OnlineGeocoder? {
        val settings = PrefsSystemGeocoderSettings(context)
        return SystemGeocoderFallback(
            backend = AndroidGeocoderBackend(context),
            scheduler = MainLooperScheduler(),
            enabled = { settings.enabled },
            allowed = allowed,
        )
    }

    /** The settings switch of the online address lookup (play flavor only). */
    @Composable
    fun SystemGeocoderSettingsSection() = SystemGeocoderSection()
}
