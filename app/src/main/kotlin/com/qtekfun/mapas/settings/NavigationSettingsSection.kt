package com.qtekfun.mapas.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.routing.BikeCycleways
import com.qtekfun.mapas.core.voice.InstructionText
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.NavSettingsStore
import com.qtekfun.mapas.core.voice.UnitsPref
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguagePref
import com.qtekfun.mapas.core.voice.VoicePriority
import com.qtekfun.mapas.core.voice.VoiceStatus
import com.qtekfun.mapas.route.BikeCyclewaysText
import com.qtekfun.mapas.ui.theme.Mapas
import com.qtekfun.mapas.voice.VoiceProblemNotice
import java.util.Locale

/** What the Navigation section reads and changes: the persistent settings and the voice (for "Test voice"). */
class NavigationSettingsEnv(
    val store: NavSettingsStore,
    val guide: VoiceGuide,
    val locale: () -> Locale = Locale::getDefault,
    /** Live Update chips exist from Android 16 (API 36); below it the switch is hidden. */
    val liveUpdateAvailable: Boolean = android.os.Build.VERSION.SDK_INT >= 36,
)

/**
 * Section "Navigation" of the Settings screen: voice on/off, only important prompts, volume, units, voice language,
 * "Test voice" (with the guide to install an engine if there is none), and the route options to avoid by default.
 */
@Composable
fun NavigationSection(env: NavigationSettingsEnv) {
    val s by env.store.settings.collectAsState()
    val status by env.guide.status.collectAsState()
    // Starting the engine here (it is not spoken yet) tells the user about a missing engine before the first trip.
    LaunchedEffect(Unit) { env.guide.prepare(s.voiceLanguage.resolve(env.locale())) }

    SectionTitle(stringResource(R.string.hub_group_voice))
    Card("nav_voice_card") {
        SwitchRow(
            title = stringResource(R.string.nav_voice_title),
            body = stringResource(R.string.nav_voice_body),
            checked = s.voiceEnabled,
            tag = "nav_voice_switch",
            onChange = { on -> env.store.update { it.copy(voiceEnabled = on) } },
        )
        if (s.voiceEnabled) {
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = stringResource(R.string.nav_important_title),
                body = stringResource(R.string.nav_important_body),
                checked = s.importantOnly,
                tag = "nav_important_switch",
                onChange = { on -> env.store.update { it.copy(importantOnly = on) } },
            )
        }
    }
    if (s.voiceEnabled) {
        Spacer(Modifier.height(10.dp))
        Card("nav_volume_card") {
            BasicText(stringResource(R.string.nav_volume_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            NavSettings.VOLUME_CHOICES.forEach { v ->
                ChoiceRow("$v %", s.volumePercent == v, radio = true, tag = "nav_volume_$v") { env.store.update { it.copy(volumePercent = v) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card("nav_language_card") {
            BasicText(stringResource(R.string.nav_language_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            VoiceLanguagePref.entries.forEach { l ->
                val label = when (l) {
                    VoiceLanguagePref.AUTO -> R.string.nav_language_auto
                    VoiceLanguagePref.ES -> R.string.nav_language_es
                    VoiceLanguagePref.EN -> R.string.nav_language_en
                }
                ChoiceRow(stringResource(label), s.voiceLanguage == l, radio = true, tag = "nav_language_${l.name.lowercase()}") {
                    env.store.update { it.copy(voiceLanguage = l) }
                    env.guide.prepare(l.resolve(env.locale())) // checks that this language has a voice
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Card("nav_test_card") {
        TextButton(stringResource(R.string.nav_test_voice), "nav_test_voice") { testVoice(env, s) }
        when (status) {
            is VoiceStatus.Ready -> BasicText(
                stringResource(R.string.nav_voice_ready),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("nav_voice_ready"),
            )
            VoiceStatus.Starting -> BasicText(stringResource(R.string.nav_voice_starting), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            else -> Unit
        }
        VoiceProblemNotice(status, onRetry = { env.guide.retry(s.voiceLanguage.resolve(env.locale())) })
    }
    SectionTitle(stringResource(R.string.hub_group_display))
    Card("nav_units_card") {
        BasicText(stringResource(R.string.nav_units_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        UnitsPref.entries.forEach { u ->
            val label = when (u) {
                UnitsPref.AUTO -> R.string.nav_units_auto
                UnitsPref.METRIC -> R.string.nav_units_metric
                UnitsPref.IMPERIAL -> R.string.nav_units_imperial
            }
            ChoiceRow(stringResource(label), s.units == u, radio = true, tag = "nav_units_${u.name.lowercase()}") { env.store.update { it.copy(units = u) } }
        }
    }
    Spacer(Modifier.height(10.dp))
    Card("nav_view_card") {
        SwitchRow(
            title = stringResource(R.string.nav_view3d_title),
            body = stringResource(R.string.nav_view3d_body),
            checked = s.view3d,
            tag = "nav_view3d_switch",
            onChange = { on -> env.store.update { it.copy(view3d = on) } },
        )
        if (s.view3d) {
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = stringResource(R.string.nav_buildings3d_title),
                body = stringResource(R.string.nav_buildings3d_body),
                checked = s.buildings3d,
                tag = "nav_buildings3d_switch",
                onChange = { on -> env.store.update { it.copy(buildings3d = on) } },
            )
        }
    }
    if (env.liveUpdateAvailable) {
        Spacer(Modifier.height(10.dp))
        Card("nav_live_update_card") {
            SwitchRow(
                title = stringResource(R.string.live_update_title),
                body = stringResource(R.string.live_update_body),
                checked = s.liveUpdateChip,
                tag = "nav_live_update_switch",
                onChange = { on -> env.store.update { it.copy(liveUpdateChip = on) } },
            )
        }
    }
    SectionTitle(stringResource(R.string.hub_group_route))
    Card("nav_avoid_card") {
        BasicText(stringResource(R.string.nav_avoid_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(stringResource(R.string.nav_avoid_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        Spacer(Modifier.height(6.dp))
        SwitchRow(stringResource(R.string.nav_avoid_motorways), "", s.avoidMotorways, "nav_avoid_motorways") { on -> env.store.update { it.copy(avoidMotorways = on) } }
        SwitchRow(stringResource(R.string.nav_avoid_tolls), "", s.avoidTolls, "nav_avoid_tolls") { on -> env.store.update { it.copy(avoidTolls = on) } }
        SwitchRow(stringResource(R.string.nav_avoid_ferries), "", s.avoidFerries, "nav_avoid_ferries") { on -> env.store.update { it.copy(avoidFerries = on) } }
        SwitchRow(stringResource(R.string.nav_avoid_unpaved), "", s.avoidUnpaved, "nav_avoid_unpaved") { on -> env.store.update { it.copy(avoidUnpaved = on) } }
    }
    Spacer(Modifier.height(10.dp))
    Card("nav_bike_cycleways_card") {
        BasicText(stringResource(R.string.bike_cycleways_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(stringResource(R.string.bike_cycleways_settings_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        BikeCycleways.entries.forEach { level ->
            ChoiceRow(
                stringResource(BikeCyclewaysText.full(level)), s.bikeCycleways == level, radio = true,
                tag = "nav_bike_cycleways_${level.name.lowercase()}",
            ) { env.store.update { it.copy(bikeCycleways = level) } }
        }
    }
}

/** Says a real sample prompt in the chosen language and units, at the chosen volume, even if the voice is off. */
private fun testVoice(env: NavigationSettingsEnv, s: NavSettings) {
    val language = s.voiceLanguage.resolve(env.locale())
    env.guide.setVolume(s.volumePercent)
    env.guide.speak(Utterance(InstructionText.test(s.units.resolve(env.locale()), language), VoicePriority.URGENT, language, key = "test"))
}
