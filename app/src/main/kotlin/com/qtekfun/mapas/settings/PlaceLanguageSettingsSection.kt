package com.qtekfun.mapas.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.search.PlaceLanguagePref
import com.qtekfun.mapas.core.search.PlaceLanguageStore
import com.qtekfun.mapas.ui.theme.Mapas

/** What the "Language of place information" section reads and changes. */
class PlaceLanguageSettingsEnv(val store: PlaceLanguageStore)

/**
 * Section "Place information": the language of the names, the address and the category in search results and the place card
 * (Automatic follows the app language; Local names shows the name as written locally). It does not change the map labels.
 */
@Composable
fun PlaceLanguageSection(env: PlaceLanguageSettingsEnv) {
    var current by remember { mutableStateOf(env.store.preference) }
    SectionTitle(stringResource(R.string.locale_title))
    Card("place_language_card") {
        BasicText(stringResource(R.string.locale_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        Spacer(Modifier.height(6.dp))
        PlaceLanguagePref.entries.forEach { pref ->
            val label = when (pref) {
                PlaceLanguagePref.AUTO -> R.string.locale_auto
                PlaceLanguagePref.ES -> R.string.locale_es
                PlaceLanguagePref.EN -> R.string.locale_en
                PlaceLanguagePref.LOCAL -> R.string.locale_local
            }
            ChoiceRow(stringResource(label), current == pref, radio = true, tag = "place_language_${pref.name.lowercase()}") {
                env.store.preference = pref
                current = pref
            }
        }
        if (current == PlaceLanguagePref.LOCAL) {
            BasicText(stringResource(R.string.locale_local_note), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
    }
}
