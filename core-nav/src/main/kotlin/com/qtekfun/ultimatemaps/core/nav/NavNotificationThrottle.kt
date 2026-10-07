package com.qtekfun.ultimatemaps.core.nav

/**
 * Decides when the foreground notification is worth refreshing. The state changes about once per second, and
 * redrawing a notification that often wastes battery (a lot more so under battery saver). A change of the key
 * (new maneuver, status or problem) goes out at once, but never more than once per second.
 */
class NavNotificationThrottle {
    private var lastAt = Long.MIN_VALUE
    private var lastKey: String? = null

    fun shouldPost(nowMillis: Long, minGapMillis: Long, key: String, force: Boolean = false): Boolean {
        val gap = if (lastAt == Long.MIN_VALUE) Long.MAX_VALUE else nowMillis - lastAt
        val post = force || lastKey == null || gap < 0 ||
            (key != lastKey && gap >= MIN_GAP_MILLIS) || gap >= minGapMillis
        if (post) {
            lastAt = nowMillis
            lastKey = key
        }
        return post
    }

    private companion object {
        const val MIN_GAP_MILLIS = 1_000L
    }
}
