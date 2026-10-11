package com.qtekfun.ultimatemaps.map

/**
 * Lets one thing through at most every [minIntervalMillis]; the caller passes the clock (no real time here, so it is testable on a
 * virtual one). Used to cap how often the map's position marker is handed to the map library: every hand-over of a GeoJSON source
 * makes the library parse it again and place every label again, so doing it on each display frame costs more than the frame.
 */
class MinIntervalGate(private val minIntervalMillis: Long) {
    private var last = Long.MIN_VALUE

    /** True when at least the interval has passed since the last [pass] (or nothing passed yet). */
    fun ready(nowMillis: Long): Boolean = last == Long.MIN_VALUE || nowMillis - last >= minIntervalMillis || nowMillis < last

    /** Records that something went through at [nowMillis]. */
    fun pass(nowMillis: Long) {
        last = nowMillis
    }

    /** Milliseconds until the gate is ready again (0 when it is). */
    fun waitMillis(nowMillis: Long): Long = if (ready(nowMillis)) 0L else minIntervalMillis - (nowMillis - last)
}
