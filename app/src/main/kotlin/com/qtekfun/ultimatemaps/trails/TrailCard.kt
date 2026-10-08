package com.qtekfun.ultimatemaps.trails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.routes.RouteAttribution
import com.qtekfun.ultimatemaps.core.routes.Trail
import com.qtekfun.ultimatemaps.core.routes.TrailKind
import com.qtekfun.ultimatemaps.core.routes.TrailLevel
import com.qtekfun.ultimatemaps.places.PanelButton
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** Observable state of the route card. Written from the main thread only. */
class TrailCardState {
    var trail by mutableStateOf<Trail?>(null)
        private set

    fun open(trail: Trail) {
        this.trail = trail
    }

    fun close() {
        trail = null
    }
}

/** What the map needs for the optional route layer; built by the activity from the application. */
class TrailsEnv(
    val settings: kotlinx.coroutines.flow.StateFlow<com.qtekfun.ultimatemaps.core.routes.RouteSettings>,
    val repository: com.qtekfun.ultimatemaps.core.routes.RouteRepository,
)

/** What the sheet needs to show the card; built by the panel host. */
class TrailCardHost(
    val state: TrailCardState,
    val generatedMillis: () -> Long?,
    val onGoToStart: (Trail) -> Unit,
)

/** Text the card shows that does not need Android resources, so it is unit-tested on the JVM. */
object TrailCardText {
    /** "850 m" below a kilometre, else "12.5 km" (one decimal, decimal separator of [locale]). */
    fun length(meters: Int, locale: Locale): String =
        if (meters < 1000) String.format(locale, "%d m", meters) else String.format(locale, "%.1f km", meters / 1000.0)

    /** What the route knows about its start: the name (or ref) is the name; no address is invented. */
    fun startPlace(trail: Trail, category: String): PlaceInfo? = trail.start?.let {
        PlaceInfo(name = trail.title.ifBlank { category }, point = it, address = null, category = category)
    }
}

@androidx.annotation.StringRes
internal fun trailKindLabel(kind: TrailKind): Int = when (kind) {
    TrailKind.HIKING -> R.string.trail_kind_label_hiking
    TrailKind.CYCLING -> R.string.trail_kind_label_cycling
    TrailKind.MTB -> R.string.trail_kind_label_mtb
}

@androidx.annotation.StringRes
internal fun trailLevelLabel(level: TrailLevel): Int = when (level) {
    TrailLevel.LOCAL -> R.string.trail_level_local
    TrailLevel.REGIONAL -> R.string.trail_level_regional
    TrailLevel.NATIONAL -> R.string.trail_level_national
    TrailLevel.INTERNATIONAL -> R.string.trail_level_international
}

/**
 * Card of a tapped hiking or cycling route: name (or ref), kind and reach, reference, who maintains it, the length (the
 * `distance` tag when OpenStreetMap has one, else a measured length said to be approximate), a note that this is community
 * data, the attribution and "Route to the start". Every touch target is at least 48 dp.
 */
@Composable
fun TrailCard(
    state: TrailCardState,
    generatedMillis: Long?,
    onGoToStart: (Trail) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = state.trail ?: return
    val locale = LocalConfiguration.current.locales[0]
    val english = locale.language != "es"
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("trail_card")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    t.title.ifBlank { stringResource(R.string.trail_card_title_unknown) },
                    style = Mapas.typography.title.copy(color = Mapas.colors.label), modifier = Modifier.testTag("trail_title"),
                )
                BasicText(
                    stringResource(R.string.trail_card_kind, stringResource(trailKindLabel(t.kind)), stringResource(trailLevelLabel(t.level))),
                    style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("trail_kind"),
                )
            }
            BasicText(
                stringResource(R.string.trail_card_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .heightIn(min = TOUCH)
                    .clickable(role = Role.Button, onClick = state::close)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("trail_close"),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (t.name.isNotBlank() && t.ref.isNotBlank()) {
            BasicText(stringResource(R.string.trail_card_ref, t.ref), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("trail_ref"))
        }
        if (t.operator.isNotBlank()) {
            BasicText(stringResource(R.string.trail_card_operator, t.operator), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("trail_operator"))
        }
        if (t.lengthMeters > 0) {
            val text = TrailCardText.length(t.lengthMeters, locale)
            BasicText(
                stringResource(if (t.lengthFromTag) R.string.trail_card_length else R.string.trail_card_length_measured, text),
                style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("trail_length"),
            )
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.trail_card_note),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("trail_note"),
        )
        BasicText(
            RouteAttribution.text(english),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("trail_card_attribution"),
        )
        generatedMillis?.let {
            BasicText(
                stringResource(R.string.trail_card_data_date, CameraAttribution.dateText(it, english)),
                style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("trail_data_date"),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (t.start != null) {
            PanelButton(
                stringResource(R.string.trail_go_start), { onGoToStart(t) }, Modifier.heightIn(min = TOUCH).fillMaxWidth(),
                primary = true, tag = "trail_go_start",
            )
        }
    }
}

private val TOUCH = 48.dp
