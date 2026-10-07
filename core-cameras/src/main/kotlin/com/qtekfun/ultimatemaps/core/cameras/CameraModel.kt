package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon

/** Which public source a camera record comes from (bit flags in the data file). */
object CameraSources {
    const val DGT = 1
    const val OSM = 2
}

enum class CameraKind {
    /** A fixed speed-camera booth or pole. */
    FIXED,

    /** An average-speed section: [SpeedCamera.location] is where it starts, [SpeedCamera.endLocation] where it ends. */
    SECTION,
}

/**
 * Which travel direction a record applies to, relative to [SpeedCamera.axisDeg] (the bearing of the road in the
 * direction of increasing kilometre points, or the OSM `direction`).
 */
enum class AxisSense { BOTH, ALONG, AGAINST }

/**
 * A fixed camera or an average-speed section. [maxSpeedKmh] is the enforced limit when the source says it (OSM
 * `maxspeed`); the DGT publication has none. [axisDeg] is a rough direction of the road, not a published value of the
 * DGT (see docs/phase2/cameras-data.md); null means unknown and then both directions are treated as affected.
 */
data class SpeedCamera(
    val id: String,
    val kind: CameraKind,
    val location: LatLon,
    val endLocation: LatLon?,
    val road: String,
    val maxSpeedKmh: Int?,
    val axisDeg: Int?,
    val sense: AxisSense,
    val sources: Int,
)

/**
 * A stretch of road where the DGT says mobile radars MAY operate (the official report gives only a road and a
 * kilometre range). Never a point and never a live position. [line] is a rough drawing of the stretch and is empty
 * when the kilometre points could not be placed on the map; such a zone is only text.
 */
data class MobileZone(
    val id: String,
    val road: String,
    val province: String,
    val kmFromMeters: Int,
    val kmToMeters: Int,
    val line: List<LatLon>,
) {
    val hasGeometry: Boolean get() = line.size >= 2
}

/** The content of one camera data file. */
data class CameraDataset(
    val generatedAtEpochSeconds: Long,
    val sourceFlags: Int,
    val fixed: List<SpeedCamera>,
    val sections: List<SpeedCamera>,
    val zones: List<MobileZone>,
) {
    companion object {
        val EMPTY = CameraDataset(0L, 0, emptyList(), emptyList(), emptyList())
    }
}
