package com.qtekfun.ultimatemaps.core.transit.follow

/**
 * The follower's local log, for the About screen's diagnostics (Settings, About, Diagnostics, Copy): one line for each change
 * of what the follower believes (phase, aboard, off plan, estimated), for each answer of the traveller to "I am on board", and a
 * heartbeat while aboard. A line has the state, the reason, the distance to the track, the fix accuracy and speed and the
 * progress; never a coordinate or a place name. Kept in memory only (the last [KEEP] lines), thread-safe, never sent anywhere.
 */
object TransitFollowDiagnostics {
    const val KEEP = 300

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun note(line: String) {
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
    }

    /** Newest first. */
    @Synchronized
    fun describe(): List<String> = lines.reversed()

    @Synchronized
    fun clear() = lines.clear()
}
