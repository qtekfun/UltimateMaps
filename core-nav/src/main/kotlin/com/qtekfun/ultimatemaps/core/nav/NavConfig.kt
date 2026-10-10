package com.qtekfun.ultimatemaps.core.nav

/**
 * A distance threshold that scales with speed: `clamp(speed * seconds, minMeters, maxMeters)`.
 * Walking falls back to [minMeters]; a motorway to roughly `speed * seconds`.
 */
data class DistanceBand(val seconds: Double, val minMeters: Double, val maxMeters: Double) {
    fun metersAt(speedMps: Double): Double = (speedMps * seconds).coerceIn(minMeters, maxMeters)
}

/** When to speak. Three levels per maneuver, each fired at most once, in order of urgency. */
data class AnnouncementConfig(
    val far: DistanceBand = DistanceBand(seconds = 30.0, minMeters = 200.0, maxMeters = 2000.0),
    val near: DistanceBand = DistanceBand(seconds = 10.0, minMeters = 60.0, maxMeters = 400.0),
    val now: DistanceBand = DistanceBand(seconds = 3.0, minMeters = 20.0, maxMeters = 80.0),
)

/** Retry policy of the reroute coroutine. */
data class RerouteConfig(
    /** Attempts per cycle (the first one has no delay). */
    val maxAttempts: Int = 3,
    val retryDelayMillis: Long = 2_000L,
    /** Wait after a failed cycle before trying again while still off route. */
    val cooldownMillis: Long = 8_000L,
    /** A new route must start within this distance of the user... */
    val maxStartDistanceMeters: Double = 500.0,
    /** ...and end within this distance of the old destination; otherwise it is discarded as an engine glitch. */
    val maxEndDistanceMeters: Double = 500.0,
)

/** All thresholds of the follower. See `docs/phase2/following.md` for the reason behind each default. */
data class NavConfig(
    // Off route
    val offRouteMinMeters: Double = 30.0,
    val accuracyFactor: Double = 2.0,
    val offRouteFixes: Int = 3,
    val offRouteMinMillis: Long = 2_000L,
    val offRouteMaxMillis: Long = 8_000L,
    val farFactor: Double = 2.5,
    val farFixes: Int = 2,
    /**
     * A turn off the route: moving at least [divergeMinSpeedMps], heading more than [divergeDegrees] away from the route
     * and already past [divergeBand] of the off-route threshold for [divergeFixes] fixes in a row is a decision, not noise.
     */
    val divergeDegrees: Double = 60.0,
    val divergeMinSpeedMps: Double = 5.0,
    val divergeBand: Double = 0.8,
    val divergeFixes: Int = 2,
    val onRouteBand: Double = 0.7,
    val wrongWayDegrees: Double = 135.0,
    val wrongWayMinSpeedMps: Double = 3.0,
    // Fix filtering
    val maxUsableAccuracyMeters: Float = 100f,
    val defaultAccuracyMeters: Float = 10f,
    // Search window along the route
    val firstFixWindowMeters: Double = 2_000.0,
    val windowBaseMeters: Double = 80.0,
    val windowMaxMeters: Double = 5_000.0,
    val backToleranceMeters: Double = 40.0,
    val headingPenaltyMeters: Double = 25.0,
    val headingMinSpeedMps: Double = 1.5,
    // Progress filter
    val stationarySpeedMps: Double = 0.5,
    // Arrival
    val arrivalRadiusMeters: Double = 25.0,
    val arrivalStopRadiusMeters: Double = 60.0,
    val arrivalStopSpeedMps: Double = 0.8,
    val arrivalStopMillis: Long = 8_000L,
    // Signal loss
    val signalLossMillis: Long = 5_000L,
    val estimateMaxMillis: Long = 30_000L,
    /** Tunnel-aware dead reckoning (applies only where a tunnel span is known). */
    val tunnel: TunnelConfig = TunnelConfig(),
    // Speed limit
    val speedToleranceKmh: Double = 0.0,
    val speedHysteresisKmh: Double = 2.0,
    // Voice
    val announcements: AnnouncementConfig = AnnouncementConfig(),
    // Reroute and session loop
    val reroute: RerouteConfig = RerouteConfig(),
    val tickMillis: Long = 1_000L,
)
