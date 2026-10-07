package com.qtekfun.ultimatemaps.route

import android.content.Context
import android.util.Log
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.search.CoMapsSearchBackend
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import java.util.Locale
import kotlin.math.roundToInt

/** Distance and duration texts of the route card (locale-aware decimal separator, metric units). */
object RouteFormat {
    /** [distance] in the chosen [units]: feet below 1000 ft, then miles (one decimal below 100 mi). Metric is the default. */
    fun distance(meters: Double, locale: Locale, units: DistanceUnits): String {
        if (units == DistanceUnits.METRIC) return distance(meters, locale)
        val feet = meters.coerceAtLeast(0.0) * FEET_PER_METER
        val miles = meters.coerceAtLeast(0.0) / METERS_PER_MILE
        return when {
            feet < 1000 -> "${(feet / 10).roundToInt() * 10} ft"
            miles < 100 -> String.format(locale, "%.1f mi", miles)
            else -> String.format(locale, "%d mi", miles.roundToInt())
        }
    }

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_MILE = 1609.344

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
