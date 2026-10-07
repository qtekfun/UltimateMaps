package com.qtekfun.mapas.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.places.PanelNote
import com.qtekfun.mapas.ui.theme.Mapas

/**
 * Route preview card: destination, origin, profile, avoid options and the result (distance and time) or why
 * there is none. While the origin is being picked, [originSearch] (the search field and results) is shown.
 */
@Composable
fun RoutePanel(
    route: RoutePreviewController,
    onUseLocation: () -> Unit,
    originSearch: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = route.state
    val scroll = rememberScrollState()
    // The origin search holds a lazy list, which cannot live inside a scrolling parent.
    val scrolling = if (s.pickingOrigin) Modifier else Modifier.verticalScroll(scroll)
    Column(modifier.fillMaxWidth().then(scrolling).testTag("route_panel")) {
        Row(verticalAlignment = Alignment.Top) {
            BasicText(
                stringResource(R.string.route_to, s.destination?.name.orEmpty()),
                style = Mapas.typography.title.copy(color = Mapas.colors.label),
                modifier = Modifier.weight(1f).testTag("route_destination"),
            )
            BasicText(
                stringResource(R.string.route_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier.clickable(role = Role.Button, onClick = route::close).padding(8.dp).testTag("route_close"),
            )
        }
        val originText = when (val o = s.origin) {
            RouteOrigin.Current -> stringResource(R.string.route_from_current)
            is RouteOrigin.Picked -> o.label?.let { stringResource(R.string.route_from_named, it) }
                ?: stringResource(R.string.route_from_point)
        }
        BasicText(
            originText,
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("route_origin"),
        )
        if (!s.pickingOrigin) Stops(route)
        Spacer(Modifier.height(8.dp))
        if (s.pickingOrigin) {
            PanelNote(stringResource(R.string.route_pick_hint), "route_pick_hint")
            originSearch()
            PanelButton(stringResource(R.string.route_pick_cancel), route::cancelPickOrigin, Modifier.fillMaxWidth(), tag = "route_pick_cancel")
        } else {
            Status(s)
            Spacer(Modifier.height(8.dp))
            Controls(route, onUseLocation)
        }
    }
}

/** Stops between the origin and the destination: name, move up / down and remove, each a 48 dp touch target. */
@Composable
private fun Stops(route: RoutePreviewController) {
    val stops = route.state.stops
    if (stops.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("route_stops")) {
        BasicText(
            stringResource(R.string.route_stops_title, stops.size, RoutePreviewController.MAX_STOPS),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
        stops.forEachIndexed { i, stop ->
            Row(Modifier.fillMaxWidth().heightIn(min = STOP_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "${i + 1}. ${stop.name}",
                    style = Mapas.typography.body.copy(color = Mapas.colors.label),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("route_stop_$i"),
                )
                StopButton("↑", stringResource(R.string.route_stop_up, stop.name), i > 0, "route_stop_up_$i") { route.moveStop(i, -1) }
                StopButton("↓", stringResource(R.string.route_stop_down, stop.name), i < stops.lastIndex, "route_stop_down_$i") { route.moveStop(i, 1) }
                StopButton("✕", stringResource(R.string.route_stop_remove, stop.name), true, "route_stop_remove_$i") { route.removeStop(i) }
            }
        }
        if (stops.size >= RoutePreviewController.MAX_STOPS) {
            PanelNote(stringResource(R.string.route_stops_limit, RoutePreviewController.MAX_STOPS), "route_stops_limit")
        }
    }
}

@Composable
private fun StopButton(glyph: String, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(STOP_ROW_MIN)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = Mapas.typography.body.copy(color = if (enabled) Mapas.colors.accent else Mapas.colors.separator))
    }
}

private val STOP_ROW_MIN = 48.dp

@Composable
private fun Controls(route: RoutePreviewController, onUseLocation: () -> Unit) {
    val s = route.state
    val o = s.options
    val car = s.profile == RoutingProfile.CAR
    val gap = Arrangement.spacedBy(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
        PanelButton(stringResource(R.string.route_change_origin), route::beginPickOrigin, Modifier.weight(1f), tag = "route_change_origin")
        PanelButton(stringResource(R.string.route_use_location), onUseLocation, Modifier.weight(1f), tag = "route_use_location")
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
        profiles.forEach { (profile, label, tag) ->
            PanelButton(stringResource(label), { route.setProfile(profile) }, Modifier.weight(1f), primary = s.profile == profile, tag = tag)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
        PanelButton(
            stringResource(R.string.route_avoid_motorways), { route.setOptions(o.copy(avoidMotorways = !o.avoidMotorways)) },
            Modifier.weight(1f), primary = o.avoidMotorways && car, enabled = car, tag = "avoid_motorways",
        )
        PanelButton(
            stringResource(R.string.route_avoid_tolls), { route.setOptions(o.copy(avoidTolls = !o.avoidTolls)) },
            Modifier.weight(1f), primary = o.avoidTolls && car, enabled = car, tag = "avoid_tolls",
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
        PanelButton(
            stringResource(R.string.route_avoid_ferries), { route.setOptions(o.copy(avoidFerries = !o.avoidFerries)) },
            Modifier.weight(1f), primary = o.avoidFerries, tag = "avoid_ferries",
        )
        PanelButton(
            stringResource(R.string.route_avoid_unpaved), { route.setOptions(o.copy(avoidUnpaved = !o.avoidUnpaved)) },
            Modifier.weight(1f), primary = o.avoidUnpaved, tag = "avoid_unpaved",
        )
    }
}

@Composable
private fun Status(s: RouteState) {
    when (s.status) {
        RouteStatus.COMPUTING -> PanelNote(stringResource(R.string.route_computing), "route_status")
        RouteStatus.NEEDS_ORIGIN -> PanelNote(stringResource(R.string.route_needs_origin), "route_status")
        RouteStatus.ERROR -> PanelNote(stringResource(errorText(s.error)), "route_status")
        RouteStatus.DONE -> {
            val locale = LocalConfiguration.current.locales[0]
            BasicText(
                stringResource(
                    R.string.route_summary,
                    RouteFormat.distance(s.distanceMeters, locale), RouteFormat.duration(s.durationSeconds),
                ),
                style = Mapas.typography.title.copy(color = Mapas.colors.label),
                modifier = Modifier.testTag("route_summary"),
            )
        }
        RouteStatus.IDLE -> Unit
    }
}

private val profiles = listOf(
    Triple(RoutingProfile.CAR, R.string.route_profile_car, "profile_car"),
    Triple(RoutingProfile.FOOT, R.string.route_profile_foot, "profile_foot"),
    Triple(RoutingProfile.BIKE, R.string.route_profile_bike, "profile_bike"),
)

private fun errorText(e: RouteError?): Int = when (e) {
    RouteError.NO_REGIONS -> R.string.route_err_no_regions
    RouteError.NEED_MORE_MAPS -> R.string.route_err_need_more_maps
    RouteError.START_NOT_FOUND -> R.string.route_err_start
    RouteError.END_NOT_FOUND -> R.string.route_err_end
    RouteError.STOP_NOT_FOUND -> R.string.route_err_stop
    RouteError.ROUTE_NOT_FOUND -> R.string.route_err_not_found
    RouteError.TIMEOUT -> R.string.route_err_timeout
    RouteError.INTERNAL, null -> R.string.route_err_internal
}
