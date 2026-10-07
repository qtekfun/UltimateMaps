package com.qtekfun.mapas.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.search.SearchResult
import com.qtekfun.mapas.places.ListsPanel
import com.qtekfun.mapas.places.GeoFormat
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.places.PanelMode
import com.qtekfun.mapas.places.PanelNote
import com.qtekfun.mapas.places.PanelRow
import com.qtekfun.mapas.places.PanelTextField
import com.qtekfun.mapas.places.PlaceCard
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.places.PlacesController
import com.qtekfun.mapas.places.PlacesMessage
import com.qtekfun.mapas.places.subtitleOf
import com.qtekfun.mapas.ui.theme.Mapas

/** Callbacks of the panel that need the activity or the map. */
class PanelActions(
    val onPickResult: (SearchResult) -> Unit,
    val onShowSaved: (PlaceInfo) -> Unit,
    val onRoute: (PlaceInfo) -> Unit,
    val onShare: (PlaceInfo) -> Unit,
    val onImport: () -> Unit,
    val onExport: (GeoFormat) -> Unit,
    val onFocusField: () -> Unit,
    val onOpenMaps: () -> Unit = {},
)

/**
 * Content of the bottom sheet: the place card when one is open, otherwise the Search / Lists tabs.
 * Fills the height it is given so the result lists scroll inside the sheet.
 */
@Composable
fun SheetPanel(
    search: SearchCoordinator,
    places: PlacesController,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    val card = places.state.card
    Column(modifier.fillMaxWidth().testTag("sheet_panel")) {
        if (card != null) {
            PlaceCard(
                info = card,
                saved = places.state.cardSavedId != null,
                onSave = places::toggleSaved,
                onRoute = { actions.onRoute(card) },
                onShare = { actions.onShare(card) },
                onClose = places::closeCard,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(
                    stringResource(R.string.tab_search), { places.showMode(PanelMode.SEARCH) },
                    Modifier.weight(1f), primary = places.state.mode == PanelMode.SEARCH, tag = "tab_search",
                )
                PanelButton(
                    stringResource(R.string.tab_lists), { places.showMode(PanelMode.LISTS) },
                    Modifier.weight(1f), primary = places.state.mode == PanelMode.LISTS, tag = "tab_lists",
                )
                PanelButton(
                    stringResource(R.string.maps_entry_title), actions.onOpenMaps,
                    Modifier.weight(1f), primary = false, tag = "open_maps",
                )
            }
            Spacer(Modifier.height(8.dp))
            if (places.state.mode == PanelMode.SEARCH) {
                SearchPane(search, actions, Modifier.weight(1f, fill = false))
            } else {
                ListsPanel(places, actions.onShowSaved, actions.onImport, actions.onExport, actions.onFocusField, Modifier.weight(1f, fill = false))
            }
        }
        places.state.message?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(
                messageText(it),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("panel_message"),
            )
        }
    }
}

@Composable
private fun messageText(m: PlacesMessage): String = when (m) {
    is PlacesMessage.Saved -> stringResource(R.string.msg_saved, m.listName)
    PlacesMessage.Removed -> stringResource(R.string.msg_removed)
    is PlacesMessage.Imported -> stringResource(
        R.string.msg_imported, m.result.placesAdded, m.result.placesDuplicate, m.result.tracksAdded,
    )
    PlacesMessage.ImportFailed -> stringResource(R.string.msg_import_failed)
    is PlacesMessage.Exported -> stringResource(R.string.msg_exported, m.places)
    PlacesMessage.ExportFailed -> stringResource(R.string.msg_export_failed)
    PlacesMessage.RouteSoon -> stringResource(R.string.msg_route_soon)
}

@Composable
private fun SearchPane(search: SearchCoordinator, actions: PanelActions, modifier: Modifier) {
    val state = search.state
    Column(modifier) {
        PanelTextField(
            value = state.query,
            onValueChange = search::onQueryChange,
            hint = stringResource(R.string.search_hint),
            onFocus = actions.onFocusField,
            tag = "search_input",
        )
        Spacer(Modifier.height(8.dp))
        when {
            state.regionsAvailable == false || state.status == SearchStatus.NO_REGIONS -> NoRegions()
            state.status == SearchStatus.PREPARING -> PanelNote(stringResource(R.string.search_preparing), "search_status")
            state.status == SearchStatus.SEARCHING && state.results.isEmpty() ->
                PanelNote(stringResource(R.string.search_searching), "search_status")
            state.status == SearchStatus.ERROR -> PanelNote(stringResource(R.string.search_error), "search_status")
            state.status == SearchStatus.DONE && state.results.isEmpty() ->
                PanelNote(stringResource(R.string.search_no_results), "search_status")
        }
        LazyColumn(Modifier.fillMaxWidth().testTag("search_results")) {
            items(state.results) { r ->
                PanelRow(r.name, subtitleOf(r.category, r.address), null, { actions.onPickResult(r) }, tag = "search_result")
            }
        }
    }
}

@Composable
private fun NoRegions() {
    Column(Modifier.fillMaxWidth().testTag("search_no_regions")) {
        BasicText(stringResource(R.string.search_no_regions_title), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
        BasicText(stringResource(R.string.search_no_regions_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
    }
}
