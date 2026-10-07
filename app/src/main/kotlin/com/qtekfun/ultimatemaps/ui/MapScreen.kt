package com.qtekfun.ultimatemaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.mutableDoubleStateOf
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
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.ui.sheet.BottomSheet
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** Message shown in the sheet after an action that the app cannot (yet) complete. */
sealed interface Notice {
    data object ShortLink : Notice
    data object Unrecognized : Notice
    data class Search(val query: String) : Notice
    data object LocationDenied : Notice
    data object LocationUnavailable : Notice
}

/** Observable UI state. Written from the main thread only. */
class MapScreenState {
    var detent by mutableStateOf(SheetDetent.MEDIUM)
    var bearing by mutableFloatStateOf(0f)

    /** Camera tilt in degrees, latitude of the view centre and zoom: the compass shows when tilted, the scale bar uses the other two. */
    var tilt by mutableFloatStateOf(0f)
    var centerLatitude by mutableDoubleStateOf(0.0)
    var zoom by mutableDoubleStateOf(0.0)

    /** The scale bar waits for the first camera report. */
    var cameraKnown by mutableStateOf(false)
    var hasTiles by mutableStateOf(true)
    var locating by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    var aboutVisible by mutableStateOf(false)

    /** Takes the camera values the controls depend on (call from the engine's camera-idle callback). */
    fun onCamera(camera: CameraState) {
        bearing = camera.bearing.toFloat()
        tilt = camera.tilt.toFloat()
        centerLatitude = camera.center.lat
        zoom = camera.zoom
        cameraKnown = true
    }

    /** Opens the "Maps" screen (regions); set by the activity. */
    var onOpenMaps: () -> Unit = {}

    /** Opens Settings (the discreet gear on the map); set by the activity. */
    var onOpenSettings: () -> Unit = {}
}

@Composable
fun MapScreen(
    state: MapScreenState,
    onLocate: () -> Unit,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier,
    sheetPanel: (@Composable () -> Unit)? = null,
    /** While true (navigating) the search sheet and the map buttons are hidden; the attribution moves into the navigation panel. */
    navigating: Boolean = false,
    /**
     * While navigating, show the sheet anyway (above the navigation screen) with [sheetPanel]: used for the petrol-station card and the camera or incident card
     * opened by tapping a marker on the map, which would otherwise stay hidden behind the navigation screen.
     */
    navSheet: Boolean = false,
    /** Drawn over the map and under nothing else: the navigation screen. */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
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
        // While navigating, the attribution moves into the navigation panel (the banner would cover it here).
        if (!navigating) AttributionLabel(
            text = stringResource(R.string.attribution_osm),
            onClick = { state.aboutVisible = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = margin, top = 8.dp),
        )
        if (!navigating && state.cameraKnown) ScaleBar(
            latitude = state.centerLatitude,
            zoom = state.zoom,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = margin, top = 44.dp),
        )
        if (!navigating) MapButtons(
            bearingDegrees = state.bearing,
            locating = state.locating,
            locateDescription = stringResource(R.string.map_locate),
            compassDescription = stringResource(R.string.map_compass),
            onLocate = onLocate,
            onResetNorth = onResetNorth,
            tiltDegrees = state.tilt,
            settingsDescription = stringResource(R.string.settings_open),
            onSettings = state.onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = margin, top = 8.dp),
        )

        val sheet: @Composable () -> Unit = {
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
                expandActionLabel = stringResource(R.string.sheet_action_expand),
                collapseActionLabel = stringResource(R.string.sheet_action_collapse),
                modifier = Modifier.fillMaxSize(),
            ) {
                SheetContent(state, sheetPanel)
            }
        }
        if (!navigating) sheet()

        overlay?.invoke(this)
        if (navigating && navSheet) sheet()

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
        MapsEntryRow(state)
        state.notice?.let { n ->
            Spacer(Modifier.height(8.dp))
            val (title, body) = noticeText(n)
            InfoCard(title, body, "card_notice")
        }
    }
}

@Composable
private fun MapsEntryRow(state: MapScreenState) {
    Spacer(Modifier.height(8.dp))
    com.qtekfun.ultimatemaps.regions.MapsEntry(onClick = state.onOpenMaps)
}

@Composable
private fun noticeText(n: Notice): Pair<String, String> = when (n) {
    Notice.ShortLink -> stringResource(R.string.notice_link_title) to stringResource(R.string.link_short_needs_resolve)
    Notice.Unrecognized -> stringResource(R.string.notice_link_title) to stringResource(R.string.link_not_recognized)
    is Notice.Search -> stringResource(R.string.notice_search_title, n.query) to stringResource(R.string.notice_search_body)
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
            // Public-transport data licences ask for their attribution wherever the data is used (empty without transit data).
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.qtekfun.ultimatemaps.MapasApp
            com.qtekfun.ultimatemaps.transit.TransitAboutBlock(app?.transit?.attributions.orEmpty())
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
