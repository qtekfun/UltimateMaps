package com.qtekfun.ultimatemaps.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.JourneyBadge
import com.qtekfun.ultimatemaps.core.transit.badges
import com.qtekfun.ultimatemaps.core.transit.JourneyNote
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import com.qtekfun.ultimatemaps.route.Icon
import com.qtekfun.ultimatemaps.places.PanelNote
import com.qtekfun.ultimatemaps.route.RouteFormat
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

private val MIN_TARGET = 48.dp
private val target: Dp @Composable get() = maxOf(MIN_TARGET, Mapas.dimens.touchTarget)

/**
 * The lower part of the route panel in transit mode: departure time, the itineraries (or one itinerary card), the
 * honest "theoretical times" note and the data attribution. Pure view over [TransitState]; [controller] receives the taps.
 */
@Composable
fun TransitSection(controller: TransitController, modifier: Modifier = Modifier) {
    val s = controller.state
    val realTime = controller.realTime
    // Cercanías real time (opt-in): ask once when a trip with a Renfe train is shown. The repository rate-limits it; with the
    // switch off this never runs.
    LaunchedEffect(s.itineraries, realTime?.enabled) {
        if (realTime != null && realTime.enabled && s.itineraries.any { i -> i.rides.any(realTime::supports) }) realTime.refresh()
    }
    Column(modifier.fillMaxWidth().testTag("transit_section")) {
        DepartureRow(controller)
        Spacer(Modifier.height(8.dp))
        if (s.showModeChips && s.phase != TransitPhase.IDLE && s.phase != TransitPhase.NEEDS_ORIGIN) {
            ModeChips(controller)
            Spacer(Modifier.height(8.dp))
        }
        when (s.phase) {
            TransitPhase.IDLE -> Unit
            TransitPhase.NEEDS_ORIGIN -> PanelNote(stringResource(R.string.transit_needs_origin), "transit_status")
            TransitPhase.COMPUTING -> PanelNote(stringResource(R.string.transit_computing), "transit_status")
            TransitPhase.ERROR -> PanelNote(errorText(s), "transit_error")
            TransitPhase.DONE -> if (s.detail && s.current != null) {
                ItineraryCard(controller, s.current!!)
            } else {
                ItineraryList(controller)
            }
        }
        if (s.phase == TransitPhase.DONE) TransitFooter(s, realTimeNote = realTime != null && realTime.enabled && s.itineraries.any { i -> i.rides.any(realTime::supports) })
    }
}

@Composable
private fun errorText(s: TransitState): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (s.error) {
        TransitError.NO_DATA -> stringResource(R.string.transit_err_no_data)
        TransitError.NOT_DOWNLOADED -> stringResource(R.string.transit_err_not_downloaded, s.errorCity.orEmpty())
        TransitError.ACROSS_INDEXES -> stringResource(R.string.transit_err_across, s.errorCity.orEmpty(), s.errorCity2.orEmpty())
        TransitError.OUTSIDE_COVERAGE -> stringResource(R.string.transit_err_outside)
        TransitError.EXPIRED -> stringResource(R.string.transit_err_expired, s.errorDate?.let { TransitFormat.date(it, locale) }.orEmpty())
        TransitError.NOT_YET_VALID -> stringResource(R.string.transit_err_not_yet, s.errorDate?.let { TransitFormat.date(it, locale) }.orEmpty())
        TransitError.NO_ROUTE -> stringResource(R.string.transit_err_no_route)
        TransitError.NO_ROUTE_MODES -> stringResource(R.string.transit_err_no_route_modes)
        TransitError.INTERNAL, null -> stringResource(R.string.transit_err_internal)
    }
}

// ---------------------------------------------------------------------------------------------------- departure

@Composable
private fun DepartureRow(controller: TransitController) {
    val s = controller.state
    val colors = Mapas.colors
    val group = stringResource(R.string.transit_group_departure)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Mapas.shapes.control)
                .background(colors.field)
                .padding(2.dp)
                .selectableGroup()
                .semantics { contentDescription = group },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val now = s.departure == null
            DepartureChoice(stringResource(R.string.transit_depart_now), now, "transit_depart_now", Modifier.weight(1f)) { controller.departNow() }
            DepartureChoice(stringResource(R.string.transit_depart_later), !now, "transit_depart_later", Modifier.weight(1f)) { controller.departLater() }
        }
        val at = s.departure
        if (at != null) {
            val locale = LocalConfiguration.current.locales[0]
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Stepper(stringResource(R.string.transit_day_earlier), "‹", "transit_day_prev") { controller.shiftDays(-1) }
                BasicText(
                    TransitFormat.date(at.toLocalDate(), locale),
                    style = Mapas.typography.body.copy(color = colors.label),
                    modifier = Modifier.weight(1f).testTag("transit_depart_date"),
                )
                Stepper(stringResource(R.string.transit_day_later), "›", "transit_day_next") { controller.shiftDays(1) }
                Stepper(stringResource(R.string.transit_time_earlier), "‹", "transit_time_prev") { controller.shiftMinutes(-15) }
                BasicText(
                    TransitFormat.time(at),
                    style = Mapas.typography.title.copy(color = colors.label),
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("transit_depart_time"),
                )
                Stepper(stringResource(R.string.transit_time_later), "›", "transit_time_next") { controller.shiftMinutes(15) }
            }
        }
    }
}

@Composable
private fun DepartureChoice(text: String, selected: Boolean, tag: String, modifier: Modifier, onSelect: () -> Unit) {
    val colors = Mapas.colors
    Box(
        modifier
            .heightIn(min = target)
            .clip(Mapas.shapes.field)
            .background(if (selected) colors.segmentThumb else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Mapas.typography.callout.copy(color = if (selected) colors.label else colors.secondaryLabel), maxLines = 1)
    }
}

@Composable
private fun Stepper(description: String, glyph: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(target)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = Mapas.typography.title.copy(color = Mapas.colors.accent))
    }
}

// ---------------------------------------------------------------------------------------------------- modes

@androidx.annotation.StringRes
internal fun modeLabel(mode: TransitMode): Int = when (mode) {
    TransitMode.BUS -> R.string.transit_mode_bus
    TransitMode.METRO -> R.string.transit_mode_metro
    TransitMode.TRAM -> R.string.transit_mode_tram
    TransitMode.TRAIN -> R.string.transit_mode_train
    TransitMode.FERRY -> R.string.transit_mode_ferry
    TransitMode.OTHER -> R.string.transit_mode_other
}

/** One chip per transport mode the city has, all on by default, plus Reset when one is off. Tags `transit_mode_<name>`. */
@Composable
private fun ModeChips(controller: TransitController) {
    val s = controller.state
    val colors = Mapas.colors
    val allOn = s.chipModes.all { it in s.modes }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("transit_modes"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        s.chipModes.forEach { mode ->
            val on = mode in s.modes
            Box(
                Modifier
                    .heightIn(min = target)
                    .clip(Mapas.shapes.pill)
                    .background(if (on) colors.accent.copy(alpha = 0.16f) else colors.field)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { controller.setMode(mode, it) })
                    .padding(horizontal = 14.dp)
                    .testTag("transit_mode_${mode.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    stringResource(modeLabel(mode)),
                    style = Mapas.typography.callout.copy(color = if (on) colors.accent else colors.secondaryLabel),
                    maxLines = 1,
                )
            }
        }
        if (!allOn) {
            Box(
                Modifier
                    .heightIn(min = target)
                    .clickable(role = Role.Button, onClick = controller::resetModes)
                    .padding(horizontal = 10.dp)
                    .testTag("transit_modes_reset"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(stringResource(R.string.transit_modes_reset), style = Mapas.typography.callout.copy(color = colors.accent), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- list

@Composable
private fun ItineraryList(controller: TransitController) {
    val s = controller.state
    Column(Modifier.fillMaxWidth().testTag("transit_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.itineraries.forEachIndexed { i, it ->
            ItineraryRow(i, it, selected = i == s.selected, zone = s.zone, realTime = controller.realTime) { controller.select(i) }
        }
    }
}

@Composable
private fun ItineraryRow(index: Int, it: Itinerary, selected: Boolean, zone: java.time.ZoneId, realTime: TransitRealTime?, onClick: () -> Unit) {
    val colors = Mapas.colors
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val depart = TransitFormat.time(it.departAt, zone)
    val arrive = TransitFormat.time(it.arriveAt, zone)
    val duration = RouteFormat.duration(it.durationSec.toDouble())
    val changes = if (it.isWalkOnly) stringResource(R.string.transit_walk_only)
    else if (it.transfers == 0) stringResource(R.string.transit_direct)
    else context.resources.getQuantityString(R.plurals.transit_transfers, it.transfers, it.transfers)
    val walking = stringResource(R.string.transit_walking, RouteFormat.distance(it.walkMeters.toDouble(), locale))
    val badges = it.badges()
    val lines = badges.map { b ->
        when (b) {
            is JourneyBadge.Walk -> context.resources.getQuantityString(R.plurals.transit_walk_description, b.minutes, b.minutes)
            is JourneyBadge.Line -> stringResource(TransitModeIcons.description(lineMode(b.ride.line)), b.ride.line.shortName)
        }
    }.joinToString(", ")
    val description = stringResource(R.string.transit_option_description, index + 1, duration, arrive, changes, walking)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.control)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.field)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$description $lines" }
            .padding(12.dp)
            .heightIn(min = target)
            .testTag("transit_option_$index"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.transit_times, depart, arrive),
                style = Mapas.typography.title.copy(color = colors.label),
                modifier = Modifier.weight(1f),
            )
            BasicText(duration, style = Mapas.typography.title.copy(color = colors.label), modifier = Modifier.testTag("transit_option_${index}_duration"))
        }
        Spacer(Modifier.height(6.dp))
        BadgeRow(index, badges, it.isWalkOnly)
        Spacer(Modifier.height(6.dp))
        BasicText(
            "$changes · $walking",
            style = Mapas.typography.callout.copy(color = colors.secondaryLabel),
            modifier = Modifier.testTag("transit_option_${index}_facts"),
        )
        val noteText = when (it.note) {
            JourneyNote.WALK_ABOUT_AS_FAST -> R.string.transit_note_walk_as_fast
            JourneyNote.LESS_WALKING -> R.string.transit_note_less_walking
            JourneyNote.FEWER_CHANGES -> R.string.transit_note_fewer_changes
            JourneyNote.WALK_THE_REST -> R.string.transit_note_walk_the_rest
            null -> null
        }
        if (noteText != null) {
            BasicText(
                stringResource(noteText),
                style = Mapas.typography.callout.copy(color = colors.accent),
                modifier = Modifier.testTag("transit_option_${index}_note"),
            )
        }
        // Real time (Renfe): one short line per train that is in the feed, e.g. "C4 · Delayed 4 min".
        it.rides.forEachIndexed { k, ride ->
            val status = RealTimeTexts.status(context.resources, rideRealTime(realTime, ride)) ?: return@forEachIndexed
            BasicText(
                "${ride.line.shortName} · ${status.text}",
                style = Mapas.typography.callout.copy(color = if (status.level == RealTimeTexts.Level.ON_TIME) colors.secondaryLabel else colors.warning),
                modifier = Modifier.testTag("transit_option_${index}_rt_$k"),
            )
        }
    }
}

/** What real time says about [ride]; reading [TransitRealTime.version] makes the caller recompose when new data arrives. */
@Composable
internal fun rideRealTime(realTime: TransitRealTime?, ride: ItineraryLeg.Ride): com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime? {
    if (realTime == null) return null
    @Suppress("UNUSED_VARIABLE") val version = realTime.version
    return if (realTime.enabled && realTime.supports(ride)) realTime.forRide(ride) else null
}

/** The mode of [line], from its GTFS route type. */
internal fun lineMode(line: LineInfo): TransitMode = TransitMode.ofRouteType(line.routeType)

/**
 * The line as a rounded chip: a small icon of its mode, then its short name, in the line colour (GTFS route_color, or a
 * per-mode default). The icon matters because short names are shared between modes (a bus "C2" and a train "C2"); the
 * chip also reads as "Bus line C2" to a screen reader.
 */
@Composable
internal fun LineChip(line: LineInfo, modifier: Modifier = Modifier) {
    val mode = lineMode(line)
    val spoken = stringResource(TransitModeIcons.description(mode), line.shortName)
    val fg = Color(line.textColor)
    Row(
        modifier
            .clip(Mapas.shapes.pill)
            .background(Color(line.color))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .widthIn(min = 28.dp)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TransitModeIcons.of(mode), fg, 14.dp, Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(4.dp))
        BasicText(line.shortName, style = Mapas.typography.callout.copy(color = fg), maxLines = 1)
    }
}

/**
 * The badge row: walking pills and line badges in travel order, wrapping onto the next line instead of cutting when it is long
 * (narrow screens, large font). Every element after the first carries its own chevron so a chevron never starts a line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeRow(index: Int, badges: List<JourneyBadge>, walkOnly: Boolean) {
    val colors = Mapas.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("transit_option_${index}_badges"),
    ) {
        if (badges.isEmpty()) WalkChip()
        badges.forEachIndexed { k, b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (k > 0) BasicText("›", style = Mapas.typography.callout.copy(color = colors.secondaryLabel))
                when (b) {
                    is JourneyBadge.Walk -> WalkPill(b.minutes, walkOnly, Modifier.testTag("transit_option_${index}_walk_$k"))
                    is JourneyBadge.Line -> LineChip(b.ride.line, Modifier.testTag("transit_option_${index}_chip_${b.rideIndex}"))
                }
            }
        }
    }
}

/**
 * A neutral grey pill with a walking person and the minutes, the same height and shape as a [LineChip]; no line colour. Grey is
 * the secondary label colour at low alpha so it reads in light, dark and glove modes alike.
 */
@Composable
internal fun WalkPill(minutes: Int, walkOnly: Boolean = false, modifier: Modifier = Modifier) {
    val colors = Mapas.colors
    val context = LocalContext.current
    val spoken = context.resources.getQuantityString(R.plurals.transit_walk_description, minutes, minutes)
    val text = stringResource(if (walkOnly) R.string.transit_walk_only_pill else R.string.transit_walk_pill, minutes)
    Row(
        modifier
            .clip(Mapas.shapes.pill)
            .background(colors.secondaryLabel.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .widthIn(min = 28.dp)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TransitModeIcons.walk, colors.label, 14.dp, Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(4.dp))
        BasicText(text, style = Mapas.typography.callout.copy(color = colors.label), maxLines = 1)
    }
}

@Composable
private fun WalkChip() {
    Box(
        Modifier.clip(Mapas.shapes.pill).background(Color(TransitController.WALK_COLOR)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("transit_walk_chip"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(stringResource(R.string.transit_chip_walk), style = Mapas.typography.callout.copy(color = Color.White), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------- card

@Composable
private fun ItineraryCard(controller: TransitController, it: Itinerary) {
    val s = controller.state
    val colors = Mapas.colors
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().testTag("transit_card")) {
        Box(
            Modifier.heightIn(min = target).clickable(role = Role.Button) { controller.back() }.padding(end = 12.dp).testTag("transit_back"),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText("‹ " + stringResource(R.string.transit_back), style = Mapas.typography.callout.copy(color = colors.accent))
        }
        val changes = if (it.isWalkOnly) stringResource(R.string.transit_walk_only)
        else if (it.transfers == 0) stringResource(R.string.transit_direct)
        else context.resources.getQuantityString(R.plurals.transit_transfers, it.transfers, it.transfers)
        val walking = stringResource(R.string.transit_walking, RouteFormat.distance(it.walkMeters.toDouble(), locale))
        val depart = TransitFormat.time(it.departAt, s.zone)
        val arrive = TransitFormat.time(it.arriveAt, s.zone)
        val duration = RouteFormat.duration(it.durationSec.toDouble())
        val summary = stringResource(R.string.transit_card_summary, duration, depart, arrive)
        Column(Modifier.semantics { contentDescription = summary }.testTag("transit_card_summary")) {
            BasicText(duration, style = Mapas.typography.largeTitle.copy(color = colors.label))
            BasicText(stringResource(R.string.transit_times, depart, arrive), style = Mapas.typography.body.copy(color = colors.label))
            BasicText("$changes · $walking", style = Mapas.typography.callout.copy(color = colors.secondaryLabel))
        }
        if (controller.canStartTrip && !it.isWalkOnly) {
            Spacer(Modifier.height(8.dp))
            val startDescription = stringResource(R.string.trip_start_description)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxOf(56.dp, target))
                    .clip(Mapas.shapes.control)
                    .background(colors.accent)
                    .clickable(role = Role.Button) { controller.startTrip() }
                    .semantics { contentDescription = startDescription }
                    .testTag("transit_trip_start"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(stringResource(R.string.trip_start), style = Mapas.typography.title.copy(color = colors.onAccent))
            }
        }
        Spacer(Modifier.height(8.dp))
        it.legs.forEachIndexed { i, leg ->
            when (leg) {
                is ItineraryLeg.Walk -> WalkRow(i, leg, s.zone, locale)
                is ItineraryLeg.Ride -> RideRow(i, leg, s.zone, context, rideRealTime(controller.realTime, leg), controller.realTime?.let { rt -> rt.enabled && rt.supports(leg) } == true)
            }
            if (i < it.legs.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.separator))
        }
    }
}

@Composable
private fun TimeColumn(text: String) {
    BasicText(
        text,
        style = Mapas.typography.body.copy(color = Mapas.colors.label),
        modifier = Modifier.widthIn(min = 56.dp),
    )
}

@Composable
private fun WalkRow(i: Int, leg: ItineraryLeg.Walk, zone: java.time.ZoneId, locale: Locale) {
    val place = leg.toName ?: stringResource(R.string.transit_place_destination)
    val text = stringResource(R.string.transit_leg_walk, RouteFormat.distance(leg.meters.toDouble(), locale), place)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("transit_leg_$i")) {
        TimeColumn(TransitFormat.time(leg.departAt, zone))
        BasicText(
            "$text · ${TransitFormat.walkMinutes(leg)} min",
            style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.weight(1f).testTag("transit_leg_${i}_text"),
        )
    }
}

@Composable
private fun RideRow(i: Int, leg: ItineraryLeg.Ride, zone: java.time.ZoneId, context: android.content.Context, rt: com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime?, realTimeOn: Boolean) {
    val colors = Mapas.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("transit_leg_$i")) {
        TimeColumn(TransitFormat.time(leg.departAt, zone))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineChip(leg.line, Modifier.testTag("transit_leg_${i}_chip"))
                if (leg.headsign.isNotBlank()) {
                    BasicText(
                        stringResource(R.string.transit_leg_towards, leg.headsign),
                        style = Mapas.typography.body.copy(color = colors.label),
                        modifier = Modifier.weight(1f).testTag("transit_leg_${i}_direction"),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                stringResource(R.string.transit_leg_board, leg.boarding.name),
                style = Mapas.typography.body.copy(color = colors.label),
                modifier = Modifier.testTag("transit_leg_${i}_board"),
            )
            BasicText(
                context.resources.getQuantityString(R.plurals.transit_leg_stops, leg.stopCount, leg.stopCount),
                style = Mapas.typography.callout.copy(color = colors.secondaryLabel),
                modifier = Modifier.testTag("transit_leg_${i}_stops"),
            )
            if (realTimeOn) RealTimeBlock(i, rt)
            Row(Modifier.fillMaxWidth()) {
                BasicText(
                    stringResource(R.string.transit_leg_alight, leg.alighting.name),
                    style = Mapas.typography.body.copy(color = colors.label),
                    modifier = Modifier.weight(1f).testTag("transit_leg_${i}_alight"),
                )
                BasicText(
                    TransitFormat.time(leg.arriveAt, zone),
                    style = Mapas.typography.body.copy(color = colors.label),
                    modifier = Modifier.testTag("transit_leg_${i}_arrive"),
                )
            }
        }
    }
}

/**
 * Under a Renfe train: "Real time (Renfe)" with its status when the train is in the feed, then any alert. When the train is
 * not in the feed (or nothing arrived yet) it says nothing about real time; the footer keeps the "theoretical" note true.
 */
@Composable
private fun RealTimeBlock(i: Int, rt: com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime?) {
    val res = LocalContext.current.resources
    val colors = Mapas.colors
    val status = RealTimeTexts.status(res, rt)
    if (status != null) {
        BasicText(
            stringResource(R.string.transit_rt_label) + " · " + status.text,
            style = Mapas.typography.callout.copy(color = if (status.level == RealTimeTexts.Level.ON_TIME) colors.secondaryLabel else colors.warning),
            modifier = Modifier.testTag("transit_leg_${i}_rt"),
        )
    }
    RealTimeTexts.details(res, rt).forEachIndexed { k, line ->
        BasicText(line, style = Mapas.typography.callout.copy(color = colors.warning), modifier = Modifier.testTag("transit_leg_${i}_rt_detail_$k"))
    }
}

// ---------------------------------------------------------------------------------------------------- footer

/** Honest note, validity of the data and the attribution the licences ask for (with links). */
@Composable
private fun TransitFooter(s: TransitState, realTimeNote: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().testTag("transit_footer")) {
        PanelNote(stringResource(if (realTimeNote) R.string.transit_rt_note else R.string.transit_theoretical_note), "transit_note")
        if (realTimeNote) PanelNote(stringResource(R.string.transit_rt_source), "transit_rt_source")
        val from = s.validFrom
        val to = s.validTo
        if (from != null && to != null) {
            PanelNote(stringResource(R.string.transit_valid_range, TransitFormat.date(from, locale), TransitFormat.date(to, locale)), "transit_validity")
        }
        if (s.unverifiedFeeds > 0) PanelNote(stringResource(R.string.transit_unverified), "transit_unverified")
        if (s.projectedFeeds > 0) PanelNote(stringResource(R.string.transit_projected_note), "transit_projected")
        AttributionLines(s.attribution, "transit_attribution")
    }
}

/** Attribution lines as text plus one tappable row per web address they contain. */
@Composable
fun AttributionLines(lines: List<String>, tagPrefix: String) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        lines.forEachIndexed { i, line ->
            BasicText(
                line,
                style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("${tagPrefix}_$i"),
            )
            TransitFormat.links(line).forEachIndexed { k, link ->
                val description = stringResource(R.string.transit_link_description, link)
                Box(
                    Modifier
                        .heightIn(min = target)
                        .clickable(role = Role.Button) { runCatching { uri.openUri(link) } }
                        .semantics { contentDescription = description }
                        .testTag("${tagPrefix}_${i}_link_$k"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(link, style = Mapas.typography.caption.copy(color = Mapas.colors.accent))
                }
            }
        }
    }
}
