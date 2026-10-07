package com.qtekfun.ultimatemaps.route

import android.content.Context
import android.util.Log
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.search.CoMapsSearchBackend
import com.qtekfun.ultimatemaps.search.CoreMaps
import java.util.Locale
import kotlin.math.roundToInt

/** Distance and duration texts of the route card (locale-aware decimal separator, metric units). */
object RouteFormat {
    fun distance(meters: Double, locale: Locale): String = when {
        meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 100_000 -> String.format(locale, "%.1f km", meters / 1000)
        else -> String.format(locale, "%d km", (meters / 1000).roundToInt())
    }

    fun duration(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }
}

/** Production [RouteBackend]: shares the one [CoMapsCore] of the process with the search. */
class CoMapsRouteBackend(private val context: Context) : RouteBackend {
    @Synchronized
    override fun open(maps: CoreMaps, timeoutSec: Int) =
        CoMapsSearchBackend.prepareCore(context, maps).routingEngine(timeoutSec)
}

/** Latency goes to logcat under UMROUTE: profile, milliseconds and result only (no positions, no names). */
object LogcatRouteLog : RouteLog {
    private const val TAG = "UMROUTE"

    override fun computed(profile: RoutingProfile, millis: Long, result: String) = computed(profile, millis, result, 0)

    override fun computed(profile: RoutingProfile, millis: Long, result: String, stops: Int) {
        val withStops = if (stops > 0) " stops=$stops" else "" // only a count, never where
        Log.i(TAG, "route profile=${profile.name.lowercase()}$withStops ms=$millis result=$result")
    }
}
