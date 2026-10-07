package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.LocationSource

/**
 * The location source of the navigation: the real one, unless a route simulation is running, in which case the
 * real source is switched off (a simulation never reads, uses or stores the real position) and only fixes passed
 * to [emitSimulated] reach the listener. The follower does not know the difference.
 */
class SwitchableLocationSource(private val real: LocationSource) : LocationSource {
    private val lock = Any()
    private var listener: LocationSource.Listener? = null
    private var simulatedLast: LocationFix? = null

    @Volatile
    var isSimulated: Boolean = false
        private set

    override fun lastKnown(): LocationFix? = if (isSimulated) synchronized(lock) { simulatedLast } else real.lastKnown()

    override fun start(listener: LocationSource.Listener) {
        synchronized(lock) {
            this.listener = listener
            if (!isSimulated) real.start(listener)
        }
    }

    override fun stop() {
        synchronized(lock) { listener = null }
        real.stop()
    }

    /** From now on only simulated fixes are delivered; the real source is stopped. */
    fun beginSimulation() {
        synchronized(lock) {
            isSimulated = true
            simulatedLast = null
        }
        real.stop()
    }

    /** Back to the real source (it starts again with the next [start]). */
    fun endSimulation() {
        synchronized(lock) {
            isSimulated = false
            simulatedLast = null
        }
    }

    /** Delivers [fix] as if it came from the receiver; ignored unless a simulation is active and listening. */
    fun emitSimulated(fix: LocationFix) {
        val l = synchronized(lock) {
            if (!isSimulated) return
            simulatedLast = fix
            listener
        }
        l?.onFix(fix)
    }
}
