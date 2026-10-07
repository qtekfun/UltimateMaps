package com.qtekfun.mapas.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.data.record.RecordingStatus
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.places.PanelNote
import com.qtekfun.mapas.places.formatDistance
import com.qtekfun.mapas.ui.theme.Mapas

/** The controller plus what only the screen can do: ask for the location permission and start listening. */
class RecordingPanel(val controller: RecordingController, val requestLocation: () -> Unit)

/**
 * Start / Stop recording in the Tracks area. Shown only while the Settings switch is on. While recording it says how
 * many points and how far, and whether the recording is paused because the user stopped moving.
 */
@Composable
fun RecordingControls(panel: RecordingPanel, modifier: Modifier = Modifier) {
    val controller = panel.controller
    val enabled by controller.enabled.collectAsState()
    if (!enabled) return
    val state by controller.state.collectAsState()
    val notice by controller.notice.collectAsState()
    Column(modifier.fillMaxWidth().testTag("recording_controls")) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.active) {
                val distance = formatDistance(state.distanceMeters)
                val text = if (state.status == RecordingStatus.PAUSED) {
                    stringResource(R.string.recording_status_paused, state.points, distance)
                } else {
                    stringResource(R.string.recording_status_on, state.points, distance)
                }
                BasicText(
                    text, style = Mapas.typography.body.copy(color = Mapas.colors.label),
                    modifier = Modifier.weight(1f).testTag("recording_status"),
                )
                PanelButton(stringResource(R.string.recording_stop), controller::stop, primary = true, tag = "record_stop")
            } else {
                BasicText(
                    stringResource(R.string.recording_idle), style = Mapas.typography.body.copy(color = Mapas.colors.label),
                    modifier = Modifier.weight(1f).testTag("recording_status"),
                )
                PanelButton(
                    stringResource(R.string.recording_start),
                    { controller.start(); panel.requestLocation() }, primary = true, tag = "record_start",
                )
            }
        }
        notice?.let {
            PanelNote(
                stringResource(
                    when (it) {
                        RecordingNotice.SAVED -> R.string.recording_saved
                        RecordingNotice.TOO_SHORT -> R.string.recording_too_short
                        RecordingNotice.FAILED -> R.string.recording_failed
                        RecordingNotice.COULD_NOT_START -> R.string.recording_could_not_start
                    },
                ),
                "recording_notice",
            )
        }
        BasicText(
            stringResource(R.string.recording_note),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("recording_note"),
        )
    }
}
