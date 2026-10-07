package com.qtekfun.mapas.nav

import android.content.res.Resources
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.nav.NavProblem
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import com.qtekfun.mapas.route.RouteFormat
import com.qtekfun.mapas.ui.NavIcons
import com.qtekfun.mapas.ui.theme.Mapas
import com.qtekfun.mapas.ui.theme.NavTheme
import com.qtekfun.mapas.ui.theme.NavigationTheme

/** What the buttons of the navigation screen do. */
class NavActions(
    val onStop: () -> Unit = {},
    val onRecenter: () -> Unit = {},
    val onGlove: (Boolean) -> Unit = {},
    val onFaster: () -> Unit = {},
    val onSlower: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onDiscard: () -> Unit = {},
)

/** The instruction of a maneuver ("Turn left onto Calle de Alcalá"), shared by the banner and the tests. */
object NavTexts {
    fun instruction(res: Resources, m: Maneuver): String {
        val exit = m.roundaboutExit
        if (m.type == TurnType.ROUNDABOUT_ENTER && exit != null && exit > 0) return res.getString(R.string.nav_ui_roundabout_exit, exit)
        val turn = res.getString(NavNotificationTexts.turnRes(m.type))
        val street = m.streetName?.takeIf { it.isNotBlank() }
        return if (street != null && m.type != TurnType.ARRIVE && m.type != TurnType.ARRIVE_LEFT && m.type != TurnType.ARRIVE_RIGHT) {
            res.getString(R.string.nav_onto_street, turn, street)
        } else {
            turn
        }
    }
}

/**
 * The navigation screen, drawn over the map: banner with the next maneuver (icon, distance, street), the one after
 * it, the lanes, a status strip for off route / recalculating / no signal / stop reached, speed limit and current
 * speed, the arrival and remaining trip with the Stop button, "recenter" when the user moved the map, the
 * simulation controls, the arrival summary, and the offer to resume a trip interrupted by the process dying.
 * The map itself is behind it and is not part of this composable.
 */
@Composable
fun NavScreen(ui: NavUi, actions: NavActions, dark: Boolean, modifier: Modifier = Modifier) {
    NavigationTheme(dark = dark, glove = ui.glove) {
        Box(modifier.fillMaxSize().testTag("nav_screen")) {
            val nav = ui.nav
            when {
                ui.phase == NavPhase.ARRIVED && ui.summary != null -> ArrivalSummary(ui, actions)
                ui.phase != null && nav != null -> Driving(ui, nav, actions)
                ui.phase != null -> StartingBanner()
                ui.resumable -> ResumeCard(actions)
            }
        }
    }
}

@Composable
private fun BoxScope.Driving(ui: NavUi, nav: NavState, actions: NavActions) {
    val c = NavTheme.colors
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
        Banner(ui, nav)
        StatusStrip(ui)
    }
    GloveToggle(ui, actions, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 168.dp, end = 12.dp))
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
            SpeedCluster(nav)
            Spacer(Modifier.weight(1f))
            if (!ui.following) {
                NavButton(
                    stringResource(R.string.nav_ui_recenter), actions.onRecenter, Modifier.testTag("nav_recenter"),
                    container = c.statusInfo, content = c.onStatus,
                )
            }
        }
        if (ui.simulated) SimulationBar(ui, actions)
        BottomPanel(ui, nav, actions)
    }
}

@Composable
private fun StartingBanner() {
    val c = NavTheme.colors
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(c.banner).padding(16.dp).testTag("nav_starting"),
    ) {
        BasicText(stringResource(R.string.nav_ui_follow_route), style = Mapas.typography.title.copy(color = c.onBanner))
    }
}

// ---------------------------------------------------------------- banner

@Composable
private fun Banner(ui: NavUi, nav: NavState) {
    val c = NavTheme.colors
    val d = NavTheme.dimens
    val res = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val next = nav.nextManeuver
    val glove = ui.glove
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(c.banner)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("nav_banner"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val type = next?.maneuver?.type ?: TurnType.STRAIGHT
            val instruction = if (next != null) NavTexts.instruction(res, next.maneuver) else stringResource(R.string.nav_ui_follow_route)
            Image(
                NavIcons.turn(type), contentDescription = stringResource(R.string.nav_ui_icon_turn, instruction),
                colorFilter = ColorFilter.tint(c.onBanner), modifier = Modifier.size(d.bannerIcon).testTag("nav_turn_icon"),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (next != null) {
                    BasicText(
                        RouteFormat.distance(next.distanceMeters.coerceAtLeast(0.0), locale),
                        style = Mapas.typography.largeTitle.copy(color = c.onBanner, fontSize = if (glove) 40.sp else 34.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.testTag("nav_distance"),
                    )
                }
                BasicText(
                    instruction,
                    style = Mapas.typography.title.copy(color = c.onBanner, fontSize = if (glove) 24.sp else 20.sp),
                    maxLines = 2,
                    modifier = Modifier.testTag("nav_instruction"),
                )
            }
        }
        nav.followingManeuver?.let { f ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("nav_then")) {
                BasicText(stringResource(R.string.nav_ui_then), style = Mapas.typography.callout.copy(color = c.onBannerSecondary))
                Spacer(Modifier.width(8.dp))
                Image(NavIcons.turn(f.maneuver.type), null, colorFilter = ColorFilter.tint(c.onBannerSecondary), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                BasicText(
                    NavTexts.instruction(res, f.maneuver),
                    style = Mapas.typography.callout.copy(color = c.onBannerSecondary), maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("nav_then_text"),
                )
                BasicText(RouteFormat.distance(f.distanceMeters.coerceAtLeast(0.0), locale), style = Mapas.typography.callout.copy(color = c.onBannerSecondary))
            }
        }
        if (nav.lanes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Lanes(nav.lanes)
        }
    }
}

@Composable
private fun Lanes(lanes: List<Lane>) {
    val c = NavTheme.colors
    val d = NavTheme.dimens
    val label = stringResource(R.string.nav_ui_lanes_description)
    val recommended = stringResource(R.string.nav_ui_lane_recommended)
    val other = stringResource(R.string.nav_ui_lane_other)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("nav_lanes"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        lanes.forEachIndexed { i, lane ->
            val bg = if (lane.recommended) c.laneRecommended else c.laneIdle
            val fg = if (lane.recommended) c.onLaneRecommended else c.onLaneIdle
            Box(
                Modifier
                    .size(d.laneBox)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .semantics { contentDescription = if (lane.recommended) recommended else other }
                    .testTag(if (lane.recommended) "nav_lane_${i}_recommended" else "nav_lane_$i"),
                contentAlignment = Alignment.Center,
            ) {
                for (dir in lane.directions) {
                    Image(NavIcons.lane(dir), null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(d.laneBox - 8.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusStrip(ui: NavUi) {
    val c = NavTheme.colors
    val problem = ui.problem
    val (text, bg) = when {
        problem == NavProblem.LOCATION_PERMISSION -> stringResource(R.string.nav_problem_permission) to c.statusDanger
        problem == NavProblem.LOCATION_DISABLED -> stringResource(R.string.nav_problem_gps_off) to c.statusDanger
        ui.phase == NavPhase.OFF_ROUTE -> stringResource(R.string.nav_ui_phase_off_route) to c.statusDanger
        ui.phase == NavPhase.REROUTING -> stringResource(R.string.nav_recalculating) to c.statusWarning
        ui.phase == NavPhase.NO_SIGNAL -> stringResource(R.string.nav_ui_phase_no_signal) to c.statusWarning
        ui.phase == NavPhase.STOP_REACHED -> stringResource(R.string.nav_ui_phase_stop_reached) to c.statusInfo
        else -> return
    }
    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        BasicText(
            text, style = Mapas.typography.callout.copy(color = c.onStatus, fontWeight = FontWeight.SemiBold), maxLines = 2,
            // A change of situation is announced by the screen reader without taking the focus.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("nav_status"),
        )
    }
}

// ---------------------------------------------------------------- speed

@Composable
private fun SpeedCluster(nav: NavState) {
    val c = NavTheme.colors
    val d = NavTheme.dimens
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nav.speedLimitKmh?.let { limit ->
            val description = stringResource(R.string.nav_ui_limit_description, limit)
            Box(
                Modifier.size(d.speedSign).clip(CircleShape).background(c.limitRing).padding(7.dp).clip(CircleShape)
                    .background(c.limitFace).semantics { contentDescription = description }.testTag("nav_limit"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(limit.toString(), style = Mapas.typography.title.copy(color = c.onLimitFace, fontWeight = FontWeight.Bold, fontSize = 24.sp))
            }
        }
        val kmh = NavFormat.speedKmh(nav.speedMps)
        val over = nav.overSpeedLimit
        val speedDescription = stringResource(R.string.nav_ui_speed_description, kmh)
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (over) c.overLimit else c.panel)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = speedDescription }
                .testTag(if (over) "nav_speed_over" else "nav_speed"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(kmh.toString(), style = Mapas.typography.title.copy(color = if (over) c.onOverLimit else c.onPanel, fontWeight = FontWeight.Bold, fontSize = 26.sp))
            BasicText(stringResource(R.string.nav_ui_speed_unit), style = Mapas.typography.caption.copy(color = if (over) c.onOverLimit else c.onPanelSecondary))
            if (over) {
                // Not only a colour: the words say it too.
                BasicText(
                    stringResource(R.string.nav_ui_over_limit),
                    style = Mapas.typography.caption.copy(color = c.onOverLimit, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.widthIn(max = 110.dp).testTag("nav_over_limit"),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- bottom

@Composable
private fun BottomPanel(ui: NavUi, nav: NavState, actions: NavActions) {
    val c = NavTheme.colors
    val d = NavTheme.dimens
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.panel)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("nav_bottom"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                if (ui.etaMillis > 0) stringResource(R.string.nav_ui_arrive_at, NavFormat.clockTime(ui.etaMillis, locale)) else "",
                style = Mapas.typography.title.copy(color = c.onPanel, fontSize = if (ui.glove) 26.sp else 22.sp),
                modifier = Modifier.testTag("nav_eta"),
            )
            BasicText(
                stringResource(R.string.nav_remaining, RouteFormat.distance(nav.remainingMeters, locale), RouteFormat.duration(nav.remainingSeconds)),
                style = Mapas.typography.body.copy(color = c.onPanelSecondary),
                modifier = Modifier.testTag("nav_remaining"),
            )
            // ODbL: the attribution stays visible while navigating.
            BasicText(
                stringResource(R.string.attribution_osm),
                style = Mapas.typography.caption.copy(color = c.onPanelSecondary),
                modifier = Modifier.testTag("nav_attribution"),
            )
        }
        NavButton(
            stringResource(R.string.nav_ui_stop), actions.onStop,
            Modifier.heightIn(min = d.touchTarget + 8.dp).widthIn(min = 96.dp).testTag("nav_stop"),
            container = c.stopButton, content = c.onStopButton,
            description = stringResource(R.string.nav_ui_stop_description),
        )
    }
}

@Composable
private fun SimulationBar(ui: NavUi, actions: NavActions) {
    val c = NavTheme.colors
    Row(
        Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(c.panel).padding(horizontal = 12.dp, vertical = 6.dp).testTag("nav_sim_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.nav_ui_simulating), style = Mapas.typography.callout.copy(color = c.onPanelSecondary))
        NavButton("−", actions.onSlower, Modifier.testTag("nav_sim_slower"), description = stringResource(R.string.nav_ui_sim_slower))
        BasicText(
            stringResource(R.string.nav_ui_sim_speed, ui.simulationSpeedKmh),
            style = Mapas.typography.body.copy(color = c.onPanel, fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("nav_sim_speed"),
        )
        NavButton("+", actions.onFaster, Modifier.testTag("nav_sim_faster"), description = stringResource(R.string.nav_ui_sim_faster))
    }
}

@Composable
private fun GloveToggle(ui: NavUi, actions: NavActions, modifier: Modifier) {
    val c = NavTheme.colors
    val description = stringResource(if (ui.glove) R.string.nav_ui_glove_on_description else R.string.nav_ui_glove_off_description)
    NavButton(
        stringResource(R.string.nav_ui_glove), { actions.onGlove(!ui.glove) }, modifier.testTag("nav_glove"),
        container = if (ui.glove) c.laneRecommended else c.panel, content = if (ui.glove) c.onLaneRecommended else c.onPanel,
        description = description,
    )
}

// ---------------------------------------------------------------- arrival and resume

@Composable
private fun BoxScope.ArrivalSummary(ui: NavUi, actions: NavActions) {
    val c = NavTheme.colors
    val summary = ui.summary ?: return
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.panel)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(20.dp)
            .testTag("nav_summary"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.nav_ui_summary_title), style = Mapas.typography.largeTitle.copy(color = c.onPanel), modifier = Modifier.testTag("nav_summary_title"))
        BasicText(
            stringResource(R.string.nav_ui_summary_body, RouteFormat.distance(summary.distanceMeters, locale), NavFormat.tripDuration(summary.durationMillis)),
            style = Mapas.typography.title.copy(color = c.onPanel), modifier = Modifier.testTag("nav_summary_body"),
        )
        if (summary.simulated) {
            BasicText(stringResource(R.string.nav_ui_summary_simulated), style = Mapas.typography.callout.copy(color = c.onPanelSecondary), modifier = Modifier.testTag("nav_summary_simulated"))
        }
        NavButton(
            stringResource(R.string.nav_ui_exit), actions.onExit, Modifier.fillMaxWidth().testTag("nav_exit"),
            container = Mapas.colors.accent, content = Mapas.colors.onAccent,
        )
    }
}

@Composable
private fun BoxScope.ResumeCard(actions: NavActions) {
    val c = NavTheme.colors
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.banner)
            .padding(16.dp)
            .testTag("nav_resume_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.nav_ui_resume_title), style = Mapas.typography.title.copy(color = c.onBanner))
        BasicText(stringResource(R.string.nav_ui_resume_body), style = Mapas.typography.body.copy(color = c.onBannerSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavButton(stringResource(R.string.nav_ui_resume), actions.onResume, Modifier.weight(1f).testTag("nav_resume"), container = Mapas.colors.accent, content = Mapas.colors.onAccent)
            NavButton(stringResource(R.string.nav_ui_discard), actions.onDiscard, Modifier.weight(1f).testTag("nav_discard"))
        }
    }
}

/** Rounded text button of the navigation screen; never smaller than the screen's touch target (56 dp in glove mode). */
@Composable
private fun NavButton(
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
        BasicText(text, style = Mapas.typography.body.copy(color = content, fontWeight = FontWeight.SemiBold, fontSize = if (NavTheme.dimens.touchTarget >= 56.dp) 20.sp else 17.sp))
    }
}
