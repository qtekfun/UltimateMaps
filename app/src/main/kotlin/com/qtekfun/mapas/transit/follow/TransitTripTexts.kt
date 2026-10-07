package com.qtekfun.mapas.transit.follow

import android.content.res.Resources
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.transit.follow.ConnectionStatus
import com.qtekfun.mapas.core.transit.follow.FollowBasis
import com.qtekfun.mapas.core.transit.follow.FollowPhase
import com.qtekfun.mapas.core.transit.follow.FollowState
import com.qtekfun.mapas.core.transit.follow.PlanStatus
import com.qtekfun.mapas.route.RouteFormat
import com.qtekfun.mapas.transit.TransitFormat
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

/** What the banner and the notification say: a headline, an optional second line and the line chip text shown with them. */
data class TripText(val title: String, val detail: String?)

/**
 * Turns a follower state into words (resources only; the unit tests are in `TransitTripTextsTest`). Shared by the banner,
 * the notification and the status-bar chip, so they never disagree. Nothing here contains a position.
 */
object TransitTripTexts {
    fun instruction(res: Resources, s: FollowState, zone: ZoneId, locale: Locale): TripText {
        val line = s.line?.shortName.orEmpty()
        val head = s.headsign?.takeIf { it.isNotBlank() }
        val boardTime = s.boardAt?.let { TransitFormat.time(it, zone) }.orEmpty()
        return when (s.phase) {
            FollowPhase.ARRIVED -> TripText(res.getString(R.string.trip_arrived_title), null)
            FollowPhase.OFF_PLAN -> TripText(res.getString(R.string.trip_off_plan_title), res.getString(R.string.trip_off_plan_detail))
            FollowPhase.BEFORE_START, FollowPhase.FINAL_WALK -> TripText(walkTitle(res, s), walkDetail(res, s, zone, locale, thenBoard = s.phase == FollowPhase.BEFORE_START))
            FollowPhase.TRANSFER -> {
                val title = if (head != null) res.getString(R.string.trip_change_title, line, head) else res.getString(R.string.trip_change_title_plain, line)
                val meters = s.walkMeters
                val detail = if (meters != null && meters > MIN_WALK_TO_MENTION && s.targetName != null) {
                    res.getString(R.string.trip_change_detail, s.targetName, RouteFormat.distance(meters.toDouble(), locale), minutes(s.walkSeconds), boardTime)
                } else {
                    res.getString(R.string.trip_change_detail_here, s.targetName.orEmpty(), boardTime).trim(' ', '·')
                }
                TripText(title, detail)
            }
            FollowPhase.WAITING -> {
                val title = if (head != null) res.getString(R.string.trip_board_title, line, head) else res.getString(R.string.trip_board_title_plain, line)
                val left = s.secondsToBoard ?: 0L
                val detail = when {
                    left > 30 -> res.getString(R.string.trip_wait_detail, boardTime, max(1L, (left + 30) / 60).toInt())
                    left >= -30 -> res.getString(R.string.trip_wait_now, boardTime)
                    else -> res.getString(R.string.trip_wait_passed, boardTime)
                }
                TripText(title, detail)
            }
            FollowPhase.ON_BOARD -> TripText(
                res.getString(R.string.trip_next_stop, s.nextStopName.orEmpty()),
                res.getQuantityString(R.plurals.trip_get_off_in, s.stopsRemaining, s.stopsRemaining, s.alightName.orEmpty()),
            )
            FollowPhase.ALIGHT_NEXT -> TripText(
                res.getString(R.string.trip_get_off_next, s.alightName.orEmpty()),
                s.alightAt?.let { res.getString(R.string.trip_get_off_detail, TransitFormat.time(it, zone)) },
            )
        }
    }

    private fun walkTitle(res: Resources, s: FollowState): String =
        if (s.targetName != null) res.getString(R.string.trip_walk_to, s.targetName) else res.getString(R.string.trip_walk_to_destination)

    private fun walkDetail(res: Resources, s: FollowState, zone: ZoneId, locale: Locale, thenBoard: Boolean): String? {
        val meters = s.walkMeters ?: return null
        val walk = res.getString(R.string.trip_walk_detail, RouteFormat.distance(meters.toDouble(), locale), minutes(s.walkSeconds))
        val line = s.line?.shortName
        val at = s.boardAt
        return if (thenBoard && line != null && at != null) walk + " — " + res.getString(R.string.trip_then_board, line, TransitFormat.time(at, zone)) else walk
    }

    /** Whole minutes, at least one. */
    private fun minutes(seconds: Int?): Int = max(1, ((seconds ?: 0) + 30) / 60)

    /** Walks shorter than this are "change here": no distance worth saying. */
    private const val MIN_WALK_TO_MENTION = 60

    /** The plan chip: "On plan", "About 3 min behind plan"... Null when there is nothing to compare yet. */
    fun planChip(res: Resources, s: FollowState): String? {
        if (s.planOffsetSec == null && !s.estimated) return null
        return when (s.plan) {
            PlanStatus.ON_PLAN -> res.getString(if (s.estimated) R.string.trip_plan_on_estimated else R.string.trip_plan_on)
            PlanStatus.BEHIND -> res.getQuantityString(R.plurals.trip_plan_behind, s.planMinutes, s.planMinutes)
            PlanStatus.AHEAD -> res.getQuantityString(R.plurals.trip_plan_ahead, s.planMinutes, s.planMinutes)
        }
    }

    /** The signal line under the banner, or null when the position is fine. */
    fun signal(res: Resources, s: FollowState): String? = when {
        s.phase == FollowPhase.ARRIVED -> null
        s.basis == FollowBasis.ESTIMATED -> res.getString(R.string.trip_estimated)
        s.basis == FollowBasis.NO_SIGNAL -> res.getString(R.string.trip_no_gps)
        else -> null
    }

    /** The connection warning, or null when the next boarding is reachable. */
    fun connection(res: Resources, s: FollowState): String? {
        val line = s.connectionLine ?: return null
        return when (s.connection) {
            ConnectionStatus.AT_RISK -> if (s.phase == FollowPhase.WAITING) null else res.getString(R.string.trip_connection_risk, line)
            ConnectionStatus.MISSED -> res.getString(R.string.trip_connection_missed, line)
            else -> null
        }
    }

    /**
     * The status-bar chip text (about 7 characters fit): stops left while riding, "Get off" at the next stop, minutes to the
     * departure while waiting, the walking distance while walking. Never a position. Null when the trip is over.
     */
    fun chip(res: Resources, s: FollowState, locale: Locale, units: com.qtekfun.mapas.core.voice.DistanceUnits, now: Long): String? = when (s.phase) {
        FollowPhase.ARRIVED -> null
        FollowPhase.OFF_PLAN -> res.getString(R.string.trip_chip_replan)
        FollowPhase.ALIGHT_NEXT -> res.getString(R.string.trip_chip_get_off)
        FollowPhase.ON_BOARD -> res.getQuantityString(R.plurals.trip_chip_stops, s.stopsRemaining, s.stopsRemaining)
        FollowPhase.WAITING -> s.boardAt?.let { res.getString(R.string.trip_chip_minutes, max(0L, ((it - now + 30) / 60)).toInt()) }
        FollowPhase.BEFORE_START, FollowPhase.TRANSFER, FollowPhase.FINAL_WALK ->
            s.walkMeters?.let { com.qtekfun.mapas.nav.LiveUpdatePolicy.compactDistance(it.toDouble(), units, locale) } ?: res.getString(R.string.trip_chip_no_gps)
    }
}

