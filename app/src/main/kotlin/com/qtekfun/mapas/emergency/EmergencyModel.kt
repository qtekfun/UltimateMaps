package com.qtekfun.mapas.emergency

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.places.GeoShare
import java.util.Locale

/**
 * Text and intents of the emergency screen. Privacy: the position is only drawn on the screen and, when the user
 * taps Share, put into the system share sheet. It is never logged, stored or sent by the app itself.
 */
object EmergencyText {
    /** The coordinates the way they are read out on the phone, five decimals (about a metre). */
    fun coordinates(p: LatLon): String = "%.5f, %.5f".format(Locale.ROOT, p.lat, p.lon)

    /** [header], the coordinates and a `geo:` link that any maps app can open. */
    fun shareText(header: String, p: LatLon): String = "$header\n${coordinates(p)}\n${GeoShare.uri(p, null)}"
}

object EmergencyIntents {
    /** The European emergency number. Dialled through the dialer: the user presses the call button there. */
    const val NUMBER = "112"

    /** Opens the dialer with the number filled in (`ACTION_DIAL`): no CALL_PHONE permission is needed or requested. */
    fun dial(): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", NUMBER, null))

    /** Share sheet with plain text (no SMS permission: the user picks the app, and the recipient, there). */
    fun share(text: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
}

/** Observable state of the emergency screen. Written from the main thread only. */
class EmergencyState {
    var position by mutableStateOf<LatLon?>(null)
    var accuracyMeters by mutableStateOf<Float?>(null)

    /** False when the location permission is missing: the screen offers to ask for it. */
    var permissionGranted by mutableStateOf(true)

    /** False when the device has no location provider at all. */
    var providerAvailable by mutableStateOf(true)

    /** True after the dialer could not be opened (no phone app). */
    var dialFailed by mutableStateOf(false)
}

/**
 * Keeps [state] up to date from [source] while the screen is visible: the last known fix first (instantly), then
 * live fixes until [stop]. Nothing is recorded: the fix only replaces the one shown.
 */
class EmergencyController(
    private val source: LocationSource,
    private val hasPermission: () -> Boolean,
    private val providerAvailable: () -> Boolean = { true },
) {
    val state = EmergencyState()

    fun start() {
        state.providerAvailable = providerAvailable()
        state.permissionGranted = hasPermission()
        if (!state.permissionGranted || !state.providerAvailable) return
        source.lastKnown()?.let(::show)
        source.start(::show)
    }

    fun stop() = source.stop()

    private fun show(fix: com.qtekfun.mapas.core.map.LocationFix) {
        state.position = fix.point
        state.accuracyMeters = fix.accuracyMeters
    }
}
