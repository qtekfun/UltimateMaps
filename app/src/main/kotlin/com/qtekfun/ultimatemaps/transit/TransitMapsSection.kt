package com.qtekfun.ultimatemaps.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.qtekfun.ultimatemaps.core.transit.TransitFailure
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.time.LocalDate

/**
 * "Public transport" section of the Maps screen: the cities the catalog offers, their validity and the Download / Update /
 * Delete actions. Nothing is downloaded until the user presses Download. An expired entry says so and cannot be installed.
 */
@Composable
fun TransitMapsSection(rows: List<TransitCityRow>, offline: Boolean, onDownload: (String) -> Unit, onDelete: (String) -> Unit, showTitle: Boolean = true) {
    if (rows.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("transit_maps")) {
        if (showTitle) {
            BasicText(stringResource(R.string.transit_maps_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(4.dp))
        }
        BasicText(
            stringResource(R.string.transit_maps_intro),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("transit_maps_intro"),
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row -> CityRow(row, offline, locale, onDownload, onDelete) }
        }
    }
}

@Composable
private fun CityRow(row: TransitCityRow, offline: Boolean, locale: java.util.Locale, onDownload: (String) -> Unit, onDelete: (String) -> Unit) {
    val colors = Mapas.colors
    val range = validityText(row, locale)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.control)
            .background(colors.field)
            .padding(12.dp)
            .testTag("transit_city_${row.id}"),
    ) {
        BasicText(row.city, style = Mapas.typography.body.copy(color = colors.label))
        BasicText(range, style = Mapas.typography.callout.copy(color = if (row.expired) colors.accent else colors.secondaryLabel), modifier = Modifier.testTag("transit_city_${row.id}_validity"))
        if (row.sizeBytes > 0 && (!row.installed || row.updateAvailable)) {
            val size = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, row.sizeBytes)
            BasicText(stringResource(R.string.transit_maps_size, size), style = Mapas.typography.callout.copy(color = colors.secondaryLabel), modifier = Modifier.testTag("transit_city_${row.id}_size"))
        }
        if (row.installed && !row.updateAvailable) {
            BasicText(stringResource(R.string.transit_maps_installed), style = Mapas.typography.callout.copy(color = colors.secondaryLabel), modifier = Modifier.testTag("transit_city_${row.id}_installed"))
        }
        if (row.updateAvailable) {
            BasicText(stringResource(R.string.transit_maps_update_available), style = Mapas.typography.callout.copy(color = colors.secondaryLabel))
        }
        row.failure?.let {
            BasicText(failureText(it), style = Mapas.typography.callout.copy(color = colors.secondaryLabel), modifier = Modifier.testTag("transit_city_${row.id}_failure"))
        }
        if (offline && !row.installed) {
            BasicText(stringResource(R.string.transit_fail_offline), style = Mapas.typography.callout.copy(color = colors.secondaryLabel))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (row.downloading) {
                BasicText(stringResource(R.string.transit_maps_downloading), style = Mapas.typography.callout.copy(color = colors.secondaryLabel), modifier = Modifier.testTag("transit_city_${row.id}_busy"))
            } else {
                if (row.installed) Action(stringResource(R.string.transit_maps_delete), "transit_city_${row.id}_delete") { onDelete(row.id) }
                if (!row.expired && (!row.installed || row.updateAvailable)) {
                    val label = if (row.updateAvailable) R.string.transit_maps_update else R.string.transit_maps_download
                    Action(stringResource(label), "transit_city_${row.id}_download") { onDownload(row.id) }
                }
            }
        }
    }
}

@Composable
private fun validityText(row: TransitCityRow, locale: java.util.Locale): String {
    val from = runCatching { LocalDate.parse(row.validFrom) }.getOrNull()
    val to = runCatching { LocalDate.parse(row.validTo) }.getOrNull()
    if (from == null || to == null) return ""
    return if (row.expired) stringResource(R.string.transit_maps_expired, TransitFormat.date(to, locale))
    else stringResource(R.string.transit_maps_valid, TransitFormat.date(from, locale), TransitFormat.date(to, locale))
}

@Composable
private fun failureText(f: TransitFailure): String = stringResource(
    when (f) {
        TransitFailure.OFFLINE_MODE -> R.string.transit_fail_offline
        TransitFailure.NOT_ALLOWED -> R.string.transit_fail_not_allowed
        TransitFailure.NETWORK -> R.string.transit_fail_network
        TransitFailure.INTEGRITY -> R.string.transit_fail_integrity
        TransitFailure.INVALID_DATA -> R.string.transit_fail_invalid
        TransitFailure.EXPIRED -> R.string.transit_fail_expired
        TransitFailure.CANCELLED -> R.string.transit_fail_cancelled
    },
)

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = Mapas.typography.callout.copy(color = Mapas.colors.accent),
        modifier = Modifier
            .heightIn(min = maxOf(48.dp, Mapas.dimens.touchTarget))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(tag),
    )
}

/** About dialog block: the attribution the data licences ask for (empty when no transit data is installed). */
@Composable
fun TransitAboutBlock(attributions: List<String>) {
    if (attributions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("about_transit")) {
        BasicText(stringResource(R.string.transit_about_title), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
        AttributionLines(attributions, "about_transit_line")
    }
}
