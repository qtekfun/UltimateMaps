package com.qtekfun.ultimatemaps.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.places.PanelNote
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Texts of an alternative's difference to the main route ("+4 min", "-1.2 km"). Pure, so it is unit-tested. */
object RouteDelta {
    private const val MINUS = "−"

    /** "+4 min", "-1 h 5 min", or null when the rounded difference is under a minute. */
    fun duration(deltaSeconds: Double): String? {
        val minutes = (abs(deltaSeconds) / 60).roundToInt()
        if (minutes == 0) return null
        return (if (deltaSeconds > 0) "+" else MINUS) + RouteFormat.duration(minutes * 60.0)
    }

    /** "+2.1 km" / "-350 m", or null when the rounded difference is zero. */
    fun distance(deltaMeters: Double, locale: Locale): String? {
        val text = RouteFormat.distance(abs(deltaMeters), locale)
        if (text.startsWith("0 ")) return null
        return (if (deltaMeters > 0) "+" else MINUS) + text
    }
}

/**
 * Under the route result: a button that looks for routes under one more restriction, then a list of the main route and
 * what was found, each with its time and the difference to the main route. Tapping a row selects that route (the map
 * line and "Start" follow). Alternatives are labelled by the restriction because the core has no other kind.
 */
@Composable
fun AlternativesSection(route: RoutePreviewController, modifier: Modifier = Modifier) {
    val s = route.state
    if (s.status != RouteStatus.DONE || s.pickingOrigin) return
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.fillMaxWidth().testTag("route_alternatives")) {
        when (s.alternativesStatus) {
            AlternativesStatus.NONE -> {
                if (RoutePreviewController.alternativeKinds(s.profile, s.options).isNotEmpty()) {
                    Row(
                        Modifier
                            .heightIn(min = Mapas.dimens.touchTarget)
                            .clickable(role = Role.Button, onClick = route::findAlternatives)
                            .padding(horizontal = 4.dp)
                            .testTag("route_alt_find"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            stringResource(R.string.route_alt_find),
                            style = Mapas.typography.callout.copy(color = Mapas.colors.accent),
                        )
                    }
                }
            }
            AlternativesStatus.FINDING -> PanelNote(stringResource(R.string.route_alt_finding), "route_alt_status")
            AlternativesStatus.DONE ->
                if (s.alternatives.isEmpty()) PanelNote(stringResource(R.string.route_alt_none), "route_alt_status")
        }
        if (s.alternatives.isNotEmpty()) {
            BasicText(
                stringResource(R.string.route_alt_title),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            AlternativeRow(
                label = stringResource(R.string.route_alt_main),
                detail = RouteFormat.duration(s.baseDurationSeconds) + " · " + RouteFormat.distance(s.baseDistanceMeters, locale),
                selected = s.selectedAlternative == null,
                onClick = { route.selectAlternative(null) },
                tag = "route_alt_main",
            )
            s.alternatives.forEachIndexed { i, alt ->
                val time = RouteDelta.duration(alt.durationSeconds - s.baseDurationSeconds)
                    ?: stringResource(R.string.route_alt_same_time)
                val dist = RouteDelta.distance(alt.distanceMeters - s.baseDistanceMeters, locale)
                AlternativeRow(
                    label = stringResource(labelOf(alt.kind)),
                    detail = RouteFormat.duration(alt.durationSeconds) + " · " + RouteFormat.distance(alt.distanceMeters, locale) +
                        " (" + listOfNotNull(time, dist).joinToString(", ") + ")",
                    selected = s.selectedAlternative == i,
                    onClick = { route.selectAlternative(i) },
                    tag = "route_alt_$i",
                )
            }
        }
    }
}

private fun labelOf(kind: AlternativeKind): Int = when (kind) {
    AlternativeKind.AVOID_MOTORWAYS -> R.string.route_alt_avoid_motorways
    AlternativeKind.AVOID_TOLLS -> R.string.route_alt_avoid_tolls
    AlternativeKind.AVOID_UNPAVED -> R.string.route_alt_avoid_unpaved
    AlternativeKind.AVOID_FERRIES -> R.string.route_alt_avoid_ferries
}

@Composable
private fun AlternativeRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = Mapas.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Mapas.dimens.touchTarget)
            .clip(Mapas.shapes.control)
            .background(if (selected) colors.field else colors.sheet)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                label,
                style = Mapas.typography.body.copy(color = if (selected) colors.accent else colors.label),
                maxLines = 1,
            )
            BasicText(detail, style = Mapas.typography.callout.copy(color = colors.secondaryLabel), maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
    }
}
