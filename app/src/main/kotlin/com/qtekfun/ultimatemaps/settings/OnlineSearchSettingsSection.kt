package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.search.OnlineSearch
import com.qtekfun.ultimatemaps.core.search.OnlineSearchSettingsStore
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/**
 * What the "Online search" section reads and changes (`play` flavor only: the host leaves it null in `foss`, which hides the
 * section). [onChanged] lists or removes the geocoder host in the connection list after a change.
 */
class OnlineSearchSettingsEnv(val store: OnlineSearchSettingsStore, val onChanged: () -> Unit = {})

/**
 * Section "Online search": one switch (off by default) and the geocoder address. The text says exactly what is sent. The
 * default address is the public Photon instance; the owner can point it to their own server.
 */
@Composable
fun OnlineSearchSection(env: OnlineSearchSettingsEnv) {
    var enabled by remember { mutableStateOf(env.store.enabled) }
    var url by remember { mutableStateOf(env.store.serverUrl) }
    var field by remember { mutableStateOf(env.store.serverUrl) }
    var invalid by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.online_search_title))
    Card("online_search_card") {
        SwitchRow(
            stringResource(R.string.online_search_switch), stringResource(R.string.online_search_body), enabled, "online_search_switch",
        ) {
            env.store.enabled = it
            enabled = it
            env.onChanged()
        }
        Spacer(Modifier.height(8.dp))
        BasicText(stringResource(R.string.online_search_url_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = field,
            onValueChange = { field = it; invalid = false },
            singleLine = true,
            textStyle = Mapas.typography.callout.copy(color = Mapas.colors.label),
            cursorBrush = SolidColor(Mapas.colors.accent),
            decorationBox = { inner ->
                if (field.isEmpty()) {
                    BasicText(OnlineSearch.DEFAULT_PHOTON_URL, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
                }
                inner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(Mapas.shapes.field)
                .background(Mapas.colors.field)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("online_search_url_field"),
        )
        if (invalid) {
            BasicText(
                stringResource(R.string.online_search_url_invalid),
                style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                modifier = Modifier.testTag("online_search_url_message"),
            )
        }
        Row {
            TextButton(stringResource(R.string.online_search_url_save), "online_search_url_save") {
                val text = field.trim()
                if (text.isEmpty() || OnlineSearch.isValidUrl(text)) {
                    env.store.serverUrl = text
                    url = text
                    field = text
                    invalid = false
                    env.onChanged()
                } else invalid = true
            }
            TextButton(stringResource(R.string.online_search_url_reset), "online_search_url_reset", enabled = url.isNotEmpty() || field.isNotEmpty()) {
                env.store.serverUrl = ""
                url = ""
                field = ""
                invalid = false
                env.onChanged()
            }
        }
        BasicText(
            stringResource(R.string.online_search_credit),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("online_search_credit"),
        )
    }
}
