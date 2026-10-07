package com.qtekfun.mapas.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.ui.theme.Mapas

/**
 * Imported GPX tracks, as items of the lists overview: one row per track with a Show/Hide switch (draws it on the
 * map) and a button that frames it. Shown only when [tracks] is not null.
 */
fun LazyListScope.tracksItems(tracks: TrackLayerController?) {
    if (tracks == null) return
    item(key = "tracks_header") {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(12.dp))
            BasicText(
                stringResource(R.string.tracks_title),
                style = Mapas.typography.title.copy(color = Mapas.colors.label),
                modifier = Modifier.testTag("tracks_title"),
            )
            if (tracks.state.tracks.isEmpty()) PanelNote(stringResource(R.string.tracks_empty), "tracks_empty")
        }
    }
    items(tracks.state.tracks, key = { "track_${it.id}" }) { info ->
        val shown = info.id in tracks.state.visible
        Row(
            Modifier.fillMaxWidth().testTag("track_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(info.name, style = Mapas.typography.body.copy(color = Mapas.colors.label), maxLines = 1)
                BasicText(
                    stringResource(R.string.track_points, info.pointCount),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    maxLines = 1,
                )
            }
            PanelButton(
                stringResource(if (shown) R.string.track_hide else R.string.track_show),
                { tracks.toggle(info.id) }, primary = shown, tag = "track_toggle",
            )
            PanelButton(stringResource(R.string.track_fit), { tracks.fitTo(info.id) }, tag = "track_fit")
        }
    }
}
