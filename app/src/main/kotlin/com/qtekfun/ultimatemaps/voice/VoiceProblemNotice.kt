package com.qtekfun.ultimatemaps.voice

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.voice.VoiceFailure
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import com.qtekfun.ultimatemaps.core.voice.isProblem
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * The guide shown when the voice cannot speak (nothing is drawn while it can). It is for the Settings screen; the
 * navigation screen should use the one-line [VoiceProblemBanner]. Navigation itself keeps working without voice.
 *
 * - No engine: why (common without Google services), the free engines on F-Droid (no proprietary store is
 *   linked), how to pick the engine in Android settings, and "Try again".
 * - Missing language data: install the voice data of the engine, or another engine.
 * - Failed: the engine does not respond; try again or choose another engine.
 */
@Composable
fun VoiceProblemNotice(status: VoiceStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (!status.isProblem) return
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().testTag("voice_problem_notice")) {
        val title = when (status) {
            is VoiceStatus.NoEngine -> stringResource(R.string.voice_problem_no_engine_title)
            is VoiceStatus.LanguageMissing -> stringResource(R.string.voice_problem_lang_title, voiceLanguageName(status.language))
            else -> stringResource(R.string.voice_problem_failed_title)
        }
        BasicText(title, style = Mapas.typography.body.copy(color = Mapas.colors.warning), modifier = Modifier.testTag("voice_problem_title"))
        Spacer(Modifier.height(4.dp))
        val body = when (status) {
            is VoiceStatus.NoEngine -> stringResource(R.string.voice_problem_no_engine_body)
            is VoiceStatus.LanguageMissing -> stringResource(R.string.voice_problem_lang_body, voiceLanguageName(status.language))
            is VoiceStatus.Failed -> stringResource(if (status.reason == VoiceFailure.INIT_FAILED) R.string.voice_problem_init_body else R.string.voice_problem_failed_body)
            else -> ""
        }
        BasicText(body, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        if (status is VoiceStatus.NoEngine || status is VoiceStatus.Failed) {
            Spacer(Modifier.height(6.dp))
            BasicText(stringResource(R.string.voice_install_intro), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
            VoiceInstall.suggested.forEach { e ->
                ActionText(stringResource(R.string.voice_install_engine, e.name), "voice_install_${e.packageId}") {
                    VoiceInstall.open(context, VoiceInstall.fdroidIntent(e.packageId))
                }
            }
            BasicText(stringResource(R.string.voice_install_then), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        if (status is VoiceStatus.LanguageMissing) {
            ActionText(stringResource(R.string.voice_install_data), "voice_install_data") { VoiceInstall.open(context, VoiceInstall.installDataIntent()) }
        }
        ActionText(stringResource(R.string.voice_open_tts_settings), "voice_open_tts_settings") { openTtsSettings(context) }
        ActionText(stringResource(R.string.voice_retry), "voice_retry", onRetry)
    }
}

private fun openTtsSettings(context: Context) {
    if (!VoiceInstall.open(context, VoiceInstall.ttsSettingsIntent())) {
        VoiceInstall.open(context, android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun ActionText(label: String, tag: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = Mapas.typography.callout.copy(color = Mapas.colors.accent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp).testTag(tag),
    )
}

/**
 * One line for the navigation screen: "Silent navigation: ..." when the voice cannot speak, nothing otherwise.
 * [onClick] should open the guide (Settings). The text is also what a screen reader says.
 */
@Composable
fun VoiceProblemBanner(status: VoiceStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!status.isProblem) return
    val text = when (status) {
        is VoiceStatus.NoEngine -> stringResource(R.string.voice_banner_no_engine)
        is VoiceStatus.LanguageMissing -> stringResource(R.string.voice_banner_lang, voiceLanguageName(status.language))
        else -> stringResource(R.string.voice_banner_failed)
    }
    BasicText(
        text,
        style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
        modifier = modifier
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.field)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("voice_problem_banner"),
    )
}
