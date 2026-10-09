package com.qtekfun.ultimatemaps.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref

/**
 * The name of a voice language for the screens. Spanish and English have string resources (translated into every app
 * language); the others are shown under their own name ("Català", "Français", ...), as language pickers usually do, so a
 * translation of their names is not needed.
 */
@Composable
fun voiceLanguageName(language: VoiceLanguage): String = when (language) {
    VoiceLanguage.ES -> stringResource(R.string.voice_lang_es)
    VoiceLanguage.EN -> stringResource(R.string.voice_lang_en)
    else -> ownName(language)
}

/** The label of a choice of the "Voice language" setting. */
@Composable
fun voiceLanguagePrefLabel(pref: VoiceLanguagePref): String = when (pref) {
    VoiceLanguagePref.AUTO -> stringResource(R.string.nav_language_auto)
    VoiceLanguagePref.ES -> stringResource(R.string.nav_language_es)
    VoiceLanguagePref.EN -> stringResource(R.string.nav_language_en)
    else -> ownName(pref.language!!)
}

private fun ownName(language: VoiceLanguage): String =
    language.locale.getDisplayLanguage(language.locale).replaceFirstChar { it.titlecase(language.locale) }
