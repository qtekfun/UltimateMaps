package com.qtekfun.mapas.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.recording.RecordingController
import com.qtekfun.mapas.ui.theme.Mapas

/** What the "Track recording" section changes: the switch and the one-tap deletion live in the controller. */
class RecordingSettingsEnv(val controller: RecordingController)

/**
 * Section "Track recording" of the Settings screen: a switch (off by default) that makes the Start recording
 * button available in the Tracks area, and a button that deletes every recorded track in one tap. Turning the
 * switch off while recording saves what was recorded. Recordings stay on this device.
 */
@Composable
fun RecordingSection(env: RecordingSettingsEnv) {
    val enabled by env.controller.enabled.collectAsState()
    var deleted by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.recording_title))
    Card("recording_card") {
        SwitchRow(
            title = stringResource(R.string.recording_switch_title),
            body = stringResource(R.string.recording_switch_body),
            checked = enabled,
            tag = "recording_switch",
            onChange = { on -> env.controller.setEnabled(on); deleted = false },
        )
        Spacer(Modifier.height(6.dp))
        TextButton(stringResource(R.string.recording_delete_all), "recording_delete_all") {
            env.controller.deleteRecorded()
            deleted = true
        }
        if (deleted) {
            BasicText(
                stringResource(R.string.recording_deleted),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("recording_deleted"),
            )
        }
    }
}
