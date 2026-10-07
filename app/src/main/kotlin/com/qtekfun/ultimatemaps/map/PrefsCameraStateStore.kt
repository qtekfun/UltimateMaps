package com.qtekfun.ultimatemaps.map

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.CameraStateStore

/** Keeps the last camera in a tiny SharedPreferences file (local only). [load] is synchronous and cheap. */
class PrefsCameraStateStore(private val prefs: SharedPreferences) : CameraStateStore {
    constructor(context: Context) : this(context.getSharedPreferences("camera", Context.MODE_PRIVATE))

    override fun load(): CameraState? {
        if (!prefs.contains(LAT)) return null
        return runCatching {
            CameraState(
                LatLon(prefs.getD(LAT), prefs.getD(LON)),
                prefs.getD(ZOOM),
                prefs.getD(BEARING),
                prefs.getD(TILT),
            )
        }.getOrNull()
    }

    override fun save(state: CameraState) {
        prefs.edit()
            .putD(LAT, state.center.lat).putD(LON, state.center.lon)
            .putD(ZOOM, state.zoom).putD(BEARING, state.bearing).putD(TILT, state.tilt)
            .apply()
    }

    // Doubles are stored as raw bits so no precision is lost.
    private fun SharedPreferences.getD(key: String) = Double.fromBits(getLong(key, 0L))
    private fun SharedPreferences.Editor.putD(key: String, v: Double) = putLong(key, v.toRawBits())

    private companion object {
        const val LAT = "lat"
        const val LON = "lon"
        const val ZOOM = "zoom"
        const val BEARING = "bearing"
        const val TILT = "tilt"
    }
}
