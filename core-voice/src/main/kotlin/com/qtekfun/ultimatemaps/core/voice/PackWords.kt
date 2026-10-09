package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.routing.ExitSign
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import java.util.Locale

/** The words of a language that is a [VoicePack]: the same rules as the Spanish and English code, with the sentences from the pack. */
internal class PackWords(private val p: InstructionPhrases, private val locale: Locale) : InstructionText.Words {
    override val now: String get() = p.now
    override val testIntro: String get() = p.testIntro

    override fun distance(d: SpokenDistance): String {
        val number = if (d.isWhole) d.value.toLong().toString() else String.format(locale, "%.1f", d.value)
        val singular = d.value == 1.0 || (p.singularBelowTwo && d.value < 2.0)
        val unit = when (d.unit) {
            SpokenDistance.Unit.METER -> p.meters
            SpokenDistance.Unit.KILOMETER -> if (singular) p.kilometerOne else p.kilometers
            SpokenDistance.Unit.FOOT -> p.feet
            SpokenDistance.Unit.MILE -> if (singular) p.mileOne else p.miles
        }
        return p.distanceLead.fill("n" to number, "u" to unit)
    }

    override fun depart(street: String?): String = p.depart.of(street)

    override fun action(m: Maneuver, street: String?): String =
        InstructionText.exitSide(m)?.let { exit(m, it, street) } ?: ordinary(m, street)

    private fun exit(m: Maneuver, side: InstructionText.ExitSide, street: String?): String {
        val ref = ExitSign.spokenExitRef(m)
        val base = if (ref != null) {
            when (side) {
                InstructionText.ExitSide.LEFT -> p.exitRefLeft
                InstructionText.ExitSide.RIGHT -> p.exitRefRight
                InstructionText.ExitSide.NONE -> p.exitRefStraight
            }.fill("ref" to ref)
        } else {
            when (side) {
                InstructionText.ExitSide.LEFT -> p.exitLeft
                InstructionText.ExitSide.RIGHT -> p.exitRight
                InstructionText.ExitSide.NONE -> p.exitStraight
            }
        }
        return base + toward(ExitSign.spokenLabel(m, p.and) ?: street)
    }

    private fun toward(where: String?): String = where?.let { p.toward.fill("s" to it) }.orEmpty()

    private fun ordinary(m: Maneuver, street: String?): String = when (m.type) {
        TurnType.DEPART -> depart(street)
        TurnType.STRAIGHT -> p.straight.of(street)
        TurnType.SLIGHT_RIGHT -> p.slightRight.of(street)
        TurnType.RIGHT -> p.right.of(street)
        TurnType.SHARP_RIGHT -> p.sharpRight.of(street)
        TurnType.SLIGHT_LEFT -> p.slightLeft.of(street)
        TurnType.LEFT -> p.left.of(street)
        TurnType.SHARP_LEFT -> p.sharpLeft.of(street)
        TurnType.U_TURN_LEFT -> p.uTurnLeft
        TurnType.U_TURN_RIGHT -> p.uTurnRight
        TurnType.ROUNDABOUT_ENTER -> {
            val exit = m.roundaboutExit
            if (exit != null && exit > 0) p.roundaboutTake.fill("x" to exitOrdinal(exit)) + toward(street) else p.roundaboutEnter
        }
        TurnType.ROUNDABOUT_LEAVE -> p.roundaboutLeave.of(street)
        TurnType.EXIT_LEFT, TurnType.EXIT_RIGHT ->
            exit(m, if (m.type == TurnType.EXIT_LEFT) InstructionText.ExitSide.LEFT else InstructionText.ExitSide.RIGHT, street)
        TurnType.MERGE -> p.merge.of(street)
        TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT -> arrive(m.type, true, null)
    }

    private fun exitOrdinal(n: Int): String =
        if (n in 1..p.exitOrdinals.size) p.exitOrdinals[n - 1] else p.exitNumber.fill("n" to n.toString())

    override fun arrive(type: TurnType, immediate: Boolean, lead: String?): String {
        val side = when (type) {
            TurnType.ARRIVE_LEFT -> p.sideLeft
            TurnType.ARRIVE_RIGHT -> p.sideRight
            else -> null
        }
        val leadText = lead.orEmpty()
        return when {
            immediate && side == null -> p.arriveNow
            immediate -> p.arriveNowSide.fill("side" to side!!)
            side == null -> p.arriveSoon.fill("lead" to leadText)
            else -> p.arriveSoonSide.fill("lead" to leadText, "side" to side)
        }
    }

    override fun lanes(lanes: List<Lane>): String? = when (val a = InstructionText.laneAdvice(lanes)) {
        null -> null
        is InstructionText.LaneAdvice.Left -> if (a.count == 1) p.laneLeftOne else p.laneLeftMany.fill("n" to a.count.toString())
        is InstructionText.LaneAdvice.Right -> if (a.count == 1) p.laneRightOne else p.laneRightMany.fill("n" to a.count.toString())
        InstructionText.LaneAdvice.Center -> p.laneCenter
        // The position is 2..5 (laneAdvice never asks for the first lane or past the fifth).
        is InstructionText.LaneAdvice.FromLeft -> p.laneFromLeft.fill("ord" to p.laneOrdinals[(a.position - 2).coerceIn(0, p.laneOrdinals.size - 1)])
    }

    override fun message(m: VoiceMessage): String = when (m) {
        VoiceMessage.RECALCULATING -> p.recalculating
        VoiceMessage.OFF_ROUTE -> p.offRoute
        VoiceMessage.ARRIVED -> p.arrived
        VoiceMessage.STOP_REACHED -> p.stopReached
    }
}
