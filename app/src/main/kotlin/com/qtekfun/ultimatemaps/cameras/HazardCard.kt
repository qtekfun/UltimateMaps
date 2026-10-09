package com.qtekfun.ultimatemaps.cameras

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.cameras.CameraDataRepository
import com.qtekfun.ultimatemaps.core.cameras.CameraSources
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.core.cameras.IncidentRepository
import com.qtekfun.ultimatemaps.map.HazardIds
import com.qtekfun.ultimatemaps.places.PanelButton
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** What the map needs for the optional camera and traffic layers; built by the activity from the application. */
class HazardsEnv(
    val settings: kotlinx.coroutines.flow.StateFlow<com.qtekfun.ultimatemaps.core.cameras.CameraSettings>,
    val cameras: CameraDataRepository,
    val incidents: com.qtekfun.ultimatemaps.core.cameras.IncidentDataRepository,
    val describer: HazardDescriber,
)

/** What the card of a tapped camera, zone or incident says. All text is already localised. */
data class HazardInfo(val title: String, val lines: List<String>, val note: String?, val attribution: String)

/** Observable state of the card. Written from the main thread only. */
class HazardCardState {
    var info by mutableStateOf<HazardInfo?>(null)
        private set

    fun open(info: HazardInfo) { this.info = info }
    fun close() { info = null }
}

/** Builds the [HazardInfo] of a marker id given by the map layer (see [HazardIds]); null when it is gone. */
class HazardDescriber(
    private val context: Context,
    private val cameras: CameraDataRepository,
    private val incidents: IncidentRepository,
    private val locale: () -> Locale = { Locale.getDefault() },
) {

    fun describe(id: String): HazardInfo? = when {
        id.startsWith(HazardIds.CAMERA) -> camera(id.removePrefix(HazardIds.CAMERA))
        id.startsWith(HazardIds.ZONE) -> zone(id.removePrefix(HazardIds.ZONE))
        id.startsWith(HazardIds.INCIDENT) -> incident(id.removePrefix(HazardIds.INCIDENT))
        else -> null
    }

    private fun camera(id: String): HazardInfo? {
        val c = cameras.camera(id) ?: return null
        val section = c.kind == com.qtekfun.ultimatemaps.core.cameras.CameraKind.SECTION
        val lines = buildList {
            if (c.road.isNotBlank()) add(context.getString(R.string.cam_card_road, c.road))
            add(c.maxSpeedKmh?.let { context.getString(R.string.cam_card_limit, it) } ?: context.getString(R.string.cam_card_limit_unknown))
            cameras.generatedMillis.value?.let { add(context.getString(R.string.cam_card_data_date, CameraAttribution.dateText(it, locale()))) }
        }
        return HazardInfo(
            context.getString(if (section) R.string.cam_card_section_title else R.string.cam_card_fixed_title), lines,
            context.getString(R.string.cam_card_fixed_note), CameraCredits.cameras(context, c.sources),
        )
    }

    private fun zone(id: String): HazardInfo? {
        val z = cameras.zone(id) ?: return null
        val lines = buildList {
            add(context.getString(R.string.cam_card_road, z.road + if (z.province.isNotBlank()) " (${z.province})" else ""))
            add(context.getString(R.string.cam_card_km, km(z.kmFromMeters), km(z.kmToMeters)))
            cameras.generatedMillis.value?.let { add(context.getString(R.string.cam_card_data_date, CameraAttribution.dateText(it, locale()))) }
        }
        return HazardInfo(context.getString(R.string.cam_card_zone_title), lines, context.getString(R.string.cam_card_zone_note), CameraCredits.cameras(context, CameraSources.DGT))
    }

    private fun km(meters: Int) = String.format(locale(), "%.1f", meters / 1000.0)

    private fun incident(id: String): HazardInfo? {
        val i = incidents.incident(id) ?: return null
        val title = when (i.kind) {
            IncidentKind.V16 -> R.string.inc_card_v16
            IncidentKind.ACCIDENT -> R.string.inc_card_accident
            IncidentKind.CLOSURE -> R.string.inc_card_closure
            IncidentKind.CONGESTION -> R.string.inc_card_congestion
            IncidentKind.OBSTACLE -> R.string.inc_card_obstacle
            IncidentKind.WEATHER -> R.string.inc_card_weather
            IncidentKind.ROADWORKS -> R.string.inc_card_roadworks
        }
        val lines = buildList {
            if (i.road.isNotBlank()) add(context.getString(R.string.cam_card_road, i.road))
            val place = listOfNotNull(i.municipality, i.province).filter { it.isNotBlank() }
            if (place.size == 2) add(context.getString(R.string.inc_card_place, place[0], place[1])) else place.firstOrNull()?.let(::add)
            i.startMillis?.let { add(context.getString(R.string.inc_card_since, CameraAttribution.dateText(it, locale()))) }
            i.endMillis?.let { add(context.getString(R.string.inc_card_until, CameraAttribution.dateText(it, locale()))) }
        }
        val downloaded = incidents.lastUpdateMillis.value?.let { context.getString(R.string.inc_card_downloaded, CameraAttribution.dateText(it, locale())) }
        return HazardInfo(context.getString(title), lines, downloaded, CameraCredits.incidents(context))
    }
}

/** Glue between a tap on a marker and its card. Main thread only; no Android here beyond [HazardDescriber]. */
class HazardCardController(
    private val describer: (String) -> HazardInfo?,
    val card: HazardCardState,
    private val onOpened: () -> Unit = {},
) {
    /** The map reported a tap on hazard [id]. */
    fun onTap(id: String) {
        val info = describer(id) ?: return
        card.open(info)
        onOpened()
    }
}

@Composable
fun HazardCard(state: HazardCardState, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    Column(modifier.fillMaxWidth().testTag("hazard_card")) {
        BasicText(info.title, style = Mapas.typography.title.copy(color = Mapas.colors.label), modifier = Modifier.testTag("hazard_title"))
        info.lines.forEach { BasicText(it, style = Mapas.typography.body.copy(color = Mapas.colors.label)) }
        info.note?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(it, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("hazard_note"))
        }
        Spacer(Modifier.height(6.dp))
        BasicText(info.attribution, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("hazard_attribution"))
        Spacer(Modifier.height(10.dp))
        PanelButton(stringResource(R.string.hazard_card_close), { state.close() }, Modifier.fillMaxWidth(), primary = false, tag = "hazard_close")
    }
}
