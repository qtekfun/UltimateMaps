package com.qtekfun.ultimatemaps.bikeshare

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
import com.qtekfun.ultimatemaps.core.bikeshare.BikeAvailability
import com.qtekfun.ultimatemaps.core.bikeshare.BikeStation
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.core.nav.StopInsertion
import com.qtekfun.ultimatemaps.places.PanelButton
import com.qtekfun.ultimatemaps.places.PanelNote
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.StopResult
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Observable state of the bike-share card. Written from the main thread only. */
class BikeCardState {
    var station by mutableStateOf<BikeStation?>(null)
        private set

    /** Live counts of the open station, when the user turned them on and the feed answered; null otherwise (nothing is shown). */
    var availability by mutableStateOf<BikeAvailability?>(null)

    /** Why the last "Add stop" was refused (shown in the card); null when none. */
    var notice by mutableStateOf<StopResult?>(null)

    /** An "Add stop" to the trip in progress is being calculated. */
    var adding by mutableStateOf(false)

    /** Why the last "Add stop" to the trip in progress was refused (the trip is unchanged); null when none. */
    var navNotice by mutableStateOf<AddStopResult?>(null)

    fun open(station: BikeStation) {
        this.station = station
        availability = null
        notice = null
        navNotice = null
    }

    fun close() {
        station = null
        availability = null
        notice = null
        navNotice = null
    }
}

/** What the route knows about a station: its name; no address is invented. */
fun BikeStation.toPlaceInfo(category: String? = null): PlaceInfo = PlaceInfo(
    name = name.ifBlank { category.orEmpty() },
    point = location,
    address = null,
    category = category,
)

/** "14:57" in the device's time zone: when the feed says the counts were reported. */
fun updatedClock(millis: Long, locale: Locale, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
    SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }.format(Date(millis))

/** What the map needs for the optional bike-share layer; built by the activity from the application. */
class BikeShareEnv(
    val settings: kotlinx.coroutines.flow.StateFlow<com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettings>,
    val repository: com.qtekfun.ultimatemaps.core.bikeshare.BikeShareRepository,
    /** Live counts of a station (null: switched off or unavailable). */
    val live: suspend (BikeStation) -> BikeAvailability? = { null },
)

/** What the sheet needs to show the card; built by the panel host. */
class BikeCardHost(
    val state: BikeCardState,
    val generatedMillis: () -> Long?,
    val onGo: (BikeStation) -> Unit,
    val onAddStop: (BikeStation) -> Unit,
    /** True while a navigation is running: "Add stop" then adds to the trip in progress. */
    val navigating: () -> Boolean = { false },
)

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
 * Bike-share station card: name, system, number of docks, the live counts when the user turned them on (and only then), the
 * licence credit of the system's data, and the Route to the station / Add stop buttons. Nothing is guessed: without live
 * data the card says only what the data file knows. "Add stop" only appears while a route is previewed or a trip is being
 * navigated. Every touch target is at least 48 dp.
 */
@Composable
fun BikeCard(
    state: BikeCardState,
    generatedMillis: Long?,
    routeActive: Boolean,
    onGo: (BikeStation) -> Unit,
    onAddStop: (BikeStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.station ?: return
    val locale = LocalConfiguration.current.locales[0]
    val button = Modifier.heightIn(min = TOUCH)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("bike_card")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    s.name.ifBlank { stringResource(R.string.bike_card_title_unknown) },
                    style = Mapas.typography.title.copy(color = Mapas.colors.label), modifier = Modifier.testTag("bike_title"),
                )
                BasicText(
                    stringResource(R.string.bike_card_system, s.system.name),
                    style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("bike_system"),
                )
            }
            BasicText(
                stringResource(R.string.bike_card_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .heightIn(min = TOUCH)
                    .clickable(role = Role.Button, onClick = state::close)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("bike_close"),
            )
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (s.capacity > 0) stringResource(R.string.bike_card_capacity, s.capacity) else stringResource(R.string.bike_card_capacity_unknown),
            style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("bike_capacity"),
        )
        state.availability?.let { a ->
            BasicText(
                stringResource(R.string.bike_card_availability, a.bikes, a.freeDocks, updatedClock(a.updatedAtMillis, locale)),
                style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.padding(top = 2.dp).testTag("bike_live"),
            )
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.bike_card_note),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("bike_note"),
        )
        BasicText(
            s.system.attribution,
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("bike_card_attribution"),
        )
        generatedMillis?.let {
            BasicText(
                stringResource(R.string.bike_card_data_date, CameraAttribution.dateText(it, locale)),
                style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("bike_data_date"),
            )
        }
        state.notice?.let { n -> noticeText(n)?.let { PanelNote(it, "bike_notice") } }
        state.navNotice?.let { n -> navNoticeText(n)?.let { PanelNote(it, "bike_nav_notice") } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.bike_go), { onGo(s) }, button.weight(1f), primary = true, tag = "bike_go")
            if (routeActive) {
                PanelButton(
                    stringResource(if (state.adding) R.string.nav_stop_adding else R.string.bike_add_stop),
                    { onAddStop(s) }, button.weight(1f), enabled = !state.adding, tag = "bike_add_stop",
                )
            }
        }
    }
}

private val TOUCH = 48.dp
