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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.weather.AemetEndpoints
import com.qtekfun.ultimatemaps.core.weather.WeatherRefresh
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import com.qtekfun.ultimatemaps.weather.WeatherAlertsController
import com.qtekfun.ultimatemaps.weather.WeatherTimes
import java.time.ZoneId

/** What the "Weather alerts (AEMET)" group of the Alerts category reads and changes. [openUrl] opens a page in the user's browser. */
class WeatherSettingsEnv(
    val controller: WeatherAlertsController,
    val openUrl: (String) -> Unit,
)

/**
 * Group "Weather alerts (AEMET)": the switch (off by default; the text says what is asked, of whom and that the key identifies
 * the user), the API key (pasted by the user, stored in the Android Keystore, never shown again), a link to the page that
 * issues keys, the yellow-warnings switch and "Check now" with the result of the last check. The options appear only while
 * the first switch is on.
 */
@Composable
fun WeatherSection(env: WeatherSettingsEnv) {
    val c = env.controller
    val s by c.settingsStore.settings.collectAsState()
    Spacer(Modifier.height(10.dp))
    SectionTitle(stringResource(R.string.weather_group_title))
    Card("weather_enable_card") {
        SwitchRow(stringResource(R.string.weather_switch_title), stringResource(R.string.weather_switch_body), s.enabled, "weather_switch") { on ->
            c.settingsStore.update { it.copy(enabled = on) }
        }
        BasicText(
            stringResource(R.string.weather_honesty),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 6.dp).testTag("weather_honesty"),
        )
        BasicText(
            stringResource(R.string.weather_attribution),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("weather_attribution"),
        )
    }
    if (!s.enabled) return

    Spacer(Modifier.height(10.dp))
    KeyCard(env)
    Spacer(Modifier.height(10.dp))
    Card("weather_yellow_card") {
        SwitchRow(stringResource(R.string.weather_yellow_title), stringResource(R.string.weather_yellow_body), s.showYellow, "weather_yellow_switch") { on ->
            c.settingsStore.update { it.copy(showYellow = on) }
        }
    }
    Spacer(Modifier.height(10.dp))
    CheckCard(env)
}

@Composable
private fun KeyCard(env: WeatherSettingsEnv) {
    val c = env.controller
    val hasKey by c.hasKey.collectAsState()
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Card("weather_key_card") {
        BasicText(stringResource(R.string.weather_key_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(
            stringResource(if (hasKey) R.string.weather_key_saved else R.string.weather_key_none),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("weather_key_state"),
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(Mapas.shapes.field).background(Mapas.colors.field).padding(10.dp)) {
            if (text.isEmpty()) BasicText(stringResource(R.string.weather_key_hint), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            BasicTextField(
                value = text, onValueChange = { text = it; invalid = false }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
                cursorBrush = SolidColor(Mapas.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                modifier = Modifier.fillMaxWidth().testTag("weather_key_field"),
            )
        }
        if (invalid) {
            BasicText(
                stringResource(R.string.weather_key_invalid),
                style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                modifier = Modifier.testTag("weather_key_invalid"),
            )
        }
        TextButton(stringResource(R.string.weather_key_save), "weather_key_save", enabled = text.isNotBlank()) {
            if (c.saveKey(text)) { text = ""; invalid = false } else invalid = true
        }
        if (hasKey) TextButton(stringResource(R.string.weather_key_remove), "weather_key_remove", color = Mapas.colors.warning) { c.removeKey() }
        TextButton(stringResource(R.string.weather_key_help), "weather_key_help") { env.openUrl(AemetEndpoints.KEY_REQUEST_URL) }
        BasicText(stringResource(R.string.weather_key_help_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        BasicText(
            stringResource(R.string.weather_key_privacy),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 6.dp).testTag("weather_key_privacy"),
        )
    }
}

@Composable
private fun CheckCard(env: WeatherSettingsEnv) {
    val c = env.controller
    val hasKey by c.hasKey.collectAsState()
    val checking by c.checking.collectAsState()
    val status by c.status.collectAsState()
    val checkResult by c.checkResult.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    Card("weather_check_card") {
        TextButton(stringResource(R.string.weather_check_now), "weather_check_now", enabled = hasKey && !checking) { c.checkNow() }
        val last = status.lastCheckMillis
        val result = checkResult ?: status.lastResult
        val text = when {
            checking -> stringResource(R.string.weather_checking)
            result == null -> stringResource(R.string.weather_status_never)
            else -> when (result) {
                WeatherRefresh.Updated -> stringResource(R.string.weather_status_ok, last?.let { WeatherTimes.text(it, it, ZoneId.systemDefault(), locale) }.orEmpty())
                WeatherRefresh.TooSoon -> stringResource(R.string.weather_status_too_soon)
                WeatherRefresh.Fresh -> stringResource(R.string.weather_status_fresh)
                WeatherRefresh.KeyRejected -> stringResource(R.string.weather_status_rejected)
                is WeatherRefresh.Failed -> stringResource(R.string.weather_status_failed)
                WeatherRefresh.NoKey, WeatherRefresh.Disabled -> stringResource(R.string.weather_status_never)
            }
        }
        BasicText(text, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("weather_status"))
    }
}
