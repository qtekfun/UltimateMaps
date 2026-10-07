package com.qtekfun.mapas.settings

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.fuel.FuelDataManager
import com.qtekfun.mapas.core.fuel.FuelFailure
import com.qtekfun.mapas.core.fuel.FuelSettings
import com.qtekfun.mapas.core.fuel.FuelSettingsStore
import com.qtekfun.mapas.core.fuel.FuelTrigger
import com.qtekfun.mapas.core.fuel.FuelTypes
import com.qtekfun.mapas.core.fuel.FuelUpdateState
import com.qtekfun.mapas.core.fuel.REFRESH_CHOICES_MINUTES
import com.qtekfun.mapas.core.fuel.isValidSourceUrl
import com.qtekfun.mapas.core.fuel.sourceHost
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.NetworkPolicy
import com.qtekfun.mapas.ui.theme.Mapas
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
)

/** The first Settings screen: Privacy (offline mode, region catalog, possible connections) and Petrol stations. */
@Composable
fun SettingsScreen(env: SettingsEnv, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings by env.store.settings.collectAsState()
    val offline = env.offline()
    Column(
        modifier
            .fillMaxSize()
            .background(Mapas.colors.sheet)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("settings_screen"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.settings_back)
            BasicText(
                "‹ $back",
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier.clickable(role = Role.Button, onClick = onBack).padding(12.dp).testTag("settings_back"),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Mapas.dimens.screenMargin),
        ) {
            BasicText(stringResource(R.string.settings_title), style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label))
            PrivacySection(env, settings, offline)
            FuelSection(env, settings, offline)
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------- Privacy

@Composable
private fun PrivacySection(env: SettingsEnv, settings: FuelSettings, offline: Boolean) {
    SectionTitle(stringResource(R.string.settings_privacy_title))
    Card("privacy_offline_card") {
        SwitchRow(
            title = stringResource(R.string.settings_offline_title),
            body = stringResource(R.string.settings_offline_body),
            checked = offline,
            tag = "offline_switch",
            onChange = env.setOffline,
        )
    }
    Spacer(Modifier.height(10.dp))
    Card("privacy_regions_card") {
        BasicText(stringResource(R.string.settings_regions_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        val url = env.catalogUrl()
        BasicText(
            if (url.isEmpty()) stringResource(R.string.settings_regions_none) else stringResource(R.string.settings_regions_body, url),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
        TextButton(stringResource(R.string.settings_regions_open), "open_maps_from_settings", onClick = env.openMaps)
    }
    Spacer(Modifier.height(10.dp))
    Card("privacy_connections_card") {
        BasicText(stringResource(R.string.settings_connections_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        // Read on every composition (cheap): the policy is not observable and the fuel host is added asynchronously.
        val connections = env.policy.possibleConnections()
        if (connections.isEmpty()) {
            BasicText(stringResource(R.string.settings_connections_none), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        connections.forEach { c ->
            Spacer(Modifier.height(6.dp))
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
        ConnectionPurpose.OTHER -> R.string.conn_purpose_other
    },
)

// ---------------------------------------------------------------- Petrol stations

@Composable
private fun FuelSection(env: SettingsEnv, s: FuelSettings, offline: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.fuel_title))
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
    Spacer(Modifier.height(10.dp))
    UpdateCard(env, s)
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

// ---------------------------------------------------------------- Parts

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    BasicText(text, style = Mapas.typography.title.copy(color = Mapas.colors.label))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Card(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Mapas.shapes.control).background(Mapas.colors.field).padding(12.dp).testTag(tag)) { content() }
}

@Composable
private fun TextButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    BasicText(
        label,
        style = Mapas.typography.callout.copy(color = if (enabled) Mapas.colors.accent else Mapas.colors.secondaryLabel),
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag(tag),
    )
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mapas.typography.body.copy(color = Mapas.colors.label))
            BasicText(body, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        Box(
            Modifier
                .size(width = 51.dp, height = 31.dp)
                .clip(CircleShape)
                .background(if (checked) Mapas.colors.accent else Mapas.colors.separator)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(27.dp).clip(CircleShape).background(Mapas.colors.onAccent))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, radio: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val mark = when {
        radio -> if (selected) "◉" else "○"
        else -> if (selected) "☑" else "☐"
    }
    val modifier = if (radio) {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(true) })
    } else {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onChange)
    }
    Row(
        Modifier.fillMaxWidth().then(modifier).padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(mark, style = Mapas.typography.body.copy(color = if (selected) Mapas.colors.accent else Mapas.colors.secondaryLabel))
        BasicText(label, style = Mapas.typography.body.copy(color = Mapas.colors.label))
    }
}
