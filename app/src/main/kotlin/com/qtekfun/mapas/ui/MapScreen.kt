package com.qtekfun.mapas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.mapas.R
import com.qtekfun.mapas.ui.sheet.BottomSheet
import com.qtekfun.mapas.ui.sheet.SheetDetent
import com.qtekfun.mapas.ui.theme.Mapas

/** Message shown in the sheet after an action that the app cannot (yet) complete. */
sealed interface Notice {
    data object ShortLink : Notice
    data object Unrecognized : Notice
    data class Search(val query: String) : Notice
    data class Place(val label: String?) : Notice
    data object LocationDenied : Notice
    data object LocationUnavailable : Notice
}

/** Observable UI state. Written from the main thread only. */
class MapScreenState {
    var detent by mutableStateOf(SheetDetent.MEDIUM)
    var bearing by mutableFloatStateOf(0f)
    var hasTiles by mutableStateOf(true)
    var locating by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    var aboutVisible by mutableStateOf(false)
}

@Composable
fun MapScreen(
    state: MapScreenState,
    onLocate: () -> Unit,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier,
    sheetPanel: (@Composable () -> Unit)? = null,
    mapContent: @Composable () -> Unit,
) {
    val statusTop = WindowInsets.statusBars
    val margin = Mapas.dimens.screenMargin
    val collapsedLabel = stringResource(R.string.sheet_state_collapsed)
    val mediumLabel = stringResource(R.string.sheet_state_medium)
    val fullLabel = stringResource(R.string.sheet_state_full)
    Box(modifier.fillMaxSize().background(Mapas.colors.separator)) {
        mapContent()

        val topPadding = with(LocalDensity.current) { statusTop.getTop(this).toDp() }
        AttributionLabel(
            text = stringResource(R.string.attribution_osm),
            onClick = { state.aboutVisible = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = margin, top = 8.dp),
        )
        MapButtons(
            bearingDegrees = state.bearing,
            locating = state.locating,
            locateDescription = stringResource(R.string.map_locate),
            compassDescription = stringResource(R.string.map_compass),
            onLocate = onLocate,
            onResetNorth = onResetNorth,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = margin, top = 8.dp),
        )

        BottomSheet(
            detent = state.detent,
            onDetentChange = { state.detent = it },
            sheetDescription = stringResource(R.string.sheet_description),
            handleDescription = stringResource(R.string.sheet_expand),
            detentLabel = { d ->
                when (d) {
                    SheetDetent.COLLAPSED -> collapsedLabel
                    SheetDetent.MEDIUM -> mediumLabel
                    SheetDetent.FULL -> fullLabel
                }
            },
            topInset = topPadding + 64.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            SheetContent(state, sheetPanel)
        }

        if (state.aboutVisible) AboutDialog(onDismiss = { state.aboutVisible = false })
    }
}

@Composable
private fun SheetContent(state: MapScreenState, panel: (@Composable () -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        if (panel != null) {
            // Search, place card and lists live in their own panel (see the search and places packages).
            state.notice?.let { n ->
                val (title, body) = noticeText(n)
                InfoCard(title, body, "card_notice")
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f).imePadding()) { panel() }
            return@Column
        }
        BasicText(
            text = stringResource(R.string.app_name),
            style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Mapas.shapes.field)
                .background(Mapas.colors.field)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("search_field"),
        ) {
            BasicText(
                text = stringResource(R.string.search_placeholder),
                style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!state.hasTiles) InfoCard(stringResource(R.string.no_maps_title), stringResource(R.string.no_maps_body), "card_no_maps")
        state.notice?.let { n ->
            Spacer(Modifier.height(8.dp))
            val (title, body) = noticeText(n)
            InfoCard(title, body, "card_notice")
        }
    }
}

@Composable
private fun noticeText(n: Notice): Pair<String, String> = when (n) {
    Notice.ShortLink -> stringResource(R.string.notice_link_title) to stringResource(R.string.link_short_needs_resolve)
    Notice.Unrecognized -> stringResource(R.string.notice_link_title) to stringResource(R.string.link_not_recognized)
    is Notice.Search -> stringResource(R.string.notice_search_title, n.query) to stringResource(R.string.notice_search_body)
    is Notice.Place -> (n.label ?: stringResource(R.string.notice_place_title)) to stringResource(R.string.notice_place_body)
    Notice.LocationDenied -> stringResource(R.string.notice_location_title) to stringResource(R.string.location_permission_denied)
    Notice.LocationUnavailable -> stringResource(R.string.notice_location_title) to stringResource(R.string.location_unavailable)
}

@Composable
private fun InfoCard(title: String, body: String, tag: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.field)
            .padding(12.dp)
            .testTag(tag),
    ) {
        BasicText(title, style = Mapas.typography.callout.copy(color = Mapas.colors.label))
        Spacer(Modifier.height(2.dp))
        BasicText(body, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(Mapas.shapes.control)
                .background(Mapas.colors.sheet)
                .padding(20.dp)
                .testTag("about_dialog"),
        ) {
            BasicText(stringResource(R.string.about_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            BasicText(stringResource(R.string.about_osm_body), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(16.dp))
            BasicText(
                stringResource(R.string.close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}
