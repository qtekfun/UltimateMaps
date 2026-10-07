package com.qtekfun.ultimatemaps.route

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.routing.BikeCycleways
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** String resources for each [BikeCycleways] level (see `strings_bike.xml`). */
object BikeCyclewaysText {
    /** Full label, used in Settings. */
    @StringRes
    fun full(level: BikeCycleways): Int = when (level) {
        BikeCycleways.OFF -> R.string.bike_cycleways_full_off
        BikeCycleways.PREFER -> R.string.bike_cycleways_full_prefer
        BikeCycleways.STRONGLY_PREFER -> R.string.bike_cycleways_full_strong
        BikeCycleways.ONLY -> R.string.bike_cycleways_full_only
    }

    /** One-word label for the chips of the route panel. */
    @StringRes
    fun short(level: BikeCycleways): Int = when (level) {
        BikeCycleways.OFF -> R.string.bike_cycleways_off
        BikeCycleways.PREFER -> R.string.bike_cycleways_prefer
        BikeCycleways.STRONGLY_PREFER -> R.string.bike_cycleways_strong
        BikeCycleways.ONLY -> R.string.bike_cycleways_only
    }

    /** What the selected level does, shown under the chips. */
    @StringRes
    fun caption(level: BikeCycleways): Int = when (level) {
        BikeCycleways.OFF -> R.string.bike_cycleways_caption_off
        BikeCycleways.PREFER -> R.string.bike_cycleways_caption_prefer
        BikeCycleways.STRONGLY_PREFER -> R.string.bike_cycleways_caption_strong
        BikeCycleways.ONLY -> R.string.bike_cycleways_caption_only
    }
}

/** Route panel row for the bike profile: how strongly to prefer cycle infrastructure for this trip. */
@Composable
internal fun BikeCyclewaysRow(route: RoutePreviewController) {
    val options = route.state.options
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("route_bike_cycleways")) {
        BasicText(stringResource(R.string.bike_cycleways_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        Spacer(Modifier.height(6.dp))
        // 2 x 2 so each chip has room for its label (and the check mark) at phone width.
        BikeCycleways.entries.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { level ->
                    OptionChip(
                        stringResource(BikeCyclewaysText.short(level)),
                        options.bikeCycleways == level,
                        true,
                        "bike_cycleways_${level.name.lowercase()}",
                        Modifier.weight(1f),
                    ) { route.setOptions(options.copy(bikeCycleways = level)) }
                }
            }
        }
        BasicText(
            stringResource(BikeCyclewaysText.caption(options.bikeCycleways)),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 6.dp).testTag("route_bike_cycleways_caption"),
        )
    }
}
