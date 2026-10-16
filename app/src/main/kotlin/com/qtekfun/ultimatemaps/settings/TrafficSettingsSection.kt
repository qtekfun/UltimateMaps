package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.traffic.TomTomEndpoints
import com.qtekfun.ultimatemaps.core.traffic.TrafficKeyStore
import com.qtekfun.ultimatemaps.core.traffic.TrafficSettingsStore
import com.qtekfun.ultimatemaps.traffic.TomTomKeyStore
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * What the "Live traffic" section reads and changes (`play` flavor only: the host leaves it null in `foss`, which hides the
 * section). [onChanged] lists or removes the TomTom host in the connection list after the switch changes.
 */
class TrafficSettingsEnv(
    val settings: TrafficSettingsStore,
    val keys: TrafficKeyStore,
    val onChanged: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
)

/**
 * Section "Live traffic": one switch (off by default; the text says exactly what is sent and to whom) and the user's own TomTom
 * key, pasted here and kept encrypted in the Android Keystore. The key is never shown again.
 */
@Composable
fun TrafficSection(env: TrafficSettingsEnv) {
    var enabled by remember { mutableStateOf(env.settings.enabled) }
    var mapLayer by remember { mutableStateOf(env.settings.mapLayer) }
    var hasKey by remember { mutableStateOf(!env.keys.read().isNullOrBlank()) }
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.traffic_title))
    Card("traffic_card") {
        SwitchRow(stringResource(R.string.traffic_switch), stringResource(R.string.traffic_body), enabled, "traffic_switch") {
            env.settings.enabled = it
            enabled = it
            env.onChanged()
        }
        BasicText(
            stringResource(R.string.traffic_honesty),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 6.dp).testTag("traffic_honesty"),
        )
    }
    if (!enabled) return
    Spacer(Modifier.height(10.dp))
    Card("traffic_map_card") {
        SwitchRow(stringResource(R.string.traffic_map_title), stringResource(R.string.traffic_map_body), mapLayer, "traffic_map_switch") {
            env.settings.mapLayer = it
            mapLayer = it
        }
    }
    Spacer(Modifier.height(10.dp))
    Card("traffic_key_card") {
        BasicText(stringResource(R.string.traffic_key_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(
            stringResource(if (hasKey) R.string.traffic_key_saved else R.string.traffic_key_none),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("traffic_key_state"),
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(Mapas.shapes.field).background(Mapas.colors.field).padding(10.dp)) {
            if (text.isEmpty()) BasicText(stringResource(R.string.traffic_key_hint), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            BasicTextField(
                value = text, onValueChange = { text = it; invalid = false }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
                cursorBrush = SolidColor(Mapas.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                modifier = Modifier.fillMaxWidth().testTag("traffic_key_field"),
            )
        }
        if (invalid) {
            BasicText(
                stringResource(R.string.traffic_key_invalid),
                style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                modifier = Modifier.testTag("traffic_key_invalid"),
            )
        }
        TextButton(stringResource(R.string.traffic_key_save), "traffic_key_save", enabled = text.isNotBlank()) {
            val key = TomTomKeyStore.clean(text)
            if (key == null) invalid = true else {
                env.keys.write(key)
                hasKey = true
                text = ""
                invalid = false
            }
        }
        if (hasKey) TextButton(stringResource(R.string.traffic_key_remove), "traffic_key_remove", color = Mapas.colors.warning) {
            env.keys.clear()
            hasKey = false
        }
        TextButton(stringResource(R.string.traffic_key_help), "traffic_key_help") { env.openUrl(TomTomEndpoints.KEY_REQUEST_URL) }
        BasicText(stringResource(R.string.traffic_key_help_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    }
}
