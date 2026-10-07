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
import com.qtekfun.ultimatemaps.search.HistorySettings
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * What the "Search history" section changes: the on/off [settings] and [clear], which deletes the stored searches
 * (the caller runs it off the main thread).
 */
class HistorySettingsEnv(val settings: HistorySettings, val clear: () -> Unit)

/**
 * Section "Search history" of the Settings screen: a switch (on by default) and a clear button. Turning the
 * switch off also deletes what was stored. The history never leaves the device.
 */
@Composable
fun HistorySection(env: HistorySettingsEnv) {
    var enabled by remember { mutableStateOf(env.settings.enabled) }
    var cleared by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.history_title))
    Card("history_card") {
        SwitchRow(
            title = stringResource(R.string.history_switch_title),
            body = stringResource(R.string.history_switch_body),
            checked = enabled,
            tag = "history_switch",
            onChange = { on ->
                enabled = on
                env.settings.enabled = on
                cleared = false
                if (!on) env.clear()
            },
        )
        Spacer(Modifier.height(6.dp))
        DestructiveButton(stringResource(R.string.history_clear), "history_clear") {
            env.clear()
            cleared = true
        }
        if (cleared) {
            BasicText(
                stringResource(R.string.history_cleared),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("history_cleared"),
            )
        }
    }
}
