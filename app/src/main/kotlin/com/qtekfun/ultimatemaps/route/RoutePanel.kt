package com.qtekfun.ultimatemaps.route

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.nav.LaunchStatus
import com.qtekfun.ultimatemaps.nav.NavStartHost
import com.qtekfun.ultimatemaps.nav.RouteFailureMessages
import com.qtekfun.ultimatemaps.places.PanelButton
import com.qtekfun.ultimatemaps.places.PanelNote
import com.qtekfun.ultimatemaps.transit.TransitSection
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * Route preview card, top to bottom: header (destination and close), the from / stops / to card, the travel-mode
 * segmented control, the result with THE primary action ("Start"), a quiet "Simulate" and the collapsible route
 * options. While the origin is being picked, [originSearch] (the search field and results) replaces the lower part.
 */
@Composable
fun RoutePanel(
    route: RoutePreviewController,
    onUseLocation: () -> Unit,
    originSearch: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navStart: NavStartHost? = null,
) {
    val s = route.state
    val scroll = rememberScrollState()
    // The origin search holds a lazy list, which cannot live inside a scrolling parent.
    val scrolling = if (s.pickingOrigin) Modifier else Modifier.verticalScroll(scroll)
    Column(modifier.fillMaxWidth().then(scrolling).testTag("route_panel")) {
        Header(s, route::close)
        Spacer(Modifier.height(SECTION_GAP))
        FromToCard(route, onUseLocation)
        if (s.pickingOrigin) {
            PanelNote(stringResource(R.string.route_pick_hint), "route_pick_hint")
            originSearch()
            PanelButton(stringResource(R.string.route_pick_cancel), route::cancelPickOrigin, Modifier.fillMaxWidth(), tag = "route_pick_cancel")
        } else {
            Spacer(Modifier.height(SECTION_GAP))
            ProfileSelector(route)
            Spacer(Modifier.height(SECTION_GAP))
            val transit = route.transit
            if (s.transitMode && transit != null) {
                TransitSection(transit)
            } else {
                ResultAndStart(s, navStart)
                AlternativesSection(route)
                Spacer(Modifier.height(4.dp))
                RouteOptionsSection(route)
                if (s.profile == RoutingProfile.BIKE) BikeCyclewaysRow(route)
            }
        }
    }
}

/** At least 48 dp, more in glove mode. */
private val target: Dp @Composable get() = maxOf(MIN_TARGET, Mapas.dimens.touchTarget)

@Composable
private fun Icon(icon: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(icon, null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}

/** Title ("To <place>") and a close control that is an icon with a 48 dp target, not a text link. */
@Composable
private fun Header(s: RouteState, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            stringResource(R.string.route_to, s.destination?.name.orEmpty()),
            style = Mapas.typography.title.copy(color = Mapas.colors.label),
            maxLines = 2,
            modifier = Modifier.weight(1f).testTag("route_destination"),
        )
        val closeText = stringResource(R.string.route_close)
        Box(
            Modifier
                .size(target)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = closeText }
                .testTag("route_close"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Mapas.colors.field), contentAlignment = Alignment.Center) {
                Icon(RouteIcons.close, Mapas.colors.secondaryLabel, 16.dp)
            }
        }
    }
}

/** One grouped card: origin, intermediate stops and destination, separated by hairlines. */
@Composable
private fun FromToCard(route: RoutePreviewController, onUseLocation: () -> Unit) {
    val s = route.state
    val colors = Mapas.colors
    Column(Modifier.fillMaxWidth().clip(Mapas.shapes.control).background(colors.field)) {
        val originText = when (val o = s.origin) {
            RouteOrigin.Current -> stringResource(R.string.route_from_current)
            is RouteOrigin.Picked -> o.label?.let { stringResource(R.string.route_from_named, it) }
                ?: stringResource(R.string.route_from_point)
        }
        CardRow {
            Marker { Box(Modifier.size(12.dp).border(2.dp, colors.accent, CircleShape)) }
            BasicText(
                originText,
                style = Mapas.typography.body.copy(color = colors.label),
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .let { if (s.pickingOrigin) it else it.clickable(role = Role.Button, onClick = route::beginPickOrigin) }
                    .padding(vertical = 12.dp)
                    .testTag("route_origin"),
            )
            if (!s.pickingOrigin) {
                TextAction(stringResource(R.string.route_change_short), route::beginPickOrigin, "route_change_origin")
            }
        }
        // "Use my location" only matters when the start is not already the location, or when it is missing.
        val useLocation = !s.pickingOrigin && (s.origin is RouteOrigin.Picked || s.status == RouteStatus.NEEDS_ORIGIN)
        if (useLocation) {
            CardRow(startInset = true) {
                Spacer(Modifier.weight(1f))
                TextAction(stringResource(R.string.route_use_location), onUseLocation, "route_use_location")
            }
        }
        if (!s.pickingOrigin) Stops(route)
        Divider()
        CardRow {
            Marker { Box(Modifier.size(12.dp).background(colors.accent, CircleShape)) }
            BasicText(
                s.destination?.name.orEmpty(),
                style = Mapas.typography.body.copy(color = colors.label),
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("route_to_row"),
            )
        }
    }
}

private val SECTION_GAP = 12.dp
private val MIN_TARGET = 48.dp
private val MARKER_WIDTH = 24.dp

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp + MARKER_WIDTH + 8.dp).height(1.dp).background(Mapas.colors.separator))
}

@Composable
private fun CardRow(startInset: Boolean = false, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = target).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startInset) Spacer(Modifier.width(MARKER_WIDTH + 8.dp))
        content()
    }
}

@Composable
private fun Marker(content: @Composable () -> Unit) {
    Box(Modifier.width(MARKER_WIDTH + 8.dp), contentAlignment = Alignment.CenterStart) { content() }
}

/** A small text action (accent colour, no background) with a full touch target. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.heightIn(min = target).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Mapas.typography.callout.copy(color = Mapas.colors.accent))
    }
}

/** Stops between the origin and the destination: name, move up / down and remove, each a 48 dp touch target. */
@Composable
private fun Stops(route: RoutePreviewController) {
    val stops = route.state.stops
    if (stops.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("route_stops")) {
        Divider()
        BasicText(
            stringResource(R.string.route_stops_title, stops.size, RoutePreviewController.MAX_STOPS),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(start = 12.dp + MARKER_WIDTH + 8.dp, top = 8.dp),
        )
        stops.forEachIndexed { i, stop ->
            CardRow {
                Marker { Box(Modifier.size(8.dp).background(Mapas.colors.secondaryLabel, CircleShape)) }
                BasicText(
                    "${i + 1}. ${stop.name}",
                    style = Mapas.typography.body.copy(color = Mapas.colors.label),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("route_stop_$i"),
                )
                StopButton(RouteIcons.up, stringResource(R.string.route_stop_up, stop.name), i > 0, "route_stop_up_$i") { route.moveStop(i, -1) }
                StopButton(RouteIcons.down, stringResource(R.string.route_stop_down, stop.name), i < stops.lastIndex, "route_stop_down_$i") { route.moveStop(i, 1) }
                StopButton(RouteIcons.close, stringResource(R.string.route_stop_remove, stop.name), true, "route_stop_remove_$i") { route.removeStop(i) }
            }
        }
        if (stops.size >= RoutePreviewController.MAX_STOPS) {
            BasicText(
                stringResource(R.string.route_stops_limit, RoutePreviewController.MAX_STOPS),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.padding(start = 12.dp + MARKER_WIDTH + 8.dp, end = 12.dp, bottom = 8.dp).testTag("route_stops_limit"),
            )
        }
    }
}

@Composable
private fun StopButton(icon: ImageVector, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(target)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (enabled) Mapas.colors.accent else Mapas.colors.separator, 20.dp)
    }
}

/** Travel mode: a segmented control (pick exactly one), visibly a track with a raised thumb, unlike any button. */
@Composable
private fun ProfileSelector(route: RoutePreviewController) {
    val colors = Mapas.colors
    val groupName = stringResource(R.string.route_profile_group)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.control)
            .background(colors.field)
            .padding(2.dp)
            .selectableGroup()
            .semantics { contentDescription = groupName },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        profiles.forEach { (profile, label, tag) ->
            val selected = route.state.profile == profile && !route.state.transitMode
            ProfileSegment(selected, profileIcon(profile), stringResource(label), tag, Modifier.weight(1f)) { route.setProfile(profile) }
        }
        if (route.transit != null) {
            ProfileSegment(
                route.state.transitMode, RouteIcons.transit, stringResource(R.string.route_profile_transit), "profile_transit", Modifier.weight(1f),
            ) { route.setTransitMode(true) }
        }
    }
}

@Composable
private fun ProfileSegment(selected: Boolean, icon: ImageVector, label: String, tag: String, modifier: Modifier, onSelect: () -> Unit) {
    val colors = Mapas.colors
    Row(
        modifier
            .heightIn(min = target)
            .clip(Mapas.shapes.field)
            .background(if (selected) colors.segmentThumb else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton) { onSelect() }
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) colors.label else colors.secondaryLabel
        Icon(icon, fg, 20.dp)
        Spacer(Modifier.width(6.dp))
        BasicText(label, style = Mapas.typography.callout.copy(color = fg), maxLines = 1)
    }
}

private fun profileIcon(profile: RoutingProfile): ImageVector = when (profile) {
    RoutingProfile.CAR -> RouteIcons.car
    RoutingProfile.FOOT -> RouteIcons.foot
    RoutingProfile.BIKE -> RouteIcons.bike
}

/**
 * The result (time first and large, then the distance) next to ONE large filled "Start" button; while computing or on
 * error the same place shows the reason and "Start" is off. "Simulate" is a quiet text action below.
 */
@Composable
private fun ResultAndStart(s: RouteState, host: NavStartHost?) {
    if (host == null) {
        Status(s)
        return
    }
    val computing = host.state.status == LaunchStatus.COMPUTING
    val done = s.status == RouteStatus.DONE
    Column(Modifier.fillMaxWidth().testTag("route_start_block")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The result and the button side by side only when there is room; otherwise the button goes full width below.
            val sideBySide = done && maxWidth >= 340.dp
            if (sideBySide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Status(s) }
                    Spacer(Modifier.width(12.dp))
                    StartButton(host, enabled = !computing, Modifier.widthIn(min = 128.dp))
                }
            } else {
                Column {
                    Status(s)
                    Spacer(Modifier.height(8.dp))
                    StartButton(host, enabled = done && !computing, Modifier.fillMaxWidth())
                }
            }
        }
        if (done) {
            Box(Modifier.align(Alignment.End).heightIn(min = target).clickable(enabled = !computing, role = Role.Button, onClick = host.onSimulate).padding(horizontal = 8.dp).testTag("route_simulate"), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(RouteIcons.play, if (computing) Mapas.colors.secondaryLabel else Mapas.colors.accent, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        stringResource(R.string.nav_ui_simulate),
                        style = Mapas.typography.callout.copy(color = if (computing) Mapas.colors.secondaryLabel else Mapas.colors.accent),
                    )
                }
            }
        }
        if (computing) PanelNote(stringResource(R.string.nav_ui_computing), "route_start_status")
        val failure = host.state.failure
        if (host.state.status == LaunchStatus.FAILED && failure != null) {
            PanelNote(RouteFailureMessages.of(LocalContext.current, failure, null), "route_start_status")
        }
    }
}

/** The single primary action: large, filled with the accent colour; off (muted) while there is nothing to start. */
@Composable
private fun StartButton(host: NavStartHost, enabled: Boolean, modifier: Modifier) {
    val colors = Mapas.colors
    Box(
        modifier
            .heightIn(min = maxOf(56.dp, target))
            .clip(Mapas.shapes.control)
            .background(if (enabled) colors.accent else colors.field)
            .clickable(enabled = enabled, role = Role.Button, onClick = host.onStart)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("route_start"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            stringResource(R.string.nav_ui_start),
            style = Mapas.typography.title.copy(color = if (enabled) colors.onAccent else colors.secondaryLabel, textAlign = TextAlign.Center),
        )
    }
}

/** Collapsible "Route options": collapsed by default, its header summarises what is being avoided. */
@Composable
private fun RouteOptionsSection(route: RoutePreviewController) {
    val s = route.state
    val o = s.options
    val car = s.profile == RoutingProfile.CAR
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = buildList {
        if (o.avoidMotorways && car) add(stringResource(R.string.route_avoid_short_motorways))
        if (o.avoidTolls && car) add(stringResource(R.string.route_avoid_short_tolls))
        if (o.avoidFerries) add(stringResource(R.string.route_avoid_short_ferries))
        if (o.avoidUnpaved) add(stringResource(R.string.route_avoid_short_unpaved))
    }
    Column(Modifier.fillMaxWidth().testTag("route_options")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = target)
                .clickable(role = Role.Button) { expanded = !expanded }
                .testTag("route_options_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                BasicText(stringResource(R.string.route_options_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                BasicText(
                    if (active.isEmpty()) stringResource(R.string.route_options_none)
                    else stringResource(R.string.route_options_avoiding, active.joinToString(", ")),
                    style = Mapas.typography.callout.copy(color = if (active.isEmpty()) Mapas.colors.secondaryLabel else Mapas.colors.accent),
                    modifier = Modifier.testTag("route_options_summary"),
                )
            }
            Icon(if (expanded) RouteIcons.chevronUp else RouteIcons.chevronDown, Mapas.colors.secondaryLabel, 20.dp, Modifier.padding(end = 4.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(stringResource(R.string.route_avoid_motorways), o.avoidMotorways && car, car, "avoid_motorways", Modifier.weight(1f)) {
                    route.setOptions(o.copy(avoidMotorways = !o.avoidMotorways))
                }
                OptionChip(stringResource(R.string.route_avoid_tolls), o.avoidTolls && car, car, "avoid_tolls", Modifier.weight(1f)) {
                    route.setOptions(o.copy(avoidTolls = !o.avoidTolls))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(stringResource(R.string.route_avoid_ferries), o.avoidFerries, true, "avoid_ferries", Modifier.weight(1f)) {
                    route.setOptions(o.copy(avoidFerries = !o.avoidFerries))
                }
                OptionChip(stringResource(R.string.route_avoid_unpaved), o.avoidUnpaved, true, "avoid_unpaved", Modifier.weight(1f)) {
                    route.setOptions(o.copy(avoidUnpaved = !o.avoidUnpaved))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A filter chip: outlined when off, tinted with a check mark when on; a checkbox for accessibility. */
@Composable
internal fun OptionChip(text: String, checked: Boolean, enabled: Boolean, tag: String, modifier: Modifier, onToggle: () -> Unit) {
    val colors = Mapas.colors
    val fg = when {
        !enabled -> colors.separator
        checked -> colors.accent
        else -> colors.label
    }
    Row(
        modifier
            .heightIn(min = target)
            .clip(Mapas.shapes.pill)
            .background(if (checked) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (checked) colors.accent else colors.separator, Mapas.shapes.pill)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = 12.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            Icon(RouteIcons.check, fg, 16.dp)
            Spacer(Modifier.width(4.dp))
        }
        BasicText(text, style = Mapas.typography.callout.copy(color = fg), maxLines = 1)
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
            val distance = RouteFormat.distance(s.distanceMeters, locale)
            val time = RouteFormat.duration(s.durationSeconds)
            val description = stringResource(R.string.route_summary, distance, time)
            Column(Modifier.semantics { contentDescription = description }.testTag("route_summary")) {
                BasicText(time, style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label))
                BasicText(distance, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            }
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
    RouteError.NO_CYCLE_ROUTE -> R.string.bike_err_no_cycle_route
    RouteError.TIMEOUT -> R.string.route_err_timeout
    RouteError.INTERNAL, null -> R.string.route_err_internal
}
