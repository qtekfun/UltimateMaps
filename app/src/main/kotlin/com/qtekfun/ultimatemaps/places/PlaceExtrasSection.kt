package com.qtekfun.ultimatemaps.places

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.OpenLocationCode
import com.qtekfun.ultimatemaps.core.search.OpeningHours
import com.qtekfun.ultimatemaps.core.search.OpeningHours.Summary.Kind
import com.qtekfun.ultimatemaps.core.search.PlaceExtras
import com.qtekfun.ultimatemaps.core.search.Wheelchair
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.time.LocalDateTime
import java.time.format.TextStyle

/**
 * The Plus Code of the place (always, it is computed from the position) and, when the map data has them, its phone
 * (tap to open the dialer: nothing is dialled), website (opens the browser), wheelchair access and opening hours
 * with a status line ("Open now · Closes at 20:00", "Closed · Opens tomorrow at 09:00", or "unknown" when the text is
 * beyond the small parser) and a note when public-holiday rules were left out.
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
            val summary = remember(hours) { OpeningHours.summary(hours, now()) }
            val locale = LocalConfiguration.current.locales[0]
            val time = "%02d:%02d".format(summary.minutes / 60 % 24, summary.minutes % 60)
            val day = summary.day?.getDisplayName(TextStyle.FULL, locale).orEmpty()
            ExtraLine(
                when (summary.kind) {
                    Kind.OPEN_ALWAYS -> stringResource(R.string.place_open_now)
                    Kind.CLOSES_AT -> stringResource(R.string.place_open_closes_at, time)
                    Kind.CLOSES_ON -> stringResource(R.string.place_open_closes_on, day, time)
                    Kind.CLOSED_ALWAYS -> stringResource(R.string.place_closed_now)
                    Kind.OPENS_AT -> stringResource(R.string.place_closed_opens_at, time)
                    Kind.OPENS_TOMORROW -> stringResource(R.string.place_closed_opens_tomorrow, time)
                    Kind.OPENS_ON -> stringResource(R.string.place_closed_opens_on, day, time)
                    Kind.UNKNOWN -> stringResource(R.string.place_open_unknown)
                },
                tag = "place_open_state",
            )
            if (summary.holidaysIgnored) ExtraLine(stringResource(R.string.place_hours_holidays_note), tag = "place_hours_note")
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
