package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.math.max
import kotlin.math.min

/** One sample of the profile chart: [distanceMeters] from the start, [altitudeMeters] smoothed, [gradePercent] of the stretch that ends here. */
data class ElevationSample(val distanceMeters: Double, val altitudeMeters: Double, val gradePercent: Double)

/**
 * Elevation profile of a route: totals plus at most [MAX_SAMPLES] samples ready to draw. Pure Kotlin, no Android.
 *
 * The raw heights of the maps are whole metres taken from a coarse terrain model at the road's vertices, so they are
 * noisy: a flat road can wiggle by a few metres, and summing every wiggle would invent hundreds of metres of climbing.
 * [of] therefore (1) fills the points without a height by interpolating along the route, (2) smooths with a moving
 * average over a window measured in *distance* (so dense and sparse vertices weigh the same) and (3) counts ascent and
 * descent only when the height has moved more than [NOISE_THRESHOLD_METERS] from the last turning point (hysteresis).
 */
class ElevationProfile private constructor(
    val samples: List<ElevationSample>,
    val totalMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minMeters: Double,
    val maxMeters: Double,
) {
    val roundedAscent: Int get() = Math.round(ascentMeters).toInt()
    val roundedDescent: Int get() = Math.round(descentMeters).toInt()

    /** Steepest climb over the samples in percent (0 when never climbing). */
    val maxGradePercent: Double get() = samples.maxOf { it.gradePercent }.coerceAtLeast(0.0)

    /** Steepest descent over the samples in percent, negative (0 when never descending). */
    val minGradePercent: Double get() = samples.minOf { it.gradePercent }.coerceAtMost(0.0)

    /** Smoothed altitude at [distanceMeters] from the start (clamped to the route), by linear interpolation of the samples. */
    fun altitudeAt(distanceMeters: Double): Double {
        val d = distanceMeters.coerceIn(0.0, totalMeters)
        val found = samples.binarySearch { it.distanceMeters.compareTo(d) }
        if (found >= 0) return samples[found].altitudeMeters
        val hi = -found - 1
        if (hi <= 0) return samples.first().altitudeMeters
        if (hi >= samples.size) return samples.last().altitudeMeters
        val a = samples[hi - 1]
        val b = samples[hi]
        val span = b.distanceMeters - a.distanceMeters
        return if (span <= 0.0) b.altitudeMeters else a.altitudeMeters + (b.altitudeMeters - a.altitudeMeters) * (d - a.distanceMeters) / span
    }

    companion object {
        const val MAX_SAMPLES = 200
        const val NOISE_THRESHOLD_METERS = 3.0
        const val SMOOTHING_HALF_WINDOW_METERS = 40.0
        const val MIN_VALID_FRACTION = 0.5
        private const val MIN_LENGTH_METERS = 50.0
        private const val MAX_GRADE_PERCENT = 40.0
        private const val MAX_PLAUSIBLE_ALTITUDE = 9000.0
        private const val MIN_PLAUSIBLE_ALTITUDE = -500.0

        /**
         * Builds the profile from the route [geometry] and its raw [altitudes] (NaN = unknown). Returns null (nothing to
         * show) when the altitudes are missing or do not match the geometry, the route is shorter than 50 m, fewer than
         * half the points have a plausible height, or every height is the same (a map with no data gives a flat line).
         */
        fun of(geometry: List<LatLon>, altitudes: List<Double>): ElevationProfile? {
            val n = geometry.size
            if (n < 2 || altitudes.size != n) return null

            val dist = DoubleArray(n)
            for (i in 1 until n) dist[i] = dist[i - 1] + geometry[i - 1].distanceTo(geometry[i])
            val total = dist[n - 1]
            if (total < MIN_LENGTH_METERS) return null

            val valid = BooleanArray(n) { i -> altitudes[i] in MIN_PLAUSIBLE_ALTITUDE..MAX_PLAUSIBLE_ALTITUDE }
            val validCount = valid.count { it }
            if (validCount < 2 || validCount < n * MIN_VALID_FRACTION) return null
            val filled = fillGaps(dist, altitudes, valid)
            if (filled.all { it == filled[0] }) return null

            val smooth = smooth(dist, filled)
            val (ascent, descent) = climb(smooth)
            return ElevationProfile(
                samples = downsample(dist, smooth, total),
                totalMeters = total,
                ascentMeters = ascent,
                descentMeters = descent,
                minMeters = smooth.min(),
                maxMeters = smooth.max(),
            )
        }

        /** Linear interpolation by distance between valid neighbours; the ends repeat the nearest valid height. */
        private fun fillGaps(dist: DoubleArray, raw: List<Double>, valid: BooleanArray): DoubleArray {
            val n = dist.size
            val out = DoubleArray(n)
            var prev = -1
            for (i in 0 until n) {
                if (!valid[i]) continue
                if (prev == -1) {
                    for (k in 0 until i) out[k] = raw[i]
                } else if (i - prev > 1) {
                    val span = dist[i] - dist[prev]
                    for (k in prev + 1 until i) {
                        val t = if (span > 0.0) (dist[k] - dist[prev]) / span else 0.0
                        out[k] = raw[prev] + (raw[i] - raw[prev]) * t
                    }
                }
                out[i] = raw[i]
                prev = i
            }
            for (k in prev + 1 until n) out[k] = raw[prev]
            return out
        }

        /** Moving average of the heights within +-[SMOOTHING_HALF_WINDOW_METERS], each vertex weighted by the route length it stands for. */
        private fun smooth(dist: DoubleArray, h: DoubleArray): DoubleArray {
            val n = dist.size
            val w = DoubleArray(n) { i ->
                val before = if (i > 0) dist[i] - dist[i - 1] else 0.0
                val after = if (i < n - 1) dist[i + 1] - dist[i] else 0.0
                (before + after) / 2
            }
            val out = DoubleArray(n)
            var lo = 0
            var hi = 0
            for (i in 0 until n) {
                while (dist[i] - dist[lo] > SMOOTHING_HALF_WINDOW_METERS) lo++
                if (hi < i) hi = i
                while (hi + 1 < n && dist[hi + 1] - dist[i] <= SMOOTHING_HALF_WINDOW_METERS) hi++
                var sw = 0.0
                var sh = 0.0
                for (k in lo..hi) {
                    sw += w[k]
                    sh += w[k] * h[k]
                }
                out[i] = if (sw > 0.0) sh / sw else h[i]
            }
            // The start and the end are the places the user knows: keep them on the real height, not a blurred one.
            out[0] = h[0]
            out[n - 1] = h[n - 1]
            return out
        }

        /** Ascent and descent with hysteresis: a move counts only after the height left the last turning point by the threshold. */
        private fun climb(h: DoubleArray): Pair<Double, Double> {
            var ascent = 0.0
            var descent = 0.0
            var extreme = h[0]
            var dir = 0 // 0 = undecided, 1 = climbing, -1 = descending
            for (v in h) {
                when (dir) {
                    1 -> if (v > extreme) {
                        ascent += v - extreme
                        extreme = v
                    } else if (extreme - v >= NOISE_THRESHOLD_METERS) {
                        descent += extreme - v
                        extreme = v
                        dir = -1
                    }
                    -1 -> if (v < extreme) {
                        descent += extreme - v
                        extreme = v
                    } else if (v - extreme >= NOISE_THRESHOLD_METERS) {
                        ascent += v - extreme
                        extreme = v
                        dir = 1
                    }
                    else -> if (v - extreme >= NOISE_THRESHOLD_METERS) {
                        ascent += v - extreme
                        extreme = v
                        dir = 1
                    } else if (extreme - v >= NOISE_THRESHOLD_METERS) {
                        descent += extreme - v
                        extreme = v
                        dir = -1
                    }
                }
            }
            return ascent to descent
        }

        /** At most [MAX_SAMPLES] samples evenly spaced by distance, always including the first and the last point. */
        private fun downsample(dist: DoubleArray, h: DoubleArray, total: Double): List<ElevationSample> {
            val n = dist.size
            val count = min(MAX_SAMPLES, max(2, n))
            val xs = DoubleArray(count) { total * it / (count - 1) }
            val ys = DoubleArray(count)
            var j = 1
            for (s in 0 until count) {
                while (j < n - 1 && dist[j] < xs[s]) j++
                val span = dist[j] - dist[j - 1]
                val t = if (span > 0.0) ((xs[s] - dist[j - 1]) / span).coerceIn(0.0, 1.0) else 1.0
                ys[s] = h[j - 1] + (h[j] - h[j - 1]) * t
            }
            fun grade(s: Int): Double {
                val run = xs[s] - xs[s - 1]
                return if (run > 0.0) ((ys[s] - ys[s - 1]) / run * 100.0).coerceIn(-MAX_GRADE_PERCENT, MAX_GRADE_PERCENT) else 0.0
            }
            return List(count) { s -> ElevationSample(xs[s], ys[s], if (s == 0) grade(1) else grade(s)) }
        }
    }
}
