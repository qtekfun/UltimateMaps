package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    /** The text of the NOTICE file (third-party notices and licence texts); read only when the user opens it. */
    val notice: () -> String = { "" },
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
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.ev_attribution),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("about_ev"),
        )
        TransitAboutBlock(env.transitAttributions())
    }
    NoticeBlock(env.notice)
}

/** "Third-party notices": the NOTICE text (licence texts that the libraries inside the app ask to ship), collapsed by default. */
@Composable
private fun NoticeBlock(notice: () -> String) {
    SectionTitle(stringResource(R.string.about_notice_title))
    Card("about_notice_card") {
        var shown by remember { mutableStateOf(false) }
        TextButton(
            stringResource(if (shown) R.string.about_notice_hide else R.string.about_notice_show),
            "about_notice_toggle",
        ) { shown = !shown }
        if (shown) {
            BasicText(
                notice().ifBlank { stringResource(R.string.about_notice_unavailable) },
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("about_notice_text"),
            )
        }
    }
}
