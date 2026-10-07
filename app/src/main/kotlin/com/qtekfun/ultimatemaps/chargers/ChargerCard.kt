package com.qtekfun.ultimatemaps.chargers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.chargers.Charger
import com.qtekfun.ultimatemaps.core.chargers.ChargerAccess
import com.qtekfun.ultimatemaps.core.chargers.ChargerAttribution
import com.qtekfun.ultimatemaps.core.chargers.ChargerAuth
import com.qtekfun.ultimatemaps.core.chargers.ChargerFee
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.core.nav.StopInsertion
import com.qtekfun.ultimatemaps.places.PanelButton
import com.qtekfun.ultimatemaps.places.PanelNote
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.StopResult
import com.qtekfun.ultimatemaps.settings.socketLabel
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** Observable state of the charger card. Written from the main thread only. */
class ChargerCardState {
    var charger by mutableStateOf<Charger?>(null)
        private set

    /** Why the last "Add stop" was refused (shown in the card); null when none. */
    var notice by mutableStateOf<StopResult?>(null)

    /** An "Add stop" to the trip in progress is being calculated. */
    var adding by mutableStateOf(false)

    /** Why the last "Add stop" to the trip in progress was refused (the trip is unchanged); null when none. */
    var navNotice by mutableStateOf<AddStopResult?>(null)

    fun open(charger: Charger) {
        this.charger = charger
        notice = null
        navNotice = null
    }

    fun close() {
        charger = null
        notice = null
        navNotice = null
    }
}

/** What the route knows about a charger: its operator (or network, or name) is the name; no address is invented. */
fun Charger.toPlaceInfo(category: String? = null): PlaceInfo = PlaceInfo(
    name = title.ifBlank { category.orEmpty() },
    point = location,
    address = null,
    category = category,
)

/** What the map needs for the optional charger layer; built by the activity from the application. */
class ChargersEnv(
    val settings: kotlinx.coroutines.flow.StateFlow<com.qtekfun.ultimatemaps.core.chargers.ChargerSettings>,
    val repository: com.qtekfun.ultimatemaps.core.chargers.ChargerRepository,
)

/** What the sheet needs to show the card; built by the panel host. */
class ChargerCardHost(
    val state: ChargerCardState,
    val generatedMillis: () -> Long?,
    val onGo: (Charger) -> Unit,
    val onAddStop: (Charger) -> Unit,
    /** True while a navigation is running: "Add stop" then adds to the trip in progress. */
    val navigating: () -> Boolean = { false },
)

/** "12.5" for 12.5 kW, "22" for 22 kW: no trailing ".0", decimal separator of [locale]. */
fun kwText(kw: Double, locale: Locale): String =
    if (kw == Math.rint(kw)) String.format(locale, "%.0f", kw) else String.format(locale, "%.1f", kw)

@Composable
private fun noticeText(n: StopResult): String? = when (n) {
    StopResult.DUPLICATE -> stringResource(R.string.fuel_stop_duplicate)
    StopResult.SAME_AS_DESTINATION -> stringResource(R.string.fuel_stop_is_destination)
    StopResult.LIMIT -> stringResource(R.string.fuel_stop_limit, RoutePreviewController.MAX_STOPS)
    StopResult.NO_ROUTE -> stringResource(R.string.fuel_stop_no_route)
    StopResult.ADDED -> null
}

@Composable
private fun navNoticeText(n: AddStopResult): String? = when (n) {
    AddStopResult.NO_ROUTE -> stringResource(R.string.nav_stop_no_route)
    AddStopResult.LIMIT -> stringResource(R.string.nav_stop_limit, StopInsertion.MAX_STOPS)
    AddStopResult.DUPLICATE -> stringResource(R.string.nav_stop_duplicate)
    AddStopResult.SAME_AS_DESTINATION -> stringResource(R.string.nav_stop_is_destination)
    AddStopResult.BUSY -> stringResource(R.string.nav_stop_busy)
    AddStopResult.NOT_NAVIGATING -> stringResource(R.string.nav_stop_not_navigating)
    AddStopResult.ADDED -> null
}

/**
 * EV charger card: operator (or network), the plugs with their count and power, fee, hours, access, how to start a session,
 * the OpenStreetMap attribution and the Go / Add stop buttons. Every field OpenStreetMap does not give is said to be unknown
 * or left out, never guessed. "Add stop" only appears while a route is previewed or a trip is being navigated (then it
 * re-plans the trip in progress). Every touch target is at least 48 dp.
 */
@Composable
fun ChargerCard(
    state: ChargerCardState,
    generatedMillis: Long?,
    routeActive: Boolean,
    onGo: (Charger) -> Unit,
    onAddStop: (Charger) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = state.charger ?: return
    val locale = LocalConfiguration.current.locales[0]
    val english = locale.language != "es"
    val button = Modifier.heightIn(min = TOUCH)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ev_card")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    c.title.ifBlank { stringResource(R.string.ev_card_title_unknown) },
                    style = Mapas.typography.title.copy(color = Mapas.colors.label),
                    modifier = Modifier.testTag("ev_title"),
                )
                if (c.operator.isNotBlank() && c.network.isNotBlank() && !c.network.equals(c.operator, ignoreCase = true)) {
                    BasicText(
                        stringResource(R.string.ev_card_network, c.network),
                        style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("ev_network"),
                    )
                }
                if (c.name.isNotBlank() && !c.name.equals(c.title, ignoreCase = true)) {
                    BasicText(c.name, style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("ev_name"))
                }
            }
            BasicText(
                stringResource(R.string.ev_card_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .heightIn(min = TOUCH)
                    .clickable(role = Role.Button, onClick = state::close)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("ev_close"),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (c.sockets.isEmpty()) {
            BasicText(
                stringResource(R.string.ev_card_sockets_unknown),
                style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_sockets_unknown"),
            )
        } else {
            BasicText(stringResource(R.string.ev_card_sockets), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            c.sockets.forEachIndexed { i, s ->
                val type = stringResource(socketLabel(s.type))
                val withPower = s.powerKw?.let { stringResource(R.string.ev_card_socket_power, type, kwText(it, locale)) } ?: type
                val line = if (s.count > 0) stringResource(R.string.ev_card_socket_count, s.count, withPower) else withPower
                BasicText(
                    line,
                    style = Mapas.typography.body.copy(color = Mapas.colors.label),
                    modifier = Modifier.padding(vertical = 2.dp).testTag("ev_socket_$i"),
                )
            }
        }
        if (c.capacity > 0) {
            BasicText(stringResource(R.string.ev_card_capacity, c.capacity), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_capacity"))
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(
                when (c.fee) {
                    ChargerFee.PAID -> R.string.ev_card_fee_paid
                    ChargerFee.FREE -> R.string.ev_card_fee_free
                    ChargerFee.UNKNOWN -> R.string.ev_card_fee_unknown
                },
            ),
            style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_fee"),
        )
        c.openingHours.takeIf { it.isNotBlank() }?.let {
            BasicText(stringResource(R.string.ev_card_hours, it), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_hours"))
        }
        when (c.access) {
            ChargerAccess.CUSTOMERS -> R.string.ev_card_access_customers
            ChargerAccess.PRIVATE -> R.string.ev_card_access_private
            ChargerAccess.PUBLIC -> R.string.ev_card_access_public
            ChargerAccess.UNKNOWN -> null
        }?.let { BasicText(stringResource(it), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_access")) }
        val auth = buildList {
            if (c.authMask and ChargerAuth.NONE != 0) add(stringResource(R.string.ev_auth_none))
            if (c.authMask and ChargerAuth.APP != 0) add(stringResource(R.string.ev_auth_app))
            if (c.authMask and ChargerAuth.CARD != 0) add(stringResource(R.string.ev_auth_card))
            if (c.authMask and ChargerAuth.NFC != 0) add(stringResource(R.string.ev_auth_nfc))
        }
        if (auth.isNotEmpty()) {
            BasicText(
                stringResource(R.string.ev_card_auth, auth.joinToString(", ")),
                style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("ev_auth"),
            )
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.ev_card_note),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("ev_note"),
        )
        BasicText(
            ChargerAttribution.text(english),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("ev_card_attribution"),
        )
        generatedMillis?.let {
            BasicText(
                stringResource(R.string.ev_card_data_date, CameraAttribution.dateText(it, english)),
                style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("ev_data_date"),
            )
        }
        state.notice?.let { n -> noticeText(n)?.let { PanelNote(it, "ev_notice") } }
        state.navNotice?.let { n -> navNoticeText(n)?.let { PanelNote(it, "ev_nav_notice") } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.ev_go), { onGo(c) }, button.weight(1f), primary = true, tag = "ev_go")
            if (routeActive) {
                PanelButton(
                    stringResource(if (state.adding) R.string.nav_stop_adding else R.string.ev_add_stop),
                    { onAddStop(c) }, button.weight(1f), enabled = !state.adding, tag = "ev_add_stop",
                )
            }
        }
    }
}

private val TOUCH = 48.dp
