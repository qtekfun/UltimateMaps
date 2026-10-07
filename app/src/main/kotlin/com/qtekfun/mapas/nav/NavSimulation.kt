package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.nav.RouteSimulator
import com.qtekfun.mapas.core.routing.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Route simulation (RF-05): walks [RoutePlan.geometry] with a [RouteSimulator] and feeds the fixes to the navigation
 * through [source], one per second of real time, at an adjustable speed (the fixes move farther per second; a faster
 * simulation is a faster vehicle, so the speed shown and the speed-limit warning follow it). Fixes are stamped with
 * [clock], the clock the follower uses. The real location is never touched ([SwitchableLocationSource] switches it off).
 */
class NavSimulation(
    private val scope: CoroutineScope,
    private val source: SwitchableLocationSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 1_000L,
) {
    private var job: Job? = null
    private var plan: RoutePlan? = null

    @Volatile
    var speedKmh: Int = DEFAULT_SPEED_KMH
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts (or restarts) the walk at [startAlongMeters] of [plan]. Switches the source to simulated fixes. */
    fun start(plan: RoutePlan, startAlongMeters: Double = 0.0, speedKmh: Int = this.speedKmh) {
        stopJob()
        this.plan = plan
        this.speedKmh = speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        source.beginSimulation()
        val simulator = RouteSimulator(
            plan.geometry, this.speedKmh / 3.6, intervalMillis = tickMillis, startAlongMeters = startAlongMeters,
        )
        job = scope.launch {
            for (fix in simulator.fixes()) {
                source.emitSimulated(fix.copy(timeMillis = clock()))
                delay(tickMillis)
            }
        }
    }

    /** Changes the speed from where the simulated vehicle is now ([alongMeters]). */
    fun setSpeed(speedKmh: Int, alongMeters: Double) {
        val p = plan ?: return
        if (!isRunning) {
            this.speedKmh = speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
            return
        }
        start(p, alongMeters, speedKmh)
    }

    /** The next preset above / below the current speed (unchanged at the ends). */
    fun faster(alongMeters: Double) = setSpeed(PRESETS.firstOrNull { it > speedKmh } ?: speedKmh, alongMeters)

    fun slower(alongMeters: Double) = setSpeed(PRESETS.lastOrNull { it < speedKmh } ?: speedKmh, alongMeters)

    /** Stops feeding fixes. Does not switch the source back: the owner does that when the navigation ends. */
    fun stop() {
        stopJob()
        plan = null
    }

    private fun stopJob() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_SPEED_KMH = 50
        const val MIN_SPEED_KMH = 5
        const val MAX_SPEED_KMH = 200
        val PRESETS = listOf(10, 30, 50, 90, 130)
    }
}
