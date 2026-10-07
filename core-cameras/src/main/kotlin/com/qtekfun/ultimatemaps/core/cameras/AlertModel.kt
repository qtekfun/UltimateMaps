package com.qtekfun.ultimatemaps.core.cameras

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** What an alert is about. */
enum class AlertCategory {
    FIXED_CAMERA, SECTION, MOBILE_ZONE, V16, ACCIDENT, CLOSURE, CONGESTION, OBSTACLE;

    val isCamera: Boolean get() = this == FIXED_CAMERA || this == SECTION || this == MOBILE_ZONE
}

/**
 * Something that can be announced ahead: precomputed once when the data is loaded, so the warner allocates nothing
 * per fix. [group] ties records that must be announced once together (the two ends of one incident). [axisDeg] with
 * [sense] and [toleranceDeg] say which travel directions are affected (null axis: all). [limitKmh] is the enforced
 * limit when known.
 */
class AlertTarget(
    val id: String,
    val group: String,
    val category: AlertCategory,
    val lat: Double,
    val lon: Double,
    val axisDeg: Int?,
    val sense: AxisSense,
    val toleranceDeg: Int,
    val limitKmh: Int?,
)

/** Where the warner finds what is near. Implementations must not allocate per call (the visitor is the only callback). */
interface AlertSource {
    fun interface Visitor {
        fun visit(target: AlertTarget)
    }

    fun forEachNear(lat: Double, lon: Double, radiusMeters: Double, visitor: Visitor)
}

/** Flat-earth grid over [AlertTarget]s (0.05 degree cells, about 5 km), enough for radii of a few kilometres. */
class TargetGrid(targets: List<AlertTarget>) : AlertSource {
    private val cells = HashMap<Long, Array<AlertTarget>>()

    init {
        val tmp = HashMap<Long, ArrayList<AlertTarget>>()
        for (t in targets) tmp.getOrPut(key(cell(t.lat), cell(t.lon))) { ArrayList(2) }.add(t)
        for ((k, v) in tmp) cells[k] = v.toTypedArray()
    }

    val size: Int = targets.size

    override fun forEachNear(lat: Double, lon: Double, radiusMeters: Double, visitor: AlertSource.Visitor) {
        val dLat = radiusMeters / METERS_PER_DEGREE
        val dLon = radiusMeters / (METERS_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val r0 = cell(lat - dLat); val r1 = cell(lat + dLat)
        val c0 = cell(lon - dLon); val c1 = cell(lon + dLon)
        var r = r0
        while (r <= r1) {
            var c = c0
            while (c <= c1) {
                val arr = cells[key(r, c)]
                if (arr != null) for (t in arr) visitor.visit(t)
                c++
            }
            r++
        }
    }

    companion object {
        const val METERS_PER_DEGREE = 111_195.0
        private const val CELLS_PER_DEGREE = 20.0
        private const val OFFSET = 10_000

        private fun cell(deg: Double) = floor(deg * CELLS_PER_DEGREE).toInt()
        private fun key(r: Int, c: Int) = ((r + OFFSET).toLong() shl 32) or (c + OFFSET).toLong()

        /** Flat-earth distance in metres between two close points (error below 0.5 % within a few kilometres). */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * METERS_PER_DEGREE
            val dx = (lon2 - lon1) * METERS_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(dx, dy)
        }

        /** Bearing in degrees clockwise from north (0..360) from point 1 to a close point 2. */
        fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1)
            val dx = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val deg = Math.toDegrees(kotlin.math.atan2(dx, dy))
            return if (deg < 0) deg + 360.0 else deg
        }

        /** Smallest absolute angle between two bearings, 0..180. */
        fun angleDiff(a: Double, b: Double): Double {
            val d = kotlin.math.abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}

/** Builds the announceable targets of a [CameraDataset] for the categories the user enabled. */
object CameraTargets {
    private const val ROAD_TOLERANCE_DEG = 50

    fun of(data: CameraDataset, settings: CameraSettings): List<AlertTarget> {
        val out = ArrayList<AlertTarget>()
        if (settings.fixedEnabled) {
            for (c in data.fixed) {
                out += AlertTarget(c.id, c.id, AlertCategory.FIXED_CAMERA, c.location.lat, c.location.lon, c.axisDeg, c.sense, ROAD_TOLERANCE_DEG, c.maxSpeedKmh)
            }
            for (s in data.sections) {
                val end = s.endLocation
                val axis = end?.let { TargetGrid.bearingDegrees(s.location.lat, s.location.lon, it.lat, it.lon).toInt() % 360 }
                out += AlertTarget(s.id, s.id, AlertCategory.SECTION, s.location.lat, s.location.lon, axis, if (axis != null) AxisSense.ALONG else AxisSense.BOTH, ROAD_TOLERANCE_DEG, s.maxSpeedKmh)
            }
        }
        if (settings.mobileZonesEnabled) {
            for (z in data.zones) {
                if (!z.hasGeometry) continue
                // Both ends announce (the driver may enter from either side); one announcement per zone (same group).
                val a = z.line.first()
                val b = z.line[1]
                val y = z.line.last()
                val x = z.line[z.line.size - 2]
                val axisA = TargetGrid.bearingDegrees(a.lat, a.lon, b.lat, b.lon).toInt() % 360
                val axisY = TargetGrid.bearingDegrees(x.lat, x.lon, y.lat, y.lon).toInt() % 360
                out += AlertTarget(z.id + "a", z.id, AlertCategory.MOBILE_ZONE, a.lat, a.lon, axisA, AxisSense.ALONG, ROAD_TOLERANCE_DEG, null)
                out += AlertTarget(z.id + "b", z.id, AlertCategory.MOBILE_ZONE, y.lat, y.lon, axisY, AxisSense.AGAINST, ROAD_TOLERANCE_DEG, null)
            }
        }
        return out
    }
}
