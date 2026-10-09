package com.qtekfun.ultimatemaps.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** Saved places: the lists overview, or the places of one list with filter, sort, import and export. */
@Composable
fun ListsPanel(
    controller: PlacesController,
    onShowPlace: (PlaceInfo) -> Unit,
    onImport: () -> Unit,
    onExport: (GeoFormat) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    tracks: TrackLayerController? = null,
) {
    val state = controller.state
    val open = state.openList
    Column(modifier.testTag("lists_panel")) {
        if (open == null) {
            Overview(controller, onImport, onExport, onFocus, tracks)
        } else {
            val comma = java.text.DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator == ','
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(stringResource(R.string.lists_back), { controller.openList(null) }, tag = "lists_back")
                BasicText(
                    listTitle(open.name, open.icon),
                    style = Mapas.typography.title.copy(color = Mapas.colors.label),
                    modifier = Modifier.weight(1f).testTag("list_title"),
                    maxLines = 1,
                )
                if (!state.editingList) {
                    PanelButton(stringResource(R.string.list_customize), controller::startEditingList, tag = "list_customize")
                }
            }
            if (!open.notes.isNullOrBlank() && !state.editingList) {
                BasicText(
                    open.notes!!,
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    modifier = Modifier.testTag("list_notes"),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.editingList) {
                ListStyleEditor(open, controller::saveListStyle, controller::cancelEditingList, onFocus)
                return@Column
            }
            PanelTextField(
                state.query, controller::setQuery, stringResource(R.string.list_filter_hint),
                onFocus = onFocus, imeAction = ImeAction.Done, tag = "list_filter",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(
                    stringResource(R.string.list_sort_distance), { controller.setByDistance(true) },
                    primary = state.byDistance, tag = "sort_distance",
                )
                PanelButton(
                    stringResource(R.string.list_sort_name), { controller.setByDistance(false) },
                    primary = !state.byDistance, tag = "sort_name",
                )
            }
            if (state.rows.isEmpty()) {
                PanelNote(
                    stringResource(if (state.query.isBlank()) R.string.lists_empty else R.string.list_empty_filter),
                    "list_empty",
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(state.rows, key = { it.place.id }) { row ->
                    PanelRow(
                        title = row.place.name,
                        subtitle = row.place.notes,
                        trailing = row.distanceMeters?.let { formatDistance(it, comma) },
                        onClick = { onShowPlace(PlaceInfo(row.place.name, row.place.point, row.place.notes)) },
                        tag = "place_row",
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Actions(controller, onImport, onExport)
        }
    }
}

@Composable
private fun ColumnScope.Overview(
    controller: PlacesController, onImport: () -> Unit, onExport: (GeoFormat) -> Unit, onFocus: () -> Unit,
    tracks: TrackLayerController?,
) {
    val state = controller.state
    var newName by remember { mutableStateOf("") }
    BasicText(stringResource(R.string.lists_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
        items(state.lists, key = { it.id }) { list ->
            PanelRow(
                title = listTitle(list.name, list.icon),
                subtitle = list.notes?.takeIf { it.isNotBlank() },
                leadingColor = list.color,
                trailing = pluralStringResource(R.plurals.lists_place_count, list.placeCount, list.placeCount),
                onClick = { controller.openList(list) },
                tag = "list_row",
            )
        }
        tracksItems(tracks)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTextField(
            newName, { newName = it }, stringResource(R.string.lists_new_hint),
            modifier = Modifier.weight(1f), onFocus = onFocus, imeAction = ImeAction.Done,
            onDone = { controller.createList(newName); newName = "" }, tag = "list_new_name",
        )
        PanelButton(
            stringResource(R.string.lists_create), { controller.createList(newName); newName = "" },
            enabled = newName.isNotBlank(), tag = "list_create",
        )
    }
    Spacer(Modifier.height(8.dp))
    Actions(controller, onImport, onExport)
}

@Composable
private fun Actions(controller: PlacesController, onImport: () -> Unit, onExport: (GeoFormat) -> Unit) {
    val open = controller.state.openList
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(stringResource(R.string.list_import), onImport, Modifier.weight(1f), tag = "list_import")
        PanelButton(stringResource(R.string.list_export_gpx), { onExport(GeoFormat.GPX) }, Modifier.weight(1f), tag = "list_export_gpx")
        PanelButton(stringResource(R.string.list_export_kml), { onExport(GeoFormat.KML) }, Modifier.weight(1f), tag = "list_export_kml")
    }
    if (open != null) {
        Spacer(Modifier.height(8.dp))
        PanelButton(stringResource(R.string.list_delete), { controller.deleteList(open) }, Modifier.fillMaxWidth(), tag = "list_delete")
    }
}

/** The list name with its emoji in front, when it has one. */
internal fun listTitle(name: String, icon: String?): String = ListStyle.displayEmoji(icon)?.let { "$it $name" } ?: name
