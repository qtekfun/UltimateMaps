package com.qtekfun.ultimatemaps.core.routing

/**
 * What a motorway exit maneuver says about itself, from the optional exit fields of [Maneuver]. Pure functions shared
 * by the banner and the voice. Nothing is ever invented: with no data every function returns null/empty and the
 * callers keep their old text.
 */
object ExitSign {
    private const val MAX_PARTS = 2
    private val SEPARATORS = Regex("[;,]")
    private val DIGIT_LETTER_BOUNDARY = Regex("(?<=\\d)(?=\\p{L})|(?<=\\p{L})(?=\\d)")
    private val SPACES = Regex("\\s+")

    /** Exit signs make sense on every maneuver except roundabouts, the start and the arrival. */
    fun applies(m: Maneuver): Boolean = when (m.type) {
        TurnType.ROUNDABOUT_ENTER, TurnType.ROUNDABOUT_LEAVE, TurnType.DEPART,
        TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT -> false
        else -> true
    }

    /** A real exit type, or any other turn or bend the map gives an exit number to (a ramp classified as a slight turn). */
    fun isExit(m: Maneuver): Boolean = m.type == TurnType.EXIT_LEFT || m.type == TurnType.EXIT_RIGHT || ref(m) != null

    /** The exit number ("23", "12A"), or null. */
    fun ref(m: Maneuver): String? = if (applies(m)) clean(m.exitRef) else null

    /** Roads the ramp leads to, at most two ("A-2", "M-40"); empty when unknown. */
    fun towardRoads(m: Maneuver): List<String> = if (applies(m)) parts(m.towardRef) else emptyList()

    /** Places signposted on the ramp, at most two; empty when unknown. */
    fun towardPlaces(m: Maneuver): List<String> = if (applies(m)) parts(m.towardName) else emptyList()

    /** True when there is anything exit related to show for [m]. */
    fun has(m: Maneuver): Boolean = ref(m) != null || towardRoads(m).isNotEmpty() || towardPlaces(m).isNotEmpty()

    /** The text of the sign for the screen: "A-2 · Alcalá de Henares", "A-2" or "Alcalá de Henares"; null when unknown. */
    fun label(m: Maneuver): String? {
        val roads = towardRoads(m).joinToString(" / ")
        val places = towardPlaces(m).joinToString(", ")
        return listOf(roads, places).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    }

    /**
     * The same for a speech engine: "A 2, Alcalá de Henares". A hyphen in a ref is read as "dash" or skipped, so it
     * becomes a space, and "12A" becomes "12 A". [and] joins two roads or two places.
     */
    fun spokenLabel(m: Maneuver, and: String): String? {
        val roads = towardRoads(m).map(::spokenRef).joinToString(" $and ")
        val places = towardPlaces(m).joinToString(" $and ")
        return listOf(roads, places).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    /** The exit number as the speech engine should read it: "23", "12 A". */
    fun spokenExitRef(m: Maneuver): String? = ref(m)?.let(::spokenRef)

    internal fun spokenRef(ref: String): String =
        ref.replace('-', ' ').replace(DIGIT_LETTER_BOUNDARY, " ").replace(SPACES, " ").trim()

    private fun clean(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true) || s.equals("none", ignoreCase = true)) null else s
    }

    private fun parts(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATORS).mapNotNull { clean(it) }.distinct().take(MAX_PARTS)
}
