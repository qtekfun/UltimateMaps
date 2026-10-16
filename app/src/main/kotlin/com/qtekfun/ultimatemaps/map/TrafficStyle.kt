package com.qtekfun.ultimatemaps.map

/** How the live-traffic lines look: green flowing, amber slow, red heavy, dark red closed; thin when flowing so jams stand out. */
object TrafficStyle {
    const val SOURCE = "traffic-src"
    const val LAYER = "traffic-line"
    const val LEVEL = "level"

    fun colorOf(level: Int): Int = when (level) {
        1 -> 0xFFFF9F0A.toInt()
        2 -> 0xFFFF3B30.toInt()
        3 -> 0xFF8E0000.toInt()
        else -> 0xFF30B454.toInt()
    }

    /** Line width in dp by zoom for a flowing road; slower levels add [extra] so they read at a glance. */
    val WIDTH_STOPS = listOf(9f to 1.2f, 12f to 2.2f, 15f to 4.5f, 18f to 8f)
}
