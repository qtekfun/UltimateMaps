package com.qtekfun.ultimatemaps.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.fuel.FuelTypes
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/**
 * One row of the Settings hub and the screen it opens. To add a category: build one of these in [settingsCategories].
 * To add a setting to an existing category, add it to that category's content (see docs/phase2/settings-hub.md).
 *
 * @property id stable id: saved across rotation and used in the row's test tag (`settings_row_<id>`).
 * @property title the row title and the heading of the category screen.
 * @property keywords space-separated words (localized) that the hub search also matches.
 * @property icon drawn with Canvas on a square area.
 * @property summary one line about the current state, read in the composition so it follows the settings.
 * @property content the category screen below the title bar.
 */
class SettingsCategory(
    val id: String,
    @StringRes val title: Int,
    @StringRes val keywords: Int,
    val icon: DrawScope.(Color) -> Unit,
    val summary: @Composable () -> String,
    val content: @Composable () -> Unit,
)

/** The categories of [env], in hub order. A category whose data source is missing (null in [env]) is left out. */
fun settingsCategories(env: SettingsEnv): List<SettingsCategory> = buildList {
    env.navigation?.let { nav ->
        add(
            SettingsCategory(
                "navigation", R.string.hub_navigation_title, R.string.hub_navigation_keywords, { drawNavigationIcon(it) },
                summary = { navigationSummary(nav) },
                content = {
                    NavigationSection(nav)
                    env.trails?.let { TrailsSection(it) }
                    env.bikeShare?.let { BikeShareSection(it) }
                },
            ),
        )
    }
    env.cameras?.let { cam ->
        add(
            SettingsCategory(
                "alerts", R.string.hub_alerts_title, R.string.hub_alerts_keywords, { drawAlertsIcon(it) },
                summary = { alertsSummary(cam) },
                content = {
                    CamerasSection(cam)
                    env.zbe?.let { ZbeSection(it) }
                },
            ),
        )
    }
    add(
        SettingsCategory(
            "fuel", R.string.fuel_title, R.string.hub_fuel_keywords, { drawFuelIcon(it) },
            summary = { fuelSummary(env) },
            content = {
                FuelSettingsContent(env)
                env.chargers?.let { ChargersSection(it) }
            },
        ),
    )
    add(
        SettingsCategory(
            "network", R.string.hub_network_title, R.string.hub_network_keywords, { drawNetworkIcon(it) },
            summary = { stringResource(R.string.hub_network_summary, onOff(env.offline())) },
            content = {
                NetworkSettingsContent(env)
                env.placeLanguage?.let { PlaceLanguageSection(it) }
            },
        ),
    )
    if (env.history != null || env.recording != null || env.backup != null) {
        add(
            SettingsCategory(
                "data", R.string.hub_data_title, R.string.hub_data_keywords, { drawDataIcon(it) },
                summary = { dataSummary(env) },
                content = { DataSettingsContent(env) },
            ),
        )
    }
    add(
        SettingsCategory(
            "about", R.string.hub_about_title, R.string.hub_about_keywords, { drawAboutIcon(it) },
            summary = { aboutSummary(env.about) },
            content = { AboutSettingsSection(env.about) },
        ),
    )
}

// ---------------------------------------------------------------- Summaries

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.hub_on else R.string.hub_off)

@Composable
private fun navigationSummary(env: NavigationSettingsEnv): String {
    val s by env.store.settings.collectAsState()
    if (!s.voiceEnabled) return stringResource(R.string.hub_navigation_voice_off)
    val language = stringResource(
        when (s.voiceLanguage) {
            VoiceLanguagePref.AUTO -> R.string.nav_language_auto
            VoiceLanguagePref.ES -> R.string.nav_language_es
            VoiceLanguagePref.EN -> R.string.nav_language_en
        },
    )
    return stringResource(R.string.hub_navigation_voice_on, language, s.volumePercent)
}

@Composable
private fun alertsSummary(env: CamerasSettingsEnv): String {
    val s by env.store.settings.collectAsState()
    return stringResource(R.string.hub_alerts_summary, onOff(s.anyCamera), onOff(s.anyIncident))
}

@Composable
private fun fuelSummary(env: SettingsEnv): String {
    val s by env.store.settings.collectAsState()
    if (!s.enabled) return stringResource(R.string.hub_fuel_off)
    if (s.downloadedFuels.isEmpty()) return stringResource(R.string.hub_fuel_no_fuel)
    val n = s.downloadedFuels.size
    val count = LocalContext.current.resources.getQuantityString(R.plurals.hub_fuel_count, n, n)
    val mapFuel = s.mapFuel?.let { FuelTypes.byId(it)?.displayName } ?: return count
    return stringResource(R.string.hub_fuel_summary, count, mapFuel)
}

@Composable
private fun dataSummary(env: SettingsEnv): String {
    val parts = buildList {
        env.history?.let { add(stringResource(R.string.hub_data_searches, onOff(it.settings.enabled))) }
        env.recording?.let {
            val on by it.controller.enabled.collectAsState()
            add(stringResource(R.string.hub_data_recording, onOff(on)))
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.backup_title) else parts.joinToString(" · ")
}

@Composable
private fun aboutSummary(about: AboutSettingsEnv): String =
    if (about.version.isBlank()) stringResource(R.string.hub_about_summary_plain) else stringResource(R.string.hub_about_summary, about.version)

// ---------------------------------------------------------------- Hub

/**
 * The first screen: a search field (filters the rows by title, summary and keywords) and one row per category with its
 * icon, title, one-line summary and a chevron.
 */
@Composable
internal fun SettingsHub(categories: List<SettingsCategory>, query: String, onQuery: (String) -> Unit, onOpen: (SettingsCategory) -> Unit) {
    SearchField(query, onQuery)
    Spacer(Modifier.height(12.dp))
    val rows = categories.map { c ->
        val title = stringResource(c.title)
        val summary = c.summary()
        val keywords = stringResource(c.keywords)
        HubRow(c, title, summary, matches = matches(query, title, summary, keywords))
    }.filter { it.matches }
    if (rows.isEmpty()) {
        BasicText(
            stringResource(R.string.hub_search_none),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(vertical = 8.dp).testTag("settings_no_match"),
        )
    } else {
        Column(
            Modifier.fillMaxWidth().clip(Mapas.shapes.control).background(Mapas.colors.field).testTag("settings_hub"),
        ) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Mapas.colors.separator))
                CategoryRow(r, onClick = { onOpen(r.category) })
            }
        }
    }
}

private class HubRow(val category: SettingsCategory, val title: String, val summary: String, val matches: Boolean)

/** True when every word of [query] is in the title, the summary or the keywords (case-insensitive). An empty query matches. */
internal fun matches(query: String, vararg fields: String): Boolean {
    val words = query.trim().lowercase(Locale.getDefault()).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = fields.joinToString(" ").lowercase(Locale.getDefault())
    return words.all { it in haystack }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
        cursorBrush = SolidColor(Mapas.colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minTarget)
            .clip(Mapas.shapes.field)
            .background(Mapas.colors.field)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("settings_search"),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    BasicText(stringResource(R.string.hub_search_hint), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
                }
                inner()
            }
        },
    )
}

@Composable
private fun CategoryRow(row: HubRow, onClick: () -> Unit) {
    val accent = Mapas.colors.accent
    val chevron = Mapas.colors.secondaryLabel
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(64.dp, minTarget))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("settings_row_${row.category.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(Mapas.shapes.control).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) { row.category.icon(this, accent) }
        }
        Column(Modifier.weight(1f)) {
            BasicText(row.title, style = Mapas.typography.body.copy(color = Mapas.colors.label))
            BasicText(
                row.summary,
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("settings_summary_${row.category.id}"),
            )
        }
        Canvas(Modifier.size(20.dp)) { drawChevron(chevron) }
    }
}

// ---------------------------------------------------------------- Data

/** Category "Data": recent searches, track recording and backup and restore, each with its own group heading. */
@Composable
internal fun DataSettingsContent(env: SettingsEnv) {
    env.history?.let { HistorySection(it) }
    env.recording?.let { RecordingSection(it) }
    env.backup?.let { BackupSection(it) }
}
