package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.transit.TransitAboutBlock
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * What the About category shows: the [version] name (empty hides it) and, lazily, the attribution lines of the
 * public-transport data that is installed ([transitAttributions] is read on every composition; empty without data).
 */
class AboutSettingsEnv(
    val version: String = "",
    val transitAttributions: () -> List<String> = { emptyList() },
)

/** Category "About": version, licence, source code and every attribution the app owes (map data, petrol prices, transit data). */
@Composable
fun AboutSettingsSection(env: AboutSettingsEnv) {
    if (env.version.isNotBlank()) {
        SectionTitle(stringResource(R.string.hub_about_version_title))
        BasicText(
            env.version,
            style = Mapas.typography.body.copy(color = Mapas.colors.label),
            modifier = Modifier.testTag("about_version"),
        )
    }
    SectionTitle(stringResource(R.string.hub_about_license_title))
    Card("about_license_card") {
        BasicText(stringResource(R.string.hub_about_license_body), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.hub_about_source),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("about_source"),
        )
    }
    SectionTitle(stringResource(R.string.about_title))
    Card("about_data_card") {
        BasicText(
            stringResource(R.string.about_osm_body),
            style = Mapas.typography.callout.copy(color = Mapas.colors.label),
            modifier = Modifier.testTag("about_osm"),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.fuel_attribution),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("about_fuel"),
        )
        TransitAboutBlock(env.transitAttributions())
    }
}
