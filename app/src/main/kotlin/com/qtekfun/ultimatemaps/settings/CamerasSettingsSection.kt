package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.cameras.CameraDataManager
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.CameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.CameraTrigger
import com.qtekfun.ultimatemaps.core.cameras.CameraUpdateState
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.cameras.INCIDENT_REFRESH_CHOICES_MINUTES
import com.qtekfun.ultimatemaps.core.cameras.IncidentDataManager
import com.qtekfun.ultimatemaps.core.cameras.IncidentTrigger
import com.qtekfun.ultimatemaps.core.cameras.IncidentUpdateState
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** What the "Speed cameras and traffic" section reads and changes. */
class CamerasSettingsEnv(
    val store: CameraSettingsStore,
    val cameras: CameraDataManager,
    val incidents: IncidentDataManager,
    val offline: () -> Boolean,
    /** Called after a switch changed, so the application can start the alerts the first time one is on. */
    val onChanged: () -> Unit = {},
    val locale: () -> Locale = Locale::getDefault,
)

/**
 * Section "Speed cameras and traffic": one switch per category (all off by default), an acknowledgement dialog before
 * the two camera categories, an explicit-consent dialog before live traffic data, the data dates and counts, "Update now"
 * and the attribution of each source.
 */
@Composable
fun CamerasSection(env: CamerasSettingsEnv) {
    val s by env.store.settings.collectAsState()
    val offline = env.offline()
    var confirmCamera by remember { mutableStateOf<CameraSwitch?>(null) }
    var confirmTraffic by remember { mutableStateOf<TrafficSwitch?>(null) }

    BasicText(stringResource(R.string.cam_intro), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    SectionTitle(stringResource(R.string.hub_group_cameras))
    Card("cam_switches_card") {
        SwitchRow(
            stringResource(R.string.cam_fixed_title), stringResource(R.string.cam_fixed_body), s.fixedEnabled, "cam_fixed_switch",
        ) { on -> if (on && !s.acknowledged) confirmCamera = CameraSwitch.FIXED else setCameras(env, fixed = on) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            stringResource(R.string.cam_mobile_title), stringResource(R.string.cam_mobile_body), s.mobileZonesEnabled, "cam_mobile_switch",
        ) { on -> if (on && !s.acknowledged) confirmCamera = CameraSwitch.MOBILE else setCameras(env, mobile = on) }
        if (s.anyCamera) {
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                stringResource(R.string.cam_only_speeding_title), stringResource(R.string.cam_only_speeding_body), s.warnOnlyIfSpeeding, "cam_only_speeding_switch",
            ) { on -> env.store.update { it.copy(warnOnlyIfSpeeding = on) } }
        }
    }
    if (s.anyCamera) {
        Spacer(Modifier.height(10.dp))
        AlertModeCard(stringResource(R.string.alert_mode_cam_title), s.cameraAlertMode, "cam_alert_mode") { m -> env.store.update { it.copy(cameraAlertMode = m) } }
    }
    SectionTitle(stringResource(R.string.hub_group_incidents))
    Card("cam_traffic_card") {
        SwitchRow(
            stringResource(R.string.cam_incidents_title), stringResource(R.string.cam_incidents_body), s.incidentsEnabled, "cam_incidents_switch",
        ) { on -> if (on && !s.anyIncident) confirmTraffic = TrafficSwitch.INCIDENTS else setTraffic(env, incidents = on) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            stringResource(R.string.cam_v16_title), stringResource(R.string.cam_v16_body), s.v16Enabled, "cam_v16_switch",
        ) { on -> if (on && !s.anyIncident) confirmTraffic = TrafficSwitch.V16 else setTraffic(env, v16 = on) }
        if (s.incidentsEnabled) {
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                stringResource(R.string.cam_roadworks_title), stringResource(R.string.cam_roadworks_body), s.roadworksEnabled, "cam_roadworks_switch",
            ) { on -> env.store.update { it.copy(roadworksEnabled = on) } }
        }
    }

    if (s.anyIncident) {
        Spacer(Modifier.height(10.dp))
        AlertModeCard(stringResource(R.string.alert_mode_incident_title), s.incidentAlertMode, "incident_alert_mode") { m -> env.store.update { it.copy(incidentAlertMode = m) } }
    }

    confirmCamera?.let { which ->
        ConfirmDialog(
            title = stringResource(R.string.cam_confirm_title), body = stringResource(R.string.cam_confirm_body), offlineNote = null,
            yes = stringResource(R.string.cam_confirm_yes), no = stringResource(R.string.cam_confirm_no), tag = "cam_confirm",
            onConfirm = {
                confirmCamera = null
                env.store.update { it.copy(acknowledged = true) }
                setCameras(env, fixed = if (which == CameraSwitch.FIXED) true else null, mobile = if (which == CameraSwitch.MOBILE) true else null)
            },
            onDismiss = { confirmCamera = null },
        )
    }
    confirmTraffic?.let { which ->
        ConfirmDialog(
            title = stringResource(R.string.cam_incidents_confirm_title), body = stringResource(R.string.cam_incidents_confirm_body),
            offlineNote = if (offline) stringResource(R.string.cam_incidents_confirm_offline) else null,
            yes = stringResource(R.string.cam_confirm_yes), no = stringResource(R.string.cam_confirm_no), tag = "cam_traffic_confirm",
            onConfirm = {
                confirmTraffic = null
                setTraffic(env, incidents = if (which == TrafficSwitch.INCIDENTS) true else null, v16 = if (which == TrafficSwitch.V16) true else null)
            },
            onDismiss = { confirmTraffic = null },
        )
    }

    if (s.anything) {
        Spacer(Modifier.height(10.dp))
        DataCard(env, s)
    }
    if (s.anyIncident) {
        Spacer(Modifier.height(10.dp))
        AdvancedGroup("cam_advanced") {
            Card("cam_refresh_card") {
                BasicText(stringResource(R.string.cam_refresh_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                INCIDENT_REFRESH_CHOICES_MINUTES.forEach { m ->
                    ChoiceRow(
                        if (m >= 60) stringResource(R.string.cam_refresh_hour) else stringResource(R.string.cam_refresh_minutes, m),
                        s.incidentRefreshMinutes == m, radio = true, tag = "cam_refresh_$m",
                    ) { env.store.update { cur -> cur.copy(incidentRefreshMinutes = m) } }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    BasicText(
        stringResource(R.string.cam_legal_note),
        style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        modifier = Modifier.testTag("cam_legal_note"),
    )
}

/** A 3-way choice (sound, voice, silent) for one alert category, shown as radio rows like the voice-language choice. */
@Composable
private fun AlertModeCard(title: String, current: AlertSoundMode, tagPrefix: String, onPick: (AlertSoundMode) -> Unit) {
    Card("${tagPrefix}_card") {
        BasicText(title, style = Mapas.typography.body.copy(color = Mapas.colors.label))
        AlertSoundMode.entries.forEach { m ->
            val label = when (m) {
                AlertSoundMode.SOUND -> R.string.alert_mode_sound
                AlertSoundMode.VOICE -> R.string.alert_mode_voice
                AlertSoundMode.SILENT -> R.string.alert_mode_silent
            }
            ChoiceRow(stringResource(label), current == m, radio = true, tag = "${tagPrefix}_${m.name.lowercase()}") { onPick(m) }
        }
        Spacer(Modifier.height(4.dp))
        BasicText(stringResource(R.string.alert_mode_note), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    }
}

private enum class CameraSwitch { FIXED, MOBILE }
private enum class TrafficSwitch { INCIDENTS, V16 }

private fun setCameras(env: CamerasSettingsEnv, fixed: Boolean? = null, mobile: Boolean? = null) {
    val before = env.store.settings.value
    env.store.update { it.copy(fixedEnabled = fixed ?: it.fixedEnabled, mobileZonesEnabled = mobile ?: it.mobileZonesEnabled) }
    env.onChanged()
    // A file is fetched only when a camera switch was just turned ON and there is no data yet.
    if (!before.anyCamera && env.store.settings.value.anyCamera) env.cameras.refreshAsync(CameraTrigger.ENABLED)
}

private fun setTraffic(env: CamerasSettingsEnv, incidents: Boolean? = null, v16: Boolean? = null) {
    val before = env.store.settings.value
    env.store.update { it.copy(incidentsEnabled = incidents ?: it.incidentsEnabled, v16Enabled = v16 ?: it.v16Enabled) }
    env.onChanged()
    if (!before.anyIncident && env.store.settings.value.anyIncident) env.incidents.refreshAsync(IncidentTrigger.ENABLED)
}

@Composable
private fun DataCard(env: CamerasSettingsEnv, s: CameraSettings) {
    val locale = env.locale()
    val english = locale.language != "es"
    val camGenerated by env.cameras.repository.generatedMillis.collectAsState()
    val incUpdated by env.incidents.repository.lastUpdateMillis.collectAsState()
    val camState by env.cameras.updateState.collectAsState()
    val incState by env.incidents.updateState.collectAsState()
    Card("cam_data_card") {
        BasicText(stringResource(R.string.cam_data_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        if (s.anyCamera) {
            val d = env.cameras.repository.data
            BasicText(
                camGenerated?.let {
                    stringResource(
                        R.string.cam_data_cameras, d.fixed.size, d.sections.size, d.zones.size, d.zones.count { z -> z.hasGeometry },
                        CameraAttribution.dateText(it, english),
                    )
                } ?: stringResource(R.string.cam_data_cameras_never),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("cam_data_cameras"),
            )
            camGenerated?.let {
                BasicText(
                    CameraAttribution.forCameras(d.sourceFlags, english),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("cam_attribution_cameras"),
                )
            }
        }
        if (s.anyIncident) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                incUpdated?.let { stringResource(R.string.cam_data_incidents, env.incidents.repository.data?.incidents?.size ?: 0, CameraAttribution.dateText(it, english)) }
                    ?: stringResource(R.string.cam_data_incidents_never),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("cam_data_incidents"),
            )
            BasicText(
                CameraAttribution.forIncidents(english),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("cam_attribution_incidents"),
            )
        }
        val running = camState is CameraUpdateState.Running || incState is IncidentUpdateState.Running
        TextButton(stringResource(R.string.cam_update_now), "cam_update_now", enabled = !running) {
            if (s.anyCamera) env.cameras.refreshAsync(CameraTrigger.USER)
            if (s.anyIncident) env.incidents.refreshAsync(IncidentTrigger.USER)
        }
        if (running) BasicText(stringResource(R.string.cam_update_running), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        val failure = ((camState as? CameraUpdateState.Finished)?.failure.takeIf { s.anyCamera }) ?: ((incState as? IncidentUpdateState.Finished)?.failure.takeIf { s.anyIncident })
        val finished = (camState as? CameraUpdateState.Finished) != null || (incState as? IncidentUpdateState.Finished) != null
        if (!running && failure != null) {
            BasicText(
                stringResource(R.string.cam_update_error, stringResource(failureText(failure))),
                style = Mapas.typography.callout.copy(color = Mapas.colors.warning), modifier = Modifier.testTag("cam_error"),
            )
        } else if (!running && finished) {
            BasicText(stringResource(R.string.cam_update_ok), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("cam_result_ok"))
        }
    }
}

private fun failureText(f: DownloadFailure): Int = when (f) {
    DownloadFailure.OFFLINE_MODE -> R.string.cam_err_offline
    DownloadFailure.NOT_ALLOWED -> R.string.cam_err_not_allowed
    DownloadFailure.NETWORK -> R.string.cam_err_network
    DownloadFailure.TIMEOUT -> R.string.cam_err_timeout
    DownloadFailure.SERVER -> R.string.cam_err_server
    DownloadFailure.TOO_LARGE -> R.string.cam_err_too_large
    DownloadFailure.INVALID_DATA -> R.string.cam_err_invalid
    DownloadFailure.NO_CATALOG -> R.string.cam_err_no_catalog
}

@Composable
private fun ConfirmDialog(
    title: String, body: String, offlineNote: String?, yes: String, no: String, tag: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(Mapas.shapes.control).background(Mapas.colors.sheet).padding(20.dp).testTag("${tag}_dialog")) {
            BasicText(title, style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                BasicText(body, style = Mapas.typography.body.copy(color = Mapas.colors.label))
                if (offlineNote != null) {
                    Spacer(Modifier.height(8.dp))
                    BasicText(offlineNote, style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(no, "${tag}_no", onClick = onDismiss)
                TextButton(yes, "${tag}_yes", onClick = onConfirm)
            }
        }
    }
}
