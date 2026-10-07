package com.qtekfun.ultimatemaps.core.voice

import kotlin.math.roundToInt

/** A distance as it is spoken: "300 metros", "1,5 kilómetros", "800 pies", "0,5 millas". */
data class SpokenDistance(val value: Double, val unit: Unit) {
    enum class Unit { METER, KILOMETER, FOOT, MILE }

    /** True when the number has a decimal part ("1,5"), which fixes the plural of the unit. */
    val isWhole: Boolean get() = value == value.toLong().toDouble()
}

/**
 * Rounds a distance the way a person would say it. Roads are not measured to the metre while driving, and a
 * prompt that says "in 437 metres" is both slow to hear and falsely precise:
 *
 * - metric: 1 km and above in half kilometres (whole above 10 km); below 1 km in hundreds from 150 m (950 m is
 *   "1 km", 900, 800, ... 200 m), then 100 and 50 m, and tens below 35 m;
 * - imperial: miles from about 1000 ft: tenths of a mile below 1 mi (0.5 mi), half miles above, whole above 10 mi;
 *   below that, feet in hundreds from 150 ft, then 100 and 50 ft, and tens below 35 ft.
 */
object DistanceRounding {
    fun round(meters: Int, units: DistanceUnits): SpokenDistance {
        val m = meters.coerceAtLeast(0)
        return when (units) {
            DistanceUnits.METRIC -> metric(m)
            DistanceUnits.IMPERIAL -> imperial(m)
        }
    }

    private fun metric(m: Int): SpokenDistance =
        if (m >= 950) SpokenDistance(bigUnit(m / 1000.0), SpokenDistance.Unit.KILOMETER)
        else SpokenDistance(small(m).toDouble(), SpokenDistance.Unit.METER)

    private fun imperial(m: Int): SpokenDistance {
        val feet = m * FEET_PER_METER
        if (feet >= 950) {
            val miles = m / METERS_PER_MILE
            val rounded = if (miles < 0.95) (miles * 10).roundToInt() / 10.0 else bigUnit(miles)
            return SpokenDistance(rounded, SpokenDistance.Unit.MILE)
        }
        return SpokenDistance(small(feet.roundToInt()).toDouble(), SpokenDistance.Unit.FOOT)
    }

    /** Half units below 10, whole units from 10 on. */
    private fun bigUnit(x: Double): Double = if (x < 10) (x * 2).roundToInt() / 2.0 else x.roundToInt().toDouble()

    /** Metres or feet below the big unit. */
    private fun small(x: Int): Int = when {
        x >= 150 -> ((x + 50) / 100) * 100
        x >= 75 -> 100
        x >= 35 -> 50
        else -> (((x + 5) / 10) * 10).coerceAtLeast(10)
    }

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_MILE = 1609.344
}
