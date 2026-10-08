package com.qtekfun.ultimatemaps.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.fuel.FuelDataManager
import com.qtekfun.ultimatemaps.core.fuel.FuelFailure
import com.qtekfun.ultimatemaps.core.fuel.FuelSettings
import com.qtekfun.ultimatemaps.core.fuel.FuelSettingsStore
import com.qtekfun.ultimatemaps.core.fuel.FuelTrigger
import com.qtekfun.ultimatemaps.core.fuel.FuelTypes
import com.qtekfun.ultimatemaps.core.fuel.FuelUpdateState
import com.qtekfun.ultimatemaps.core.fuel.REFRESH_CHOICES_MINUTES
import com.qtekfun.ultimatemaps.core.fuel.isValidSourceUrl
import com.qtekfun.ultimatemaps.core.fuel.sourceHost
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.text.DateFormat
import java.util.Date

/** Everything the Settings screen reads and changes. The activity builds it from the application. */
class SettingsEnv(
    val store: FuelSettingsStore,
    val fuel: FuelDataManager,
    val policy: NetworkPolicy,
    /** Offline mode is the same persisted setting the Maps screen uses. */
    val offline: () -> Boolean,
    val setOffline: (Boolean) -> Unit,
    val catalogUrl: () -> String,
    val openMaps: () -> Unit,
    /** Navigation category (voice and route defaults); null hides it. */
    val navigation: NavigationSettingsEnv? = null,
    /** Search history part of the Data category (on/off and clear); null hides it. */
    val history: HistorySettingsEnv? = null,
    /** Alerts category (speed cameras and traffic); null hides it. */
    val cameras: CamerasSettingsEnv? = null,
    /** "Electric chargers" group of the Fuel stations category (switch, plug and power filters); null hides it. */
    val chargers: ChargersSettingsEnv? = null,
    /** Track recording part of the Data category (switch and delete); null hides it. */
    val recording: RecordingSettingsEnv? = null,
    /** Language of place information section (search results and place card); null hides it. */
    val placeLanguage: PlaceLanguageSettingsEnv? = null,
    /** Backup and restore part of the Data category (export, import, export everything); null hides it. */
    val backup: BackupSettingsEnv? = null,
    /** About category: the version name and the attribution lines of the installed public-transport data. */
    val about: AboutSettingsEnv = AboutSettingsEnv(),
)

/**
 * The Settings screen: a hub of categories (see [settingsCategories]) and, once one is opened, that category's screen
 * with a back arrow. The open category is kept across rotation and the system Back returns to the hub first.
 * [initialCategory] opens a category directly (tests, previews).
 */
@Composable
fun SettingsScreen(env: SettingsEnv, onBack: () -> Unit, modifier: Modifier = Modifier, initialCategory: String? = null) {
    val categories = remember(env) { settingsCategories(env) }
    var openId by rememberSaveable { mutableStateOf(initialCategory) }
    var query by rememberSaveable { mutableStateOf("") }
    val open = categories.firstOrNull { it.id == openId }
    BackHandler(enabled = open != null) { openId = null }
    Column(
        modifier
            .fillMaxSize()
            .background(Mapas.colors.sheet)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("settings_screen"),
    ) {
        SettingsTopBar(
            title = stringResource(open?.title ?: R.string.settings_title),
            onBack = { if (open != null) openId = null else onBack() },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Mapas.dimens.screenMargin),
        ) {
            // A restore bumps the revision: the screens are rebuilt so they show the restored values.
            key(env.backup?.state?.revision ?: 0) {
                if (open == null) {
                    SettingsHub(categories, query, onQuery = { query = it }, onOpen = { openId = it.id })
                } else {
                    key(open.id) { open.content() }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------- Maps and network

/** Category "Maps and network": offline mode, the region catalog and the list of possible connections. */
@Composable
internal fun NetworkSettingsContent(env: SettingsEnv) {
    val offline = env.offline()
    SectionTitle(stringResource(R.string.settings_privacy_title))
    BasicText(
        stringResource(R.string.hub_privacy_note),
        style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        modifier = Modifier.testTag("privacy_note"),
    )
    Spacer(Modifier.height(8.dp))
    Card("privacy_offline_card") {
        SwitchRow(
            title = stringResource(R.string.settings_offline_title),
            body = stringResource(R.string.settings_offline_body),
            checked = offline,
            tag = "offline_switch",
            onChange = env.setOffline,
        )
    }
    SectionTitle(stringResource(R.string.hub_group_maps))
    Card("privacy_regions_card") {
        BasicText(stringResource(R.string.settings_regions_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        val url = env.catalogUrl()
        BasicText(
            if (url.isEmpty()) stringResource(R.string.settings_regions_none) else stringResource(R.string.settings_regions_body, url),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
        TextButton(stringResource(R.string.settings_regions_open), "open_maps_from_settings", onClick = env.openMaps)
    }
    SectionTitle(stringResource(R.string.settings_connections_title))
    Card("privacy_connections_card") {
        // Read on every composition (cheap): the policy is not observable and the fuel host is added asynchronously.
        val connections = env.policy.possibleConnections()
        if (connections.isEmpty()) {
            BasicText(stringResource(R.string.settings_connections_none), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        connections.forEachIndexed { i, c ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            BasicText(c.host, style = Mapas.typography.callout.copy(color = Mapas.colors.label), modifier = Modifier.testTag("conn_host"))
            val status = when {
                offline -> stringResource(R.string.conn_status_blocked_offline)
                c.enabled -> stringResource(R.string.conn_status_active)
                else -> stringResource(R.string.conn_status_disabled)
            }
            BasicText(
                "${purposeLabel(c.purpose)} · $status",
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("conn_status"),
            )
        }
    }
}

@Composable
private fun purposeLabel(p: ConnectionPurpose) = stringResource(
    when (p) {
        ConnectionPurpose.ONLINE_TILES -> R.string.conn_purpose_tiles
        ConnectionPurpose.SHORT_LINK_RESOLVE -> R.string.conn_purpose_short_link
        ConnectionPurpose.MAP_DOWNLOAD -> R.string.conn_purpose_map_download
        ConnectionPurpose.SYNC_WEBDAV -> R.string.conn_purpose_sync
        ConnectionPurpose.TRAFFIC_INCIDENTS -> R.string.conn_purpose_traffic
        ConnectionPurpose.TRANSIT_REALTIME -> R.string.conn_purpose_transit_rt
        ConnectionPurpose.OTHER -> R.string.conn_purpose_other
    },
)

// ---------------------------------------------------------------- Petrol stations

@Composable
internal fun FuelSettingsContent(env: SettingsEnv) {
    val s by env.store.settings.collectAsState()
    val offline = env.offline()
    var confirming by remember { mutableStateOf(false) }
    Card("fuel_enable_card") {
        SwitchRow(
            title = stringResource(R.string.fuel_switch_title),
            body = stringResource(R.string.fuel_switch_body),
            checked = s.enabled,
            tag = "fuel_switch",
            onChange = { on ->
                if (on) confirming = true // nothing changes (and nothing connects) until the user confirms
                else env.store.update { it.copy(enabled = false) }
            },
        )
    }
    if (confirming) {
        ConfirmDialog(
            server = sourceHost(s.sourceUrl) ?: s.sourceUrl,
            offline = offline,
            onConfirm = {
                confirming = false
                env.store.update { it.copy(enabled = true) }
                env.fuel.refreshAsync(FuelTrigger.ENABLED)
            },
            onDismiss = { confirming = false },
        )
    }
    if (!s.enabled) return

    Spacer(Modifier.height(10.dp))
    Card("fuel_fuels_card") {
        BasicText(stringResource(R.string.fuel_fuels_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(
            stringResource(if (s.downloadedFuels.isEmpty()) R.string.fuel_fuels_none else R.string.fuel_fuels_body),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
        FuelTypes.all.forEach { f ->
            val on = f.id in s.downloadedFuels
            ChoiceRow(f.displayName, on, radio = false, tag = "fuel_check_${f.id}") { checked ->
                env.store.update { cur ->
                    cur.copy(downloadedFuels = if (checked) cur.downloadedFuels + f.id else cur.downloadedFuels - f.id)
                }
                // A fuel that was just added is fetched now; nothing else is asked again before its time.
                if (checked) env.fuel.refreshAsync(FuelTrigger.FUELS_CHANGED)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Card("fuel_map_card") {
        BasicText(stringResource(R.string.fuel_map_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        if (s.downloadedFuels.isEmpty()) {
            BasicText(stringResource(R.string.fuel_map_none), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        FuelTypes.all.filter { it.id in s.downloadedFuels }.forEach { f ->
            ChoiceRow(f.displayName, s.mapFuel == f.id, radio = true, tag = "fuel_map_${f.id}") {
                env.store.update { cur -> cur.copy(mapFuel = f.id) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    UpdateCard(env, s)
    Spacer(Modifier.height(10.dp))
    AdvancedGroup("fuel_advanced") {
        Card("fuel_refresh_card") {
            BasicText(stringResource(R.string.fuel_refresh_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            REFRESH_CHOICES_MINUTES.forEach { m ->
                ChoiceRow(refreshLabel(m), s.refreshMinutes == m, radio = true, tag = "fuel_refresh_$m") {
                    env.store.update { cur -> cur.copy(refreshMinutes = m) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        UrlCard(env, s)
    }
}

@Composable
private fun refreshLabel(minutes: Int): String = when {
    minutes >= 1440 -> stringResource(R.string.fuel_refresh_days)
    minutes >= 60 -> stringResource(R.string.fuel_refresh_hours, minutes / 60)
    else -> stringResource(R.string.fuel_refresh_minutes, minutes)
}

@Composable
private fun UrlCard(env: SettingsEnv, s: FuelSettings) {
    var text by remember(s.sourceUrl) { mutableStateOf(s.sourceUrl) }
    var message by remember { mutableStateOf<Int?>(null) }
    Card("fuel_url_card") {
        BasicText(stringResource(R.string.fuel_url_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it; message = null },
            singleLine = true,
            textStyle = Mapas.typography.callout.copy(color = Mapas.colors.label),
            cursorBrush = SolidColor(Mapas.colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(Mapas.shapes.field)
                .background(Mapas.colors.field)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("fuel_url_field"),
        )
        message?.let {
            BasicText(
                stringResource(it),
                style = Mapas.typography.callout.copy(color = if (it == R.string.fuel_url_invalid) Mapas.colors.warning else Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("fuel_url_message"),
            )
        }
        Row {
            TextButton(stringResource(R.string.fuel_url_save), "fuel_url_save") {
                if (isValidSourceUrl(text)) {
                    env.store.update { it.copy(sourceUrl = text.trim()) }
                    message = R.string.fuel_url_saved
                } else message = R.string.fuel_url_invalid
            }
            TextButton(stringResource(R.string.fuel_url_reset), "fuel_url_reset") {
                env.store.update { it.copy(sourceUrl = FuelSettings.DEFAULT_SOURCE_URL) }
                text = FuelSettings.DEFAULT_SOURCE_URL
                message = null
            }
        }
    }
}

@Composable
private fun UpdateCard(env: SettingsEnv, s: FuelSettings) {
    val last by env.fuel.repository.lastUpdateMillis.collectAsState()
    val update by env.fuel.updateState.collectAsState()
    Card("fuel_update_card") {
        BasicText(
            last?.let { stringResource(R.string.fuel_last_update, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
                ?: stringResource(R.string.fuel_last_update_never),
            style = Mapas.typography.body.copy(color = Mapas.colors.label),
            modifier = Modifier.testTag("fuel_last_update"),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.fuel_attribution),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("fuel_attribution"),
        )
        val running = update is FuelUpdateState.Running
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                stringResource(R.string.fuel_update_now), "fuel_update_now",
                enabled = !running && s.downloadedFuels.isNotEmpty(),
            ) { env.fuel.refreshAsync(FuelTrigger.USER) }
        }
        when (val u = update) {
            is FuelUpdateState.Running -> {
                val name = u.currentFuelId?.let { FuelTypes.byId(it)?.displayName ?: it }.orEmpty()
                BasicText(
                    stringResource(R.string.fuel_update_running, u.done + 1, u.total, name),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    modifier = Modifier.testTag("fuel_progress"),
                )
            }
            is FuelUpdateState.Finished -> {
                val errors = u.outcomes.filter { it.failure != null }
                if (errors.isEmpty()) {
                    if (u.outcomes.isNotEmpty()) {
                        BasicText(stringResource(R.string.fuel_update_ok), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("fuel_result_ok"))
                    }
                } else {
                    errors.forEach { o ->
                        val name = FuelTypes.byId(o.fuelId)?.displayName ?: o.fuelId
                        BasicText(
                            stringResource(R.string.fuel_update_error, name, stringResource(failureText(o.failure!!))),
                            style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                            modifier = Modifier.testTag("fuel_error"),
                        )
                    }
                    BasicText(stringResource(R.string.fuel_update_kept), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
                }
            }
            FuelUpdateState.Idle -> Unit
        }
    }
}

private fun failureText(f: FuelFailure): Int = when (f) {
    FuelFailure.OFFLINE_MODE -> R.string.fuel_err_offline
    FuelFailure.NOT_ALLOWED -> R.string.fuel_err_not_allowed
    FuelFailure.NETWORK -> R.string.fuel_err_network
    FuelFailure.TIMEOUT -> R.string.fuel_err_timeout
    FuelFailure.SERVER -> R.string.fuel_err_server
    FuelFailure.TOO_LARGE -> R.string.fuel_err_too_large
    FuelFailure.INVALID_DATA -> R.string.fuel_err_invalid
}

@Composable
private fun ConfirmDialog(server: String, offline: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(Mapas.shapes.control).background(Mapas.colors.sheet).padding(20.dp).testTag("fuel_confirm_dialog")) {
            BasicText(stringResource(R.string.fuel_confirm_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                BasicText(stringResource(R.string.fuel_confirm_body, server), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                if (offline) {
                    Spacer(Modifier.height(8.dp))
                    BasicText(stringResource(R.string.fuel_confirm_offline), style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(stringResource(R.string.fuel_confirm_no), "fuel_confirm_no", onClick = onDismiss)
                TextButton(stringResource(R.string.fuel_confirm_yes), "fuel_confirm_yes", onClick = onConfirm)
            }
        }
    }
}

