package com.qtekfun.mapas.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.OpenLocationCode
import com.qtekfun.mapas.core.search.OpenState
import com.qtekfun.mapas.core.search.OpeningHours
import com.qtekfun.mapas.core.search.PlaceExtras
import com.qtekfun.mapas.core.search.Wheelchair
import com.qtekfun.mapas.ui.theme.Mapas
import java.time.LocalDateTime

/**
 * The Plus Code of the place (always, it is computed from the position) and, when the map data has them, its phone
 * (tap to open the dialer: nothing is dialled), website (opens the browser), wheelchair access and opening hours
 * with an "open now" line (or "unknown" when the text is beyond the small parser).
 */
@Composable
fun PlaceExtrasSection(
    point: LatLon,
    extras: PlaceExtras?,
    onDial: (String) -> Unit,
    onOpenWebsite: (String) -> Unit,
    now: () -> LocalDateTime,
    modifier: Modifier = Modifier,
) {
    val plusCode = remember(point) { OpenLocationCode.encode(point) }
    Column(modifier.fillMaxWidth()) {
        BasicText(
            stringResource(R.string.place_plus_code, plusCode),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 2.dp).testTag("place_plus_code"),
        )
        if (extras == null || extras.isEmpty) return@Column
        val dial = PlaceExtras.dialUri(extras.phone)
        val phone = PlaceExtras.firstPhone(extras.phone)
        if (dial != null && phone != null) {
            val description = stringResource(R.string.place_call_description, phone)
            ExtraLine(phone, accent = true, tag = "place_phone", description = description) { onDial(dial) }
        }
        val web = PlaceExtras.webUri(extras.website)
        if (web != null) {
            val description = stringResource(R.string.place_website_description)
            ExtraLine(
                web.substringAfter("://").removePrefix("www.").trimEnd('/'), accent = true, tag = "place_website", description = description,
            ) { onOpenWebsite(web) }
        }
        extras.wheelchair?.let { w ->
            ExtraLine(
                stringResource(
                    when (w) {
                        Wheelchair.YES -> R.string.place_wheelchair_yes
                        Wheelchair.LIMITED -> R.string.place_wheelchair_limited
                        Wheelchair.NO -> R.string.place_wheelchair_no
                    },
                ),
                tag = "place_wheelchair",
            )
        }
        extras.openingHours?.takeIf { it.isNotBlank() }?.let { hours ->
            ExtraLine(stringResource(R.string.place_hours, hours.trim()), tag = "place_hours")
            val state = remember(hours) { OpeningHours.stateAt(hours, now()) }
            ExtraLine(
                stringResource(
                    when (state) {
                        OpenState.OPEN -> R.string.place_open_now
                        OpenState.CLOSED -> R.string.place_closed_now
                        OpenState.UNKNOWN -> R.string.place_open_unknown
                    },
                ),
                tag = "place_open_state",
            )
        }
    }
}

@Composable
private fun ExtraLine(text: String, tag: String, accent: Boolean = false, description: String? = null, onClick: (() -> Unit)? = null) {
    var m = Modifier.fillMaxWidth().heightIn(min = if (onClick != null) 44.dp else 24.dp)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    if (description != null) m = m.semantics { contentDescription = description }
    Column(m.semantics(mergeDescendants = true) {}.testTag(tag), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        BasicText(
            text,
            style = Mapas.typography.body.copy(color = if (accent) Mapas.colors.accent else Mapas.colors.label),
        )
    }
}
