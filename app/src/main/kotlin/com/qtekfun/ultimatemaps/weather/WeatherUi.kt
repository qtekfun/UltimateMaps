package com.qtekfun.ultimatemaps.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.weather.AlertLevel
import com.qtekfun.ultimatemaps.core.weather.WeatherWarning
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.time.ZoneId

private val OrangeFill = Color(0xFFB45309) // white text on it: contrast above 4.5:1
private val RedFill = Color(0xFFC5221F) // white text on it: contrast above 5:1
private val YellowFill = Color(0xFFF2C200) // dark text on it

internal fun levelColor(level: AlertLevel): Color = when (level) {
    AlertLevel.YELLOW -> YellowFill
    AlertLevel.ORANGE -> OrangeFill
    AlertLevel.RED -> RedFill
}

private fun levelRes(level: AlertLevel): Int = when (level) {
    AlertLevel.YELLOW -> R.string.weather_level_yellow
    AlertLevel.ORANGE -> R.string.weather_level_orange
    AlertLevel.RED -> R.string.weather_level_red
}

/** "Orange warning: Wind until 18:00 (AEMET)"; "from 14:00" when it has not started; without a time when none is given. */
@Composable
internal fun weatherLine(w: WeatherWarning, nowMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val level = stringResource(levelRes(w.level))
    val event = w.event.ifBlank { w.headline }
    val onset = w.onsetMillis
    val expires = w.expiresMillis
    return when {
        onset != null && onset > nowMillis -> stringResource(R.string.weather_line_from, level, event, WeatherTimes.text(onset, nowMillis, zone, locale))
        expires != null -> stringResource(R.string.weather_line_until, level, event, WeatherTimes.text(expires, nowMillis, zone, locale))
        else -> stringResource(R.string.weather_line_open, level, event)
    }
}

/**
 * The chip on the map: the most serious orange or red warning at the centre of the view, as one line. A tap opens the detail
 * card. Draws nothing (and costs nothing) while the feature is off or no warning applies there.
 */
@Composable
fun WeatherChip(controller: WeatherAlertsController, center: () -> LatLon?, modifier: Modifier = Modifier) {
    val version by controller.version.collectAsState()
    val hasKey by controller.hasKey.collectAsState()
    val settings by controller.settingsStore.settings.collectAsState()
    val point = center()
    var open by remember { mutableStateOf(false) }
    if (!settings.enabled || !hasKey || point == null) return
    val now = System.currentTimeMillis()
    val warnings = remember(version, point.lat, point.lon, settings, hasKey) { controller.chipAt(point) }
    val top = warnings.firstOrNull() ?: run { open = false; return }
    val line = weatherLine(top, now)
    val description = stringResource(R.string.weather_chip_description)
    Row(
        modifier
            .testTag("weather_chip")
            .widthIn(max = 360.dp)
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(levelColor(top.level))
            .clickable(role = Role.Button, onClickLabel = description) { open = true }
            .semantics { contentDescription = "$line. $description" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            line,
            style = Mapas.typography.callout.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            modifier = Modifier.testTag("weather_chip_text"),
        )
        if (warnings.size > 1) {
            BasicText(" +${warnings.size - 1}", style = Mapas.typography.callout.copy(color = Color.White))
        }
    }
    if (open) WeatherCard(controller.detailsAt(point), now) { open = false }
}

/** The detail card: for each warning its level colour, text, area, validity and the attribution; one honesty line at the end. */
@Composable
fun WeatherCard(warnings: List<WeatherWarning>, nowMillis: Long, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(Mapas.shapes.control).background(Mapas.colors.sheet).padding(20.dp).testTag("weather_card"),
        ) {
            BasicText(stringResource(R.string.weather_card_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (warnings.isEmpty()) {
                    BasicText(stringResource(R.string.weather_card_empty), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                }
                warnings.forEach { w -> WarningBlock(w, nowMillis) }
                BasicText(
                    stringResource(R.string.weather_honesty),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    modifier = Modifier.padding(top = 8.dp).testTag("weather_card_honesty"),
                )
                BasicText(
                    stringResource(R.string.weather_attribution),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    modifier = Modifier.padding(top = 4.dp).testTag("weather_card_attribution"),
                )
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                stringResource(R.string.weather_card_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(vertical = 12.dp)
                    .testTag("weather_card_close"),
            )
        }
    }
}

@Composable
private fun WarningBlock(w: WeatherWarning, nowMillis: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("weather_card_item")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(levelColor(w.level)).testTag("weather_card_level"))
            Spacer(Modifier.size(8.dp))
            BasicText(weatherLine(w, nowMillis), style = Mapas.typography.body.copy(color = Mapas.colors.label, fontWeight = FontWeight.SemiBold))
        }
        if (w.areaDesc.isNotBlank()) {
            BasicText(stringResource(R.string.weather_card_area, w.areaDesc), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        val onset = w.onsetMillis
        val expires = w.expiresMillis
        val validity = when {
            onset != null && expires != null ->
                stringResource(R.string.weather_card_valid_range, WeatherTimes.text(onset, nowMillis, zone, locale), WeatherTimes.text(expires, nowMillis, zone, locale))
            expires != null -> stringResource(R.string.weather_card_valid_until, WeatherTimes.text(expires, nowMillis, zone, locale))
            onset != null -> stringResource(R.string.weather_card_valid_from, WeatherTimes.text(onset, nowMillis, zone, locale))
            else -> null
        }
        validity?.let {
            BasicText(stringResource(R.string.weather_card_valid, it), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("weather_card_validity"))
        }
        if (w.description.isNotBlank()) {
            BasicText(w.description, style = Mapas.typography.callout.copy(color = Mapas.colors.label), modifier = Modifier.padding(top = 4.dp))
        }
        if (w.instruction.isNotBlank()) {
            BasicText(stringResource(R.string.weather_card_instruction, w.instruction), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
        }
        w.sentMillis?.let {
            BasicText(
                stringResource(R.string.weather_card_issued, WeatherTimes.text(it, nowMillis, zone, locale)),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            )
        }
    }
}

/** On the route summary: the orange and red warnings along the route, one line each, with the honesty line and the attribution. */
@Composable
fun WeatherRouteWarning(warnings: List<WeatherWarning>) {
    if (warnings.isEmpty()) return
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("route_weather_warning")) {
        BasicText(stringResource(R.string.weather_route_title), style = Mapas.typography.callout.copy(color = Mapas.colors.warning, fontWeight = FontWeight.SemiBold))
        warnings.take(MAX_ROUTE_LINES).forEach { w ->
            BasicText(weatherLine(w, now), style = Mapas.typography.callout.copy(color = Mapas.colors.label), modifier = Modifier.testTag("route_weather_line"))
        }
        BasicText(
            stringResource(R.string.weather_honesty),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.testTag("route_weather_notice"),
        )
        BasicText(stringResource(R.string.weather_attribution), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    }
}

private const val MAX_ROUTE_LINES = 4
