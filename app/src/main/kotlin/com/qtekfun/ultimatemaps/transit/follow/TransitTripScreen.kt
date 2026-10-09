package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.FollowState
import com.qtekfun.ultimatemaps.core.transit.follow.PlanStatus
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripState
import com.qtekfun.ultimatemaps.nav.LightStatusBarIcons
import com.qtekfun.ultimatemaps.transit.LineChip
import com.qtekfun.ultimatemaps.transit.RealTimeTexts
import com.qtekfun.ultimatemaps.transit.TransitFormat
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import com.qtekfun.ultimatemaps.ui.theme.NavTheme
import com.qtekfun.ultimatemaps.ui.theme.NavigationTheme
import java.time.ZoneId

/** What the buttons of the trip screen do. */
class TransitTripActions(
    val onStop: () -> Unit = {},
    val onReplan: () -> Unit = {},
    /** "I'm on board" (true) / "I'm not on board" (false): the traveller's answer wins over what the fixes inferred. */
    val onBoard: (Boolean) -> Unit = {},
    val onGlove: (Boolean) -> Unit = {},
    val onVoice: (Boolean) -> Unit = {},
    val onResume: () -> Unit = {},
    val onDiscard: () -> Unit = {},
)

/**
 * The screen of the step-by-step public-transport trip, drawn over the map: a banner with the current instruction (walk,
 * board, next stop and how many are left, get off, change here, off the plan), a strip with the plan-versus-clock chip and the
 * signal and connection warnings, the Re-plan button when it applies, the stops of the current line with the position marked,
 * and the Stop button. Same look as the car navigation (night theme from [dark], glove mode from the same switch). The map is
 * behind it and is not part of this composable.
 */
@Composable
fun TransitTripScreen(ui: TransitTripUi, actions: TransitTripActions, dark: Boolean, modifier: Modifier = Modifier) {
    NavigationTheme(dark = dark, glove = ui.glove) {
        Box(modifier.fillMaxSize().testTag("trip_screen")) {
            val trip = ui.trip
            when {
                trip != null -> Following(ui, trip, actions)
                ui.resumable -> ResumeCard(actions)
            }
        }
    }
}

@Composable
private fun BoxScope.Following(ui: TransitTripUi, trip: TransitTripState, actions: TransitTripActions) {
    LightStatusBarIcons()
    val s = trip.follow
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
        Banner(ui, trip)
        Strips(trip, actions)
    }
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TripButton(
                    stringResource(if (ui.voiceOn) R.string.nav_ui_mute else R.string.nav_ui_unmute), { actions.onVoice(!ui.voiceOn) }, Modifier.testTag("trip_mute"),
                    container = if (ui.voiceOn) NavTheme.colors.panel else NavTheme.colors.laneRecommended,
                    content = if (ui.voiceOn) NavTheme.colors.onPanel else NavTheme.colors.onLaneRecommended,
                    description = stringResource(if (ui.voiceOn) R.string.nav_ui_mute_description else R.string.nav_ui_unmute_description),
                )
                TripButton(
                    stringResource(R.string.nav_ui_glove), { actions.onGlove(!ui.glove) }, Modifier.testTag("trip_glove"),
                    container = if (ui.glove) NavTheme.colors.laneRecommended else NavTheme.colors.panel,
                    content = if (ui.glove) NavTheme.colors.onLaneRecommended else NavTheme.colors.onPanel,
                    description = stringResource(if (ui.glove) R.string.nav_ui_glove_on_description else R.string.nav_ui_glove_off_description),
                )
            }
        }
        BottomPanel(ui, trip, s, actions)
    }
}

// ---------------------------------------------------------------- banner

@Composable
private fun Banner(ui: TransitTripUi, trip: TransitTripState) {
    val c = NavTheme.colors
    val res = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val s = trip.follow
    val text = TransitTripTexts.instruction(res, s, ZoneId.of(trip.zoneId), locale)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(c.banner)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("trip_banner"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val line = s.line
            if (line != null) {
                LineChip(line, Modifier.testTag("trip_line_chip"))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                BasicText(
                    text.title,
                    style = Mapas.typography.title.copy(color = c.onBanner, fontSize = if (ui.glove) 26.sp else 22.sp, fontWeight = FontWeight.Bold),
                    maxLines = 3,
                    modifier = Modifier.testTag("trip_title"),
                )
                text.detail?.let {
                    BasicText(
                        it,
                        style = Mapas.typography.body.copy(color = c.onBannerSecondary, fontSize = if (ui.glove) 20.sp else 17.sp),
                        maxLines = 3,
                        modifier = Modifier.testTag("trip_detail"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Strips(trip: TransitTripState, actions: TransitTripActions) {
    val c = NavTheme.colors
    val res = LocalContext.current.resources
    val s = trip.follow
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TransitTripTexts.planChip(res, s)?.let { chip ->
            val bg = when (s.plan) {
                PlanStatus.ON_PLAN -> c.statusInfo
                PlanStatus.AHEAD -> c.statusInfo
                PlanStatus.BEHIND -> c.statusWarning
            }
            Strip(chip, bg, "trip_plan_chip")
        }
        // Real time (Renfe, opt-in): the train's own status and any alert, only for the ride this state is about.
        RealTimeTexts.status(res, s.realTime)?.let { st ->
            val bg = when (st.level) {
                RealTimeTexts.Level.ON_TIME -> c.statusInfo
                RealTimeTexts.Level.LATE -> c.statusWarning
                RealTimeTexts.Level.CANCELLED -> c.statusDanger
            }
            Strip(res.getString(R.string.transit_rt_label) + " · " + st.text, bg, "trip_rt_status")
        }
        RealTimeTexts.details(res, s.realTime).forEachIndexed { k, line -> Strip(line, c.statusWarning, "trip_rt_detail_$k") }
        TransitTripTexts.signal(res, s)?.let { Strip(it, c.statusWarning, "trip_signal") }
        TransitTripTexts.connection(res, s)?.let { Strip(it, c.statusDanger, "trip_connection") }
        if (trip.replanning) Strip(stringResource(R.string.trip_replanning), c.statusInfo, "trip_replanning")
        if (trip.replanFailed) Strip(stringResource(R.string.trip_replan_failed), c.statusDanger, "trip_replan_failed")
        val aboard = s.phase == FollowPhase.ON_BOARD || s.phase == FollowPhase.ALIGHT_NEXT
        if (aboard || s.phase == FollowPhase.WAITING || s.phase == FollowPhase.BEFORE_START || s.phase == FollowPhase.TRANSFER || s.phase == FollowPhase.OFF_PLAN) {
            TripButton(
                stringResource(if (aboard) R.string.trip_not_on_board else R.string.trip_on_board), { actions.onBoard(!aboard) },
                Modifier.testTag("trip_on_board"),
                container = c.statusInfo, content = c.onStatus,
                description = stringResource(if (aboard) R.string.trip_not_on_board_description else R.string.trip_on_board_description),
            )
        }
        if (s.canReplan && !trip.replanning) {
            TripButton(
                stringResource(R.string.trip_replan), actions.onReplan, Modifier.testTag("trip_replan"),
                container = Mapas.colors.accent, content = Mapas.colors.onAccent,
                description = stringResource(R.string.trip_replan_description),
            )
        }
    }
}

@Composable
private fun Strip(text: String, background: Color, tag: String) {
    val c = NavTheme.colors
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(background).padding(horizontal = 14.dp, vertical = 8.dp)) {
        BasicText(
            text, style = Mapas.typography.callout.copy(color = c.onStatus, fontWeight = FontWeight.SemiBold), maxLines = 2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        )
    }
}

// ---------------------------------------------------------------- bottom

@Composable
private fun BottomPanel(ui: TransitTripUi, trip: TransitTripState, s: FollowState, actions: TransitTripActions) {
    val c = NavTheme.colors
    val d = NavTheme.dimens
    val zone = ZoneId.of(trip.zoneId)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.panel)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("trip_bottom"),
    ) {
        StopList(trip, s, zone)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    stringResource(R.string.trip_arrive_at, TransitFormat.time(s.etaAt, zone)),
                    style = Mapas.typography.title.copy(color = c.onPanel, fontSize = if (ui.glove) 26.sp else 22.sp),
                    modifier = Modifier.testTag("trip_eta"),
                )
                BasicText(
                    RealTimeTexts.note(LocalContext.current.resources, s.realTime, R.string.trip_scheduled_note),
                    style = Mapas.typography.caption.copy(color = c.onPanelSecondary),
                    modifier = Modifier.testTag("trip_note"),
                )
            }
            val arrived = s.phase == FollowPhase.ARRIVED
            TripButton(
                stringResource(if (arrived) R.string.trip_done else R.string.trip_stop), actions.onStop,
                Modifier.heightIn(min = d.touchTarget + 8.dp).widthIn(min = 96.dp).testTag("trip_stop"),
                container = c.stopButton, content = c.onStopButton,
                description = stringResource(if (arrived) R.string.trip_done else R.string.trip_stop_description),
            )
        }
    }
}

/** The ride shown in the list: the one in progress, else the next one to board. */
private fun rideToShow(trip: TransitTripState): ItineraryLeg.Ride? {
    val legs = trip.itinerary.legs
    val i = trip.follow.legIndex
    return (legs.getOrNull(i) as? ItineraryLeg.Ride) ?: legs.drop(i + 1).filterIsInstance<ItineraryLeg.Ride>().firstOrNull()
}

@Composable
private fun StopList(trip: TransitTripState, s: FollowState, zone: ZoneId) {
    val ride = rideToShow(trip) ?: return
    val c = NavTheme.colors
    val onThisRide = trip.itinerary.legs.getOrNull(s.legIndex) === ride && (s.phase == FollowPhase.ON_BOARD || s.phase == FollowPhase.ALIGHT_NEXT || s.phase == FollowPhase.OFF_PLAN && s.nextStopIndex >= 0)
    val next = if (onThisRide) s.nextStopIndex else 0
    val state = rememberLazyListState()
    LaunchedEffect(next) { state.scrollToItem((next - 1).coerceAtLeast(0)) }
    val listDescription = stringResource(R.string.trip_stops_description)
    val passedWord = stringResource(R.string.trip_stop_passed)
    val nextWord = stringResource(R.string.trip_stop_next)
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 168.dp).semantics { contentDescription = listDescription }.testTag("trip_stops"),
        state = state,
    ) {
        itemsIndexed(ride.stops) { i, stop ->
            val passed = onThisRide && i < next
            val isNext = i == next
            val time = TransitFormat.time(if (i == 0) stop.departAt else stop.arriveAt, zone)
            val marker = if (passed) passedWord else if (isNext) nextWord else ""
            val description = stringResource(R.string.trip_stop_row_description, stop.name, marker.ifBlank { "-" }, time)
            val color = if (passed) c.onPanelSecondary else c.onPanel
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .let { if (isNext) it.clip(RoundedCornerShape(8.dp)).background(c.laneRecommended.copy(alpha = 0.35f)) else it }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .testTag("trip_stop_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    if (passed) "✓" else if (isNext) "▶" else "•",
                    style = Mapas.typography.body.copy(color = color),
                    modifier = Modifier.width(24.dp),
                )
                BasicText(
                    stop.name,
                    style = Mapas.typography.body.copy(color = color, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("trip_stop_${i}_name"),
                )
                BasicText(time, style = Mapas.typography.body.copy(color = color), modifier = Modifier.testTag("trip_stop_${i}_time"))
            }
        }
    }
}

// ---------------------------------------------------------------- resume

@Composable
private fun BoxScope.ResumeCard(actions: TransitTripActions) {
    val c = NavTheme.colors
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.banner)
            .padding(16.dp)
            .testTag("trip_resume_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.trip_resume_title), style = Mapas.typography.title.copy(color = c.onBanner))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripButton(stringResource(R.string.trip_resume), actions.onResume, Modifier.weight(1f).testTag("trip_resume"), container = Mapas.colors.accent, content = Mapas.colors.onAccent)
            TripButton(stringResource(R.string.trip_discard), actions.onDiscard, Modifier.weight(1f).testTag("trip_discard"))
        }
    }
}

/** Rounded text button of the trip screen; never smaller than the screen's touch target (56 dp in glove mode). */
@Composable
private fun TripButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = NavTheme.colors.panel,
    content: Color = NavTheme.colors.onPanel,
    description: String? = null,
) {
    val d = NavTheme.dimens
    Box(
        modifier
            .heightIn(min = d.touchTarget)
            .widthIn(min = d.touchTarget)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .let { m -> if (description != null) m.semantics { contentDescription = description } else m }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Mapas.typography.body.copy(color = content, fontWeight = FontWeight.SemiBold, fontSize = if (d.touchTarget >= 56.dp) 20.sp else 17.sp))
    }
}

/**
 * The trip screen with its window effects: the screen stays on while the trip runs (like the car navigation), the activity
 * shows over the lock screen, and the itinerary is drawn on the map ([showItinerary]) when it starts or is re-planned.
 */
@Composable
fun TransitTripOverlay(host: TransitTripHost, dark: Boolean, showItinerary: (List<com.qtekfun.ultimatemaps.core.map.TransitMapLeg>) -> Unit) {
    val ui by host.ui.collectAsState()
    val trip = ui.trip
    com.qtekfun.ultimatemaps.nav.KeepScreenOn(trip != null && trip.follow.phase != FollowPhase.ARRIVED)
    com.qtekfun.ultimatemaps.nav.ShowOverLockScreen(trip != null)
    LaunchedEffect(trip?.itinerary) {
        trip?.itinerary?.let { showItinerary(com.qtekfun.ultimatemaps.transit.TransitController.mapLegs(it)) }
    }
    TransitTripScreen(
        ui = ui,
        actions = TransitTripActions(
            onStop = host::stop, onReplan = { host.replan() }, onBoard = host::setOnBoard, onGlove = host::setGlove, onVoice = host::setVoice,
            onResume = host::resume, onDiscard = host::discard,
        ),
        dark = dark,
    )
}
