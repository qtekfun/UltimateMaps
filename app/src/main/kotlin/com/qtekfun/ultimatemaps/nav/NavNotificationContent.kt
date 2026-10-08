package com.qtekfun.ultimatemaps.nav

import android.content.Context
import androidx.annotation.StringRes
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.nav.NavProblem
import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.routing.ExitSign
import com.qtekfun.ultimatemaps.core.routing.TurnType
import com.qtekfun.ultimatemaps.route.RouteFormat
import java.util.Locale

/** The two lines of the navigation notification. Never contains a position, only distances and street names. */
data class NavNotificationContent(val title: String, val text: String)

/** Builds the notification text from the navigation state; pure apart from resource lookup, so it is unit-tested. */
object NavNotificationTexts {
    @StringRes
    fun turnRes(type: TurnType): Int = when (type) {
        TurnType.DEPART -> R.string.nav_turn_depart
        TurnType.STRAIGHT -> R.string.nav_turn_straight
        TurnType.SLIGHT_RIGHT -> R.string.nav_turn_slight_right
        TurnType.RIGHT -> R.string.nav_turn_right
        TurnType.SHARP_RIGHT -> R.string.nav_turn_sharp_right
        TurnType.SLIGHT_LEFT -> R.string.nav_turn_slight_left
        TurnType.LEFT -> R.string.nav_turn_left
        TurnType.SHARP_LEFT -> R.string.nav_turn_sharp_left
        TurnType.U_TURN_LEFT, TurnType.U_TURN_RIGHT -> R.string.nav_turn_u_turn
        TurnType.ROUNDABOUT_ENTER -> R.string.nav_turn_roundabout_enter
        TurnType.ROUNDABOUT_LEAVE -> R.string.nav_turn_roundabout_leave
        TurnType.EXIT_LEFT -> R.string.nav_turn_exit_left
        TurnType.EXIT_RIGHT -> R.string.nav_turn_exit_right
        TurnType.MERGE -> R.string.nav_turn_merge
        TurnType.ARRIVE -> R.string.nav_turn_arrive
        TurnType.ARRIVE_LEFT -> R.string.nav_turn_arrive_left
        TurnType.ARRIVE_RIGHT -> R.string.nav_turn_arrive_right
    }

    fun of(context: Context, state: NavState?, problem: NavProblem?, locale: Locale = Locale.getDefault(), units: DistanceUnits = DistanceUnits.METRIC): NavNotificationContent {
        val app = context.getString(R.string.app_name)
        if (problem == NavProblem.LOCATION_PERMISSION) return NavNotificationContent(app, context.getString(R.string.nav_problem_permission))
        if (problem == NavProblem.LOCATION_DISABLED) return NavNotificationContent(app, context.getString(R.string.nav_problem_gps_off))
        if (state == null) return NavNotificationContent(app, context.getString(R.string.nav_resumed))
        val remaining = context.getString(
            R.string.nav_remaining, RouteFormat.distance(state.remainingMeters, locale, units), RouteFormat.duration(state.remainingSeconds),
        )
        return when (state.status) {
            NavStatus.ARRIVED -> NavNotificationContent(context.getString(R.string.nav_arrived), app)
            NavStatus.NO_SIGNAL -> NavNotificationContent(context.getString(R.string.nav_waiting_gps), remaining)
            NavStatus.OFF_ROUTE, NavStatus.REROUTING -> NavNotificationContent(context.getString(R.string.nav_recalculating), remaining)
            NavStatus.ON_ROUTE -> {
                val next = state.nextManeuver ?: return NavNotificationContent(app, remaining)
                val turn = context.getString(turnRes(next.maneuver.type))
                val street = next.maneuver.streetName?.takeIf { it.isNotBlank() }
                val sign = if (ExitSign.isExit(next.maneuver)) ExitSign.label(next.maneuver) else null
                val exitNumber = ExitSign.ref(next.maneuver)?.let { context.getString(R.string.nav_exit_badge, it) }
                val action = when {
                    sign != null -> context.getString(R.string.nav_exit_toward, turn, sign)
                    street != null -> context.getString(R.string.nav_onto_street, turn, street)
                    else -> turn
                }.let { if (exitNumber != null) context.getString(R.string.nav_exit_then, exitNumber, it) else it }
                NavNotificationContent(
                    context.getString(R.string.nav_in_distance, RouteFormat.distance(next.distanceMeters.coerceAtLeast(0.0), locale, units), action),
                    remaining,
                )
            }
        }
    }
}
