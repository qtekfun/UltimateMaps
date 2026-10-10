package com.qtekfun.ultimatemaps.core.cameras

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters about what the alert warner has seen, for the About screen's diagnostics when "alerts do not go off": whether
 * fixes reach it, from which source, why they were skipped and whether anything was near. Numbers and ages only: no
 * position, no target and no path is kept. Thread-safe (written by the location thread, read by the UI).
 */
class AlertStats {
    @Volatile var source: String = "unknown"

    /** Why the free-driving feed runs or not, set by whoever starts it. */
    @Volatile var freeFeed: String = "not evaluated yet"
    private val freeFixes = AtomicLong()
    private val routeFixes = AtomicLong()
    private val skippedSwitchesOff = AtomicLong()
    private val skippedSlow = AtomicLong()
    private val skippedNoHeading = AtomicLong()
    private val raised = AtomicLong()
    @Volatile private var lastFixAtMs = 0L
    @Volatile private var lastFixFree = true
    @Volatile private var lastSpeedKmh = Double.NaN
    @Volatile private var lastHadBearing = false
    @Volatile private var lastTargetsInRange = 0
    @Volatile private var lastAlertAtMs = 0L
    private var inRange = 0

    fun fix(free: Boolean, atMs: Long, speedMps: Float?, hadBearing: Boolean) {
        if (free) freeFixes.incrementAndGet() else routeFixes.incrementAndGet()
        lastFixAtMs = atMs
        lastFixFree = free
        lastSpeedKmh = speedMps?.let { it * 3.6 } ?: Double.NaN
        lastHadBearing = hadBearing
    }

    fun skippedSwitchesOff() { skippedSwitchesOff.incrementAndGet() }
    fun skippedSlow() { skippedSlow.incrementAndGet() }
    fun skippedNoHeading() { skippedNoHeading.incrementAndGet() }

    /** A new scan starts for a fix. */
    fun scanStart() { inRange = 0 }

    /** A target of an enabled category passed the geometry tests (ahead, in the cone or on the route). */
    fun targetInRange() { inRange++ }

    fun scanEnd() { lastTargetsInRange = inRange }

    @Volatile private var mapZoom = Double.NaN
    @Volatile private var mapPins = -1
    @Volatile private var mapLines = -1
    @Volatile private var dataFixed = -1
    @Volatile private var dataSections = 0
    @Volatile private var dataZones = 0

    /** The map layer's last decision: the zoom it saw, what it drew, and what the loaded data holds (numbers only). */
    fun mapLayer(zoom: Double, pins: Int, lines: Int, fixed: Int, sections: Int, zones: Int) {
        mapZoom = zoom
        mapPins = pins
        mapLines = lines
        dataFixed = fixed
        dataSections = sections
        dataZones = zones
    }

    fun alertRaised(atMs: Long) {
        raised.incrementAndGet()
        lastAlertAtMs = atMs
    }

    /** The lines for the diagnostics screen; ages are relative to [nowMs]. */
    fun describe(nowMs: Long): List<String> {
        fun age(t: Long) = if (t == 0L) "never" else "${(nowMs - t) / 1000} s ago"
        return listOf(
            "Position source: $source",
            "Free-driving feed: $freeFeed",
            "Fixes seen: ${freeFixes.get()} free driving, ${routeFixes.get()} on a route; last ${age(lastFixAtMs)}" +
                (if (lastFixAtMs != 0L) " (${if (lastFixFree) "free driving" else "route"}, " +
                    (if (lastSpeedKmh.isNaN()) "no speed" else "${lastSpeedKmh.toInt()} km/h") +
                    ", ${if (lastHadBearing) "with" else "without"} bearing)" else ""),
            "Skipped fixes: ${skippedSwitchesOff.get()} everything off, ${skippedSlow.get()} too slow, ${skippedNoHeading.get()} no direction",
            "Targets ahead at the last fix: $lastTargetsInRange",
            "Alerts raised: ${raised.get()}; last ${age(lastAlertAtMs)}",
            if (mapPins < 0) "Map layer: no viewport seen yet"
            else "Map layer: zoom ${"%.1f".format(mapZoom)} (draws from 8), drew $mapPins pins and $mapLines lines; data holds " +
                "$dataFixed fixed cameras, $dataSections sections, $dataZones zones",
        )
    }
}
