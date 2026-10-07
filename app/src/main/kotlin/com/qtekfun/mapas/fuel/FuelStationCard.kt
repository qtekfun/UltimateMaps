package com.qtekfun.mapas.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.fuel.FuelStation
import com.qtekfun.mapas.map.FuelMapLayer
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.places.PanelNote
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.route.StopResult
import com.qtekfun.mapas.route.RoutePreviewController
import com.qtekfun.mapas.ui.theme.Mapas

/** Observable state of the petrol-station card. Written from the main thread only. */
class FuelCardState {
    var station by mutableStateOf<FuelStation?>(null)
        private set

    /** Whether the open station is already in the saved places. */
    var saved by mutableStateOf(false)

    /** Why the last "Add stop" was refused (shown in the card); null when none. */
    var notice by mutableStateOf<StopResult?>(null)

    fun open(station: FuelStation) {
        this.station = station
        saved = false
        notice = null
    }

    fun close() {
        station = null
        saved = false
        notice = null
    }
}

/** What the saved places and the route know about a station. */
fun FuelStation.toPlaceInfo(category: String? = null): PlaceInfo = PlaceInfo(
    name = brand.ifBlank { address },
    point = location,
    address = listOf(address, municipality).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null },
    category = category,
)

/** "Updated N min ago" broken into a unit and a value so it can be tested without Android. */
enum class AgoUnit { NOW, MINUTES, HOURS, DAYS, UNKNOWN }

data class Ago(val unit: AgoUnit, val value: Int = 0)

fun agoOf(updatedMillis: Long?, nowMillis: Long): Ago {
    if (updatedMillis == null) return Ago(AgoUnit.UNKNOWN)
    val minutes = ((nowMillis - updatedMillis).coerceAtLeast(0) / 60_000L).toInt()
    return when {
        minutes < 1 -> Ago(AgoUnit.NOW)
        minutes < 60 -> Ago(AgoUnit.MINUTES, minutes)
        minutes < 60 * 24 -> Ago(AgoUnit.HOURS, minutes / 60)
        else -> Ago(AgoUnit.DAYS, minutes / (60 * 24))
    }
}

/**
 * The attribution line under the prices, in ONE place so it can be swapped for `FuelAttribution.text(...)` of
 * `:core-fuel` once that exists: "Source: <ministry> · updated N min ago".
 */
@Composable
fun fuelAttributionText(updatedMillis: Long?, nowMillis: Long): String {
    val ago = agoOf(updatedMillis, nowMillis)
    val when_ = when (ago.unit) {
        AgoUnit.NOW -> stringResource(R.string.fuel_updated_now)
        AgoUnit.MINUTES -> stringResource(R.string.fuel_updated_min, ago.value)
        AgoUnit.HOURS -> stringResource(R.string.fuel_updated_h, ago.value)
        AgoUnit.DAYS -> stringResource(R.string.fuel_updated_d, ago.value)
        AgoUnit.UNKNOWN -> stringResource(R.string.fuel_updated_unknown)
    }
    return stringResource(R.string.fuel_source_with_date, stringResource(R.string.fuel_source), when_)
}

@Composable
private fun noticeText(n: StopResult): String? = when (n) {
    StopResult.DUPLICATE -> stringResource(R.string.fuel_stop_duplicate)
    StopResult.SAME_AS_DESTINATION -> stringResource(R.string.fuel_stop_is_destination)
    StopResult.LIMIT -> stringResource(R.string.fuel_stop_limit, RoutePreviewController.MAX_STOPS)
    StopResult.NO_ROUTE -> stringResource(R.string.fuel_stop_no_route)
    StopResult.ADDED -> null
}

/**
 * Petrol-station card: brand, address, municipality, hours, the price of EVERY downloaded fuel (the one drawn on
 * the map first and in bold), the source attribution and the Go / Add stop / Save buttons. "Add stop" only appears
 * while a route is active. Every touch target is at least 48 dp.
 */
@Composable
fun FuelStationCard(
    state: FuelCardState,
    mapFuelId: String?,
    fuelName: (String) -> String,
    updatedMillis: Long?,
    nowMillis: Long,
    routeActive: Boolean,
    onGo: (FuelStation) -> Unit,
    onAddStop: (FuelStation) -> Unit,
    onSave: (FuelStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.station ?: return
    val locale = LocalConfiguration.current.locales[0]
    val button = Modifier.heightIn(min = TOUCH)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("fuel_card")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    s.brand.ifBlank { stringResource(R.string.fuel_category) },
                    style = Mapas.typography.title.copy(color = Mapas.colors.label),
                    modifier = Modifier.testTag("fuel_brand"),
                )
                val where = listOf(s.address, s.municipality).filter { it.isNotBlank() }.joinToString(", ")
                if (where.isNotEmpty()) {
                    BasicText(where, style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("fuel_address"))
                }
                s.schedule?.takeIf { it.isNotBlank() }?.let {
                    BasicText(
                        stringResource(R.string.fuel_schedule, it),
                        style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                        modifier = Modifier.testTag("fuel_schedule"),
                    )
                }
            }
            BasicText(
                stringResource(R.string.fuel_card_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .heightIn(min = TOUCH)
                    .clickable(role = Role.Button, onClick = state::close)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("fuel_close"),
            )
        }
        Spacer(Modifier.height(8.dp))
        BasicText(stringResource(R.string.fuel_prices_title), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        val rows = s.prices.entries.sortedWith(compareBy({ it.key != mapFuelId }, { fuelName(it.key) }))
        rows.forEach { (id, price) ->
            val chosen = id == mapFuelId
            val label = if (chosen) stringResource(R.string.fuel_price_on_map, fuelName(id)) else fuelName(id)
            val weight = if (chosen) FontWeight.Bold else FontWeight.Normal
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("fuel_price_$id")) {
                BasicText(label, style = Mapas.typography.body.copy(color = Mapas.colors.label, fontWeight = weight), modifier = Modifier.weight(1f))
                BasicText(
                    FuelMapLayer.price(price, locale),
                    style = Mapas.typography.body.copy(color = Mapas.colors.label, fontWeight = weight),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            fuelAttributionText(updatedMillis, nowMillis),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("fuel_attribution"),
        )
        BasicText(
            stringResource(R.string.fuel_price_note),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("fuel_price_note"),
        )
        state.notice?.let { n -> noticeText(n)?.let { PanelNote(it, "fuel_notice") } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.fuel_go), { onGo(s) }, button.weight(1f), primary = true, tag = "fuel_go")
            if (routeActive) {
                PanelButton(stringResource(R.string.fuel_add_stop), { onAddStop(s) }, button.weight(1f), tag = "fuel_add_stop")
            }
            PanelButton(
                stringResource(if (state.saved) R.string.fuel_saved else R.string.fuel_save),
                { onSave(s) }, button.weight(1f), tag = "fuel_save",
            )
        }
    }
}

private val TOUCH = 48.dp
