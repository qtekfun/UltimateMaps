package com.qtekfun.ultimatemaps.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.zbe.ZbeCrossing
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** The zone names to show: distinct, in route order, at most [MAX_NAMES]. */
internal fun lowEmissionNames(crossings: List<ZbeCrossing>): List<String> =
    crossings.map { it.zone.label }.filter { it.isNotBlank() }.distinct().take(MAX_NAMES)

private const val MAX_NAMES = 3

/**
 * "This route enters a low-emission zone: check the access rules of the city", with the zone names, the restriction text when
 * OpenStreetMap carries one (shown as such, never interpreted) and the honesty line about the data. Draws nothing without
 * crossings. It never says that the vehicle is allowed or banned.
 */
@Composable
internal fun LowEmissionWarning(crossings: List<ZbeCrossing>) {
    if (crossings.isEmpty()) return
    val names = lowEmissionNames(crossings)
    val restriction = crossings.map { it.zone.restriction }.firstOrNull { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("route_zbe_warning")) {
        BasicText(
            stringResource(R.string.zbe_route_warning),
            style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
            modifier = Modifier.testTag("route_zbe_text"),
        )
        if (names.isNotEmpty()) {
            BasicText(
                stringResource(R.string.zbe_route_zones, names.joinToString(", ")),
                style = Mapas.typography.callout.copy(color = Mapas.colors.label),
                modifier = Modifier.testTag("route_zbe_names"),
            )
        }
        if (restriction != null) {
            BasicText(
                stringResource(R.string.zbe_route_tagged, restriction),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("route_zbe_tagged"),
            )
        }
        BasicText(
            stringResource(R.string.zbe_data_notice),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("route_zbe_notice"),
        )
    }
}
