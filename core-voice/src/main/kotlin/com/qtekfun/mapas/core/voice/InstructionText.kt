package com.qtekfun.mapas.core.voice

import com.qtekfun.mapas.core.nav.Announcement
import com.qtekfun.mapas.core.nav.AnnouncementKind
import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import java.util.Locale

/** Sentences that are not tied to a maneuver. */
enum class VoiceMessage { RECALCULATING, OFF_ROUTE, ARRIVED, STOP_REACHED }

/**
 * Turns navigation events into the sentence the TTS engine reads (pure functions, Spanish and English; the
 * cases are in `InstructionTextTest`). Rules that apply everywhere:
 *
 * - A missing street name (null, blank, or the literal "null" the native core produced in the first device
 *   test) is simply left out: "gira a la izquierda", never "gira a la izquierda en null".
 * - [AnnouncementKind.FAR] and [AnnouncementKind.NEAR] start with the rounded distance ("En 300 metros, ..."),
 *   [AnnouncementKind.NOW] with "Ahora, ...". A FAR/NEAR prompt for a maneuver under 20 m away says "Ahora" too.
 * - Roundabouts read "En la rotonda, toma la segunda salida hacia ..." (no "Ahora": the roundabout is the
 *   cue); past the tenth exit it says "la salida número 11".
 * - Lane advice ("Mantente en el carril de la izquierda") is added to NEAR prompts only: far away it is too early
 *   to act on and at NOW there is no time left.
 */
object InstructionText {
    fun of(a: Announcement, units: DistanceUnits, language: VoiceLanguage): String {
        val w = words(language)
        val m = a.maneuver
        val street = cleanStreet(m.streetName)
        if (m.type == TurnType.DEPART) return capitalize(w.depart(street), language)

        val rounded = DistanceRounding.round(a.meters, units)
        val immediate = a.kind == AnnouncementKind.NOW || a.meters < IMMEDIATE_UNDER_METERS
        val lead = if (immediate) null else w.distance(rounded)

        val body = if (m.type.isArrival()) w.arrive(m.type, immediate, lead) else {
            val action = w.action(m, street)
            val withLead = when {
                lead != null -> "$lead, $action"
                m.type == TurnType.ROUNDABOUT_ENTER -> action
                else -> "${w.now}, $action"
            }
            capitalize(withLead, language)
        }
        val lanes = if (a.kind == AnnouncementKind.NEAR && !m.type.isArrival()) w.lanes(m.lanes) else null
        return if (lanes == null) body else "$body. $lanes"
    }

    fun of(message: VoiceMessage, language: VoiceLanguage): String = words(language).message(message)

    /** What "Test voice" says: a real prompt, so the units and the language can be checked too. */
    fun test(units: DistanceUnits, language: VoiceLanguage): String {
        val sample = of(Announcement(Maneuver(0, TurnType.LEFT), AnnouncementKind.FAR, 300), units, language)
        return "${words(language).testIntro}. $sample"
    }

    /** The street to say, or null when there is none worth saying. */
    internal fun cleanStreet(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true) || s.equals("none", ignoreCase = true)) null else s
    }

    private fun TurnType.isArrival() = this == TurnType.ARRIVE || this == TurnType.ARRIVE_LEFT || this == TurnType.ARRIVE_RIGHT

    private fun capitalize(s: String, language: VoiceLanguage): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(language.locale) + s.substring(1)

    private const val IMMEDIATE_UNDER_METERS = 20

    private fun words(language: VoiceLanguage): Words = if (language == VoiceLanguage.ES) Spanish else English

    // ------------------------------------------------------------------ Lanes

    /** Where the recommended lanes are: leftmost, rightmost, or a single one in the middle. Null when it says nothing. */
    internal sealed interface LaneAdvice {
        data class Left(val count: Int) : LaneAdvice
        data class Right(val count: Int) : LaneAdvice
        data object Center : LaneAdvice
        data class FromLeft(val position: Int) : LaneAdvice // 1-based
    }

    internal fun laneAdvice(lanes: List<Lane>): LaneAdvice? {
        val n = lanes.size
        if (n < 2) return null
        val rec = lanes.indices.filter { lanes[it].recommended }
        if (rec.isEmpty() || rec.size == n) return null
        if (rec.last() - rec.first() + 1 != rec.size) return null // not contiguous: nothing simple to say
        return when {
            rec.first() == 0 -> LaneAdvice.Left(rec.size)
            rec.last() == n - 1 -> LaneAdvice.Right(rec.size)
            rec.size == 1 && n % 2 == 1 && rec[0] == n / 2 -> LaneAdvice.Center
            rec.size == 1 && rec[0] < MAX_ORDINAL_LANE -> LaneAdvice.FromLeft(rec[0] + 1)
            else -> null
        }
    }

    private const val MAX_ORDINAL_LANE = 5

    // ------------------------------------------------------------------ Languages

    private interface Words {
        val now: String
        val testIntro: String
        fun distance(d: SpokenDistance): String
        fun depart(street: String?): String
        fun action(m: Maneuver, street: String?): String
        fun arrive(type: TurnType, immediate: Boolean, lead: String?): String
        fun lanes(lanes: List<Lane>): String?
        fun message(m: VoiceMessage): String
    }

    private object Spanish : Words {
        private val es = Locale.forLanguageTag("es")
        override val now = "ahora"
        override val testIntro = "Voz de navegación activada"

        override fun distance(d: SpokenDistance): String {
            val number = number(d.value)
            val unit = when (d.unit) {
                SpokenDistance.Unit.METER -> "metros"
                SpokenDistance.Unit.KILOMETER -> if (d.value == 1.0) "kilómetro" else "kilómetros"
                SpokenDistance.Unit.FOOT -> "pies"
                SpokenDistance.Unit.MILE -> if (d.value == 1.0) "milla" else "millas"
            }
            return "En $number $unit"
        }

        private fun number(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(es, "%.1f", v)

        override fun depart(street: String?) = if (street == null) "comienza la ruta" else "comienza la ruta por $street"

        override fun action(m: Maneuver, street: String?): String {
            val onto = street?.let { " en $it" }.orEmpty()
            val toward = street?.let { " hacia $it" }.orEmpty()
            return when (m.type) {
                TurnType.DEPART -> depart(street)
                TurnType.STRAIGHT -> "sigue recto" + street?.let { " por $it" }.orEmpty()
                TurnType.SLIGHT_RIGHT -> "gira ligeramente a la derecha$onto"
                TurnType.RIGHT -> "gira a la derecha$onto"
                TurnType.SHARP_RIGHT -> "gira cerrado a la derecha$onto"
                TurnType.SLIGHT_LEFT -> "gira ligeramente a la izquierda$onto"
                TurnType.LEFT -> "gira a la izquierda$onto"
                TurnType.SHARP_LEFT -> "gira cerrado a la izquierda$onto"
                TurnType.U_TURN_LEFT -> "haz un cambio de sentido a la izquierda"
                TurnType.U_TURN_RIGHT -> "haz un cambio de sentido a la derecha"
                TurnType.ROUNDABOUT_ENTER -> {
                    val exit = m.roundaboutExit
                    if (exit != null && exit > 0) "en la rotonda, toma ${exitOrdinal(exit)}$toward" else "entra en la rotonda"
                }
                TurnType.ROUNDABOUT_LEAVE -> "sal de la rotonda$toward"
                TurnType.EXIT_LEFT -> "toma la salida de la izquierda$toward"
                TurnType.EXIT_RIGHT -> "toma la salida de la derecha$toward"
                TurnType.MERGE -> if (street == null) "incorpórate a la vía" else "incorpórate a $street"
                TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT -> arrive(m.type, true, null)
            }
        }

        private val ordinals = listOf("la primera", "la segunda", "la tercera", "la cuarta", "la quinta", "la sexta", "la séptima", "la octava", "la novena", "la décima")

        private fun exitOrdinal(n: Int) = if (n in 1..ordinals.size) "${ordinals[n - 1]} salida" else "la salida número $n"

        override fun arrive(type: TurnType, immediate: Boolean, lead: String?): String {
            val side = when (type) {
                TurnType.ARRIVE_LEFT -> " a la izquierda"
                TurnType.ARRIVE_RIGHT -> " a la derecha"
                else -> ""
            }
            if (immediate) return if (side.isEmpty()) "Has llegado a tu destino" else "Has llegado a tu destino,$side"
            return if (side.isEmpty()) "$lead, llegarás a tu destino" else "$lead, tu destino estará$side"
        }

        override fun lanes(lanes: List<Lane>): String? = when (val a = laneAdvice(lanes)) {
            null -> null
            is LaneAdvice.Left -> if (a.count == 1) "Mantente en el carril de la izquierda" else "Mantente en los ${count(a.count)} carriles de la izquierda"
            is LaneAdvice.Right -> if (a.count == 1) "Mantente en el carril de la derecha" else "Mantente en los ${count(a.count)} carriles de la derecha"
            LaneAdvice.Center -> "Mantente en el carril central"
            is LaneAdvice.FromLeft -> "Mantente en el ${ordinalMasc(a.position)} carril por la izquierda"
        }

        private fun count(n: Int) = when (n) { 2 -> "dos"; 3 -> "tres"; 4 -> "cuatro"; else -> n.toString() }
        private fun ordinalMasc(n: Int) = when (n) { 2 -> "segundo"; 3 -> "tercer"; 4 -> "cuarto"; else -> "quinto" }

        override fun message(m: VoiceMessage) = when (m) {
            VoiceMessage.RECALCULATING -> "Recalculando"
            VoiceMessage.OFF_ROUTE -> "Has salido de la ruta"
            VoiceMessage.ARRIVED -> "Has llegado a tu destino"
            VoiceMessage.STOP_REACHED -> "Parada alcanzada"
        }
    }

    private object English : Words {
        override val now = "now"
        override val testIntro = "Navigation voice is on"

        override fun distance(d: SpokenDistance): String {
            val number = if (d.isWhole) d.value.toLong().toString() else String.format(Locale.ROOT, "%.1f", d.value)
            val unit = when (d.unit) {
                SpokenDistance.Unit.METER -> "meters"
                SpokenDistance.Unit.KILOMETER -> if (d.value == 1.0) "kilometer" else "kilometers"
                SpokenDistance.Unit.FOOT -> "feet"
                SpokenDistance.Unit.MILE -> if (d.value == 1.0) "mile" else "miles"
            }
            return "In $number $unit"
        }

        override fun depart(street: String?) = if (street == null) "start the route" else "start the route on $street"

        override fun action(m: Maneuver, street: String?): String {
            val onto = street?.let { " onto $it" }.orEmpty()
            val toward = street?.let { " toward $it" }.orEmpty()
            return when (m.type) {
                TurnType.DEPART -> depart(street)
                TurnType.STRAIGHT -> "continue straight" + street?.let { " on $it" }.orEmpty()
                TurnType.SLIGHT_RIGHT -> "bear slightly right$onto"
                TurnType.RIGHT -> "turn right$onto"
                TurnType.SHARP_RIGHT -> "make a sharp right$onto"
                TurnType.SLIGHT_LEFT -> "bear slightly left$onto"
                TurnType.LEFT -> "turn left$onto"
                TurnType.SHARP_LEFT -> "make a sharp left$onto"
                TurnType.U_TURN_LEFT -> "make a U-turn to the left"
                TurnType.U_TURN_RIGHT -> "make a U-turn to the right"
                TurnType.ROUNDABOUT_ENTER -> {
                    val exit = m.roundaboutExit
                    if (exit != null && exit > 0) "at the roundabout, take ${exitOrdinal(exit)}$toward" else "enter the roundabout"
                }
                TurnType.ROUNDABOUT_LEAVE -> "exit the roundabout$onto"
                TurnType.EXIT_LEFT -> "take the exit on the left$toward"
                TurnType.EXIT_RIGHT -> "take the exit on the right$toward"
                TurnType.MERGE -> if (street == null) "merge" else "merge onto $street"
                TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT -> arrive(m.type, true, null)
            }
        }

        private val ordinals = listOf("the first", "the second", "the third", "the fourth", "the fifth", "the sixth", "the seventh", "the eighth", "the ninth", "the tenth")

        private fun exitOrdinal(n: Int) = if (n in 1..ordinals.size) "${ordinals[n - 1]} exit" else "exit number $n"

        override fun arrive(type: TurnType, immediate: Boolean, lead: String?): String {
            val side = when (type) {
                TurnType.ARRIVE_LEFT -> "on the left"
                TurnType.ARRIVE_RIGHT -> "on the right"
                else -> null
            }
            if (immediate) return if (side == null) "You have arrived at your destination" else "You have arrived at your destination, $side"
            return if (side == null) "$lead, you will arrive at your destination" else "$lead, your destination will be $side"
        }

        override fun lanes(lanes: List<Lane>): String? = when (val a = laneAdvice(lanes)) {
            null -> null
            is LaneAdvice.Left -> if (a.count == 1) "Keep in the left lane" else "Keep in the left ${a.count} lanes"
            is LaneAdvice.Right -> if (a.count == 1) "Keep in the right lane" else "Keep in the right ${a.count} lanes"
            LaneAdvice.Center -> "Keep in the middle lane"
            is LaneAdvice.FromLeft -> "Keep in the ${ordinalWord(a.position)} lane from the left"
        }

        private fun ordinalWord(n: Int) = when (n) { 2 -> "second"; 3 -> "third"; 4 -> "fourth"; else -> "fifth" }

        override fun message(m: VoiceMessage) = when (m) {
            VoiceMessage.RECALCULATING -> "Recalculating"
            VoiceMessage.OFF_ROUTE -> "You have left the route"
            VoiceMessage.ARRIVED -> "You have arrived at your destination"
            VoiceMessage.STOP_REACHED -> "Stop reached"
        }
    }
}

/**
 * Whether a prompt is worth speaking when the user chose "only important prompts": real turns, exits, roundabouts,
 * U-turns and the arrival, but not "continue straight", slight bends, merges or leaving a roundabout, and nothing
 * from far away except exits and roundabouts (where missing the prompt costs a detour).
 */
fun Announcement.isImportant(): Boolean {
    val t = maneuver.type
    val farToo = t == TurnType.EXIT_LEFT || t == TurnType.EXIT_RIGHT || t == TurnType.ROUNDABOUT_ENTER
    if (kind == AnnouncementKind.FAR && !farToo) return false
    return when (t) {
        TurnType.STRAIGHT, TurnType.SLIGHT_LEFT, TurnType.SLIGHT_RIGHT, TurnType.MERGE, TurnType.ROUNDABOUT_LEAVE, TurnType.DEPART -> false
        else -> true
    }
}
