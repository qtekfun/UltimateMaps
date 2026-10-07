package com.qtekfun.ultimatemaps.nav

import android.content.Intent
import android.content.res.Resources
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.route.RouteFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "I will arrive at about 18:40 (12 km, 15 min to go)." as plain text for the system share sheet. Only the arrival
 * time and the remaining distance and time go in it: no place name, no position and no link, and nothing is sent by
 * this app (the user picks the app that sends it). It is a snapshot, not live tracking.
 */
object EtaShare {
    /** Null when there is no estimate yet. */
    fun text(resources: Resources, ui: NavUi, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String? {
        val nav = ui.nav ?: return null
        if (ui.etaMillis <= 0) return null
        return resources.getString(
            R.string.nav_share_eta_text,
            NavFormat.clockTime(ui.etaMillis, locale, zone),
            RouteFormat.distance(nav.remainingMeters, locale),
            RouteFormat.duration(nav.remainingSeconds),
        )
    }

    /** The share-sheet intent for [text]. */
    fun intent(text: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
}
