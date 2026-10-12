package com.qtekfun.ultimatemaps.platform

import android.content.Context
import com.qtekfun.ultimatemaps.core.map.AvailableLocationSource
import com.qtekfun.ultimatemaps.core.map.Geofencer
import com.qtekfun.ultimatemaps.core.map.MovementHint
import com.qtekfun.ultimatemaps.core.map.NoGeofencer
import com.qtekfun.ultimatemaps.core.map.NoMovementHint
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.OnlineSearchSettingsStore
import com.qtekfun.ultimatemaps.gms.GmsAvailability
import com.qtekfun.ultimatemaps.gms.GmsGeofencer
import com.qtekfun.ultimatemaps.gms.GmsLocationSource
import com.qtekfun.ultimatemaps.gms.GmsMovementHint
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.link.ShortLinkResolver
import com.qtekfun.ultimatemaps.location.AndroidLocationSource

/**
 * What the `play` flavor uses when Google Play Services is installed: Google's Fused Location Provider (GPS plus Wi-Fi and
 * cell positioning) and Google's activity recognition as a weak "in a vehicle" hint for the public-transport trip. Without
 * Play Services (or without the permission) everything falls back to what the `foss` flavor does.
 */
object PlatformServices {
    const val GOOGLE_SERVICES_FLAVOR = true

    /**
     * The optional online fallback of the offline search (Photon, OpenStreetMap data). Off until the owner turns the switch
     * on in Settings; every request goes through [policy], so offline mode blocks it.
     */
    fun onlineGeocoder(
        policy: NetworkPolicy,
        settings: OnlineSearchSettingsStore,
        versionName: String?,
        language: () -> String?,
    ): OnlineGeocoder = PhotonGeocoder(
        settings, policy, PhotonGeocoder.fetchOver(policy, PhotonGeocoder.userAgent(versionName)), language,
    )

    fun locationSource(context: Context): AvailableLocationSource =
        if (GmsAvailability.isAvailable(context)) GmsLocationSource(context) else AndroidLocationSource(context)

    fun movementHint(context: Context): MovementHint =
        if (GmsAvailability.isAvailable(context)) GmsMovementHint(context) else NoMovementHint

    fun geofencer(context: Context): Geofencer =
        if (GmsAvailability.isAvailable(context)) GmsGeofencer(context) else NoGeofencer

    fun googleServicesInUse(context: Context): Boolean = GmsAvailability.isAvailable(context)

    /**
     * Expands `maps.app.goo.gl` links that the user shares into the app, through [policy] (offline mode applies). Only the
     * short URL is sent. The `foss` flavor never does this.
     */
    fun shortLinkResolver(policy: NetworkPolicy): ShortLinkResolver = HttpShortLinkResolver(policy)
}
