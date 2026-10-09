package com.qtekfun.ultimatemaps.platform

import android.content.Context
import com.qtekfun.ultimatemaps.core.map.AvailableLocationSource
import com.qtekfun.ultimatemaps.core.map.MovementHint
import com.qtekfun.ultimatemaps.core.map.NoMovementHint
import com.qtekfun.ultimatemaps.gms.GmsAvailability
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

    fun googleServicesInUse(context: Context): Boolean = GmsAvailability.isAvailable(context)
}
