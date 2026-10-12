package com.qtekfun.ultimatemaps.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.search.PrefsSystemGeocoderSettings
import com.qtekfun.ultimatemaps.settings.Card
import com.qtekfun.ultimatemaps.settings.SectionTitle
import com.qtekfun.ultimatemaps.settings.SwitchRow

/**
 * The switch of the optional online address lookup through the phone's geocoder (off by default). The body says plainly
 * what leaves the phone and where it goes. Only the `play` flavor has this file.
 */
@Composable
internal fun SystemGeocoderSection() {
    val settings = PrefsSystemGeocoderSettings(LocalContext.current)
    var enabled by remember { mutableStateOf(settings.enabled) }
    SectionTitle(stringResource(R.string.system_geocoder_title))
    Card("system_geocoder_card") {
        SwitchRow(
            title = stringResource(R.string.system_geocoder_switch_title),
            body = stringResource(R.string.system_geocoder_switch_body),
            checked = enabled,
            tag = "system_geocoder_switch",
            onChange = { on ->
                enabled = on
                settings.enabled = on
            },
        )
    }
}
