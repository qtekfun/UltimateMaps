package com.qtekfun.ultimatemaps.platform

import android.content.Context
import com.qtekfun.ultimatemaps.core.map.AvailableLocationSource
import com.qtekfun.ultimatemaps.core.map.Geofencer
import com.qtekfun.ultimatemaps.core.map.MovementHint
import com.qtekfun.ultimatemaps.core.map.NoGeofencer
import com.qtekfun.ultimatemaps.core.map.NoMovementHint
import com.qtekfun.ultimatemaps.location.AndroidLocationSource

/**
 * What the `foss` flavor (F-Droid) uses: the platform location manager only, no movement hint, no proprietary library.
 * The `play` flavor has its own file with the same name and members.
 */
object PlatformServices {
    /** True only in the `play` flavor. */
    const val GOOGLE_SERVICES_FLAVOR = false

    fun locationSource(context: Context): AvailableLocationSource = AndroidLocationSource(context)

    fun movementHint(context: Context): MovementHint = NoMovementHint

    fun geofencer(context: Context): Geofencer = NoGeofencer

    /** True when Google Play Services is in use right now (never in this flavor). */
    fun googleServicesInUse(context: Context): Boolean = false
}
