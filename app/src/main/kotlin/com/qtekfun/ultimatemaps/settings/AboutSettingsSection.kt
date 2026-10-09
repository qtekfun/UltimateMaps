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
    /** The local diagnostic notes (how the processes ended, caught failures; no positions or names); read only when the user opens them. */
    val diagnostics: () -> String = { "" },
    val clearDiagnostics: () -> Unit = {},
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
    DiagnosticsBlock(env.diagnostics, env.clearDiagnostics)
    NoticeBlock(env.notice)
}

/**
 * "Diagnostics": what the app noted locally about failures (never a position, a name or an exception message), collapsed by
 * default, with Copy for a bug report and Clear. Nothing is sent anywhere.
 */
@Composable
private fun DiagnosticsBlock(diagnostics: () -> String, clear: () -> Unit) {
    SectionTitle(stringResource(R.string.about_diag_title))
    Card("about_diag_card") {
        var shown by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        BasicText(
            stringResource(R.string.about_diag_body),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
        TextButton(
            stringResource(if (shown) R.string.about_diag_hide else R.string.about_diag_show),
            "about_diag_toggle",
        ) {
            shown = !shown
            if (shown) text = diagnostics()
        }
        if (shown) {
            BasicText(
                text.ifBlank { stringResource(R.string.about_diag_empty) },
                style = Mapas.typography.callout.copy(color = Mapas.colors.label),
                modifier = Modifier.testTag("about_diag_text"),
            )
            if (text.isNotBlank()) {
                TextButton(stringResource(R.string.about_diag_copy), "about_diag_copy") {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                }
                DestructiveButton(stringResource(R.string.about_diag_clear), "about_diag_clear") {
                    clear()
                    text = ""
                }
            }
        }
    }
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
