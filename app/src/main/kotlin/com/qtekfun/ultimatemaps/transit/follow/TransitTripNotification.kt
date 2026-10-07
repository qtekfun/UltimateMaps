package com.qtekfun.ultimatemaps.transit.follow

import android.content.res.Resources
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripState
import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import com.qtekfun.ultimatemaps.nav.LiveUpdatePlan
import com.qtekfun.ultimatemaps.nav.LiveUpdatePolicy
import com.qtekfun.ultimatemaps.nav.NavNotificationContent
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the text of the transit trip notification. Never contains a position, only stop and line names and times. */
object TransitTripNotificationTexts {
    /** Headline = the banner instruction, second line = its detail and the plan chip ("About 3 min behind plan"). */
    fun of(res: Resources, trip: TransitTripState?, locale: Locale): NavNotificationContent {
        val app = res.getString(R.string.app_name)
        if (trip == null) return NavNotificationContent(app, res.getString(R.string.trip_resumed))
        val s = trip.follow
        val text = TransitTripTexts.instruction(res, s, ZoneId.of(trip.zoneId), locale)
        val extra = listOfNotNull(
            text.detail,
            TransitTripTexts.connection(res, s) ?: TransitTripTexts.signal(res, s),
            TransitTripTexts.planChip(res, s),
        ).joinToString(" · ")
        return NavNotificationContent(text.title, extra.ifBlank { res.getString(R.string.trip_scheduled_note) })
    }

    /** Changes only when something worth redrawing changes (throttle key). */
    fun key(trip: TransitTripState?): String {
        val s = trip?.follow ?: return "none"
        return "${s.phase}|${s.legIndex}|${s.nextStopIndex}|${s.plan}|${s.planMinutes}|${s.connection}|${s.basis}|${s.canReplan}"
    }
}

/** The Android 16+ status-bar chip (Live Update) of the transit trip. Pure; the chip fits about 7 characters. */
object TransitTripLiveUpdate {
    /**
     * The plan to promote with, or null when the notification must stay a normal one: the user turned the chip off, the device
     * is older than Android 16, there is no trip or it is over. Progress is the share of the scheduled time that has passed.
     */
    fun plan(
        enabled: Boolean,
        sdkInt: Int,
        trip: TransitTripState?,
        res: Resources,
        units: DistanceUnits,
        locale: Locale,
        nowSec: Long,
    ): LiveUpdatePlan? {
        if (!enabled || sdkInt < LiveUpdatePolicy.MIN_SDK || trip == null || trip.follow.phase == FollowPhase.ARRIVED) return null
        val chip = TransitTripTexts.chip(res, trip.follow, locale, units, nowSec) ?: return null
        val it = trip.itinerary
        val total = (it.arriveAt - it.departAt).coerceAtLeast(1)
        val percent = ((nowSec - it.departAt).toDouble() / total * 100.0).roundToInt().coerceIn(0, 100)
        return LiveUpdatePlan(chip.ifBlank { res.getString(R.string.trip_chip_no_gps) }, percent)
    }
}
