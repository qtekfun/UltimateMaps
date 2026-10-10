package com.qtekfun.ultimatemaps.regions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.regions.Region
import com.qtekfun.ultimatemaps.core.regions.RegionCatalog
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** Immutable snapshot the screen draws; built from [RegionsController] and trivially constructible in tests. */
data class RegionsUiState(
    val catalog: CatalogState,
    val installed: List<InstalledEntry> = emptyList(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val storage: List<StorageInfo> = emptyList(),
    val selectedLocationId: String = RegionStorage.INTERNAL_ID,
    val offline: Boolean = false,
    val serverUrl: String = "",
    /** A deleted region may keep answering searches until the app restarts (the native core cannot unload it). */
    val restartForSearch: Boolean = false,
    /** Some installed map could not be linked for the search. */
    val linkProblem: Boolean = false,
    /** Region ids a settings restore listed as installed on the old phone (offered for download, never downloaded alone). */
    val restoredRegionIds: Set<String> = emptySet(),
    /** Public-transport cities of the catalog (the optional `transit` block); empty hides the section. */
    val transit: List<com.qtekfun.ultimatemaps.transit.TransitCityRow> = emptyList(),
)

fun RegionsController.uiState() = RegionsUiState(
    catalog = catalogState, installed = installed, downloads = downloadStates, storage = storage,
    selectedLocationId = selectedLocationId, offline = offline, serverUrl = serverUrl,
    restartForSearch = restartForSearch, linkProblem = linkProblem,
)

class RegionsActions(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSetOffline: (Boolean) -> Unit = {},
    /** Returns false if the address is not a valid https URL. */
    val onSaveServer: (String) -> Boolean = { true },
    val onSelectLocation: (String) -> Unit = {},
    val onDownload: (Region) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onDismissFailure: (String) -> Unit = {},
    /** The owner dismissed the offer to download the maps listed by a settings restore. */
    val onDismissRestored: () -> Unit = {},
    val onTransitDownload: (String) -> Unit = {},
    val onTransitDelete: (String) -> Unit = {},
)

@Composable
fun RegionsScreen(state: RegionsUiState, actions: RegionsActions, modifier: Modifier = Modifier, nameLanguage: String = "en") {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf<Pair<String, String>?>(null) } // id to display name
    val installedVersions = state.installed.associate { it.region.id to it.region.version }
    val catalog = (state.catalog as? CatalogState.Loaded)?.catalog
    var query by rememberSaveable { mutableStateOf("") }
    val searching = RegionSearch.normalize(query).isNotEmpty()
    // Normalized keys are computed once per catalog; typing only scans them.
    val index = remember(catalog, nameLanguage) { catalog?.let { RegionSearch.Index(it, nameLanguage) } }
    val rows = remember(catalog, index, expanded, state.installed, state.downloads, query) {
        when {
            catalog == null || index == null -> emptyList()
            searching -> RegionsModel.searchRows(index, query, installedVersions, state.downloads, expanded.toSet())
            else -> RegionsModel.rows(catalog, expanded.toSet(), installedVersions, state.downloads)
        }
    }
    val allOrphans = RegionsModel.orphans(catalog, installedVersions).mapNotNull { id -> state.installed.firstOrNull { it.region.id == id } }
    val orphans = if (searching) {
        val tokens = RegionSearch.normalize(query).split(' ')
        allOrphans.filter { e -> RegionSearch.normalize(e.region.id).let { k -> tokens.all { it in k } } }
    } else allOrphans
    // Installed maps come first (outside the search): one row per installed region the catalog lists.
    val installedRows = remember(catalog, state.installed, state.downloads, nameLanguage) {
        if (catalog == null) emptyList() else RegionsModel.installedRows(catalog, installedVersions, state.downloads).map { r ->
            val names = (RegionsModel.ancestors(catalog, r.region.id) + r.region.id).mapNotNull { catalog[it]?.displayName(nameLanguage) }
            r.copy(path = names.joinToString(" › "))
        }
    }
    var tab by rememberSaveable { mutableStateOf(RegionsTab.MAPS) }
    val listState = rememberLazyListState()
    // A new tab starts at the top.
    var shownTab by rememberSaveable { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != shownTab) {
            shownTab = tab
            listState.scrollToItem(0)
        }
    }
    // A new query starts at the top; a restored one (same text as before the recreation) keeps its scroll.
    var shownQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != shownQuery) {
            shownQuery = query
            listState.scrollToItem(0)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Mapas.colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("regions_screen"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.regions_title), Modifier.weight(1f), style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label))
            TextButton(stringResource(R.string.close), "regions_close", onClick = actions.onClose)
        }
        RegionsTabs(tab) { tab = it }
        // The search field filters the map regions only, so it lives on the Maps tab.
        if (tab == RegionsTab.MAPS && catalog != null) SearchField(query, { query = it })
        LazyColumn(Modifier.fillMaxSize().testTag("regions_list"), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            when (tab) {
                RegionsTab.SETTINGS -> item(key = "settings") { SettingsSection(state, actions) }
                RegionsTab.TRANSIT -> item(key = "transit") {
                    if (state.transit.isEmpty()) {
                        BasicText(
                            stringResource(R.string.transit_maps_none),
                            Modifier.padding(16.dp).testTag("transit_maps_empty"),
                            style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel),
                        )
                    } else {
                        com.qtekfun.ultimatemaps.transit.TransitMapsSection(state.transit, state.offline, actions.onTransitDownload, actions.onTransitDelete, showTitle = false)
                    }
                }
                RegionsTab.MAPS -> mapsItems(
                    state, actions, catalog, nameLanguage, query, searching, rows, installedRows, orphans,
                    expanded, { expanded = it }, { confirmDelete = it },
                )
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirmDelete?.let { (id, name) ->
        val bytes = state.installed.firstOrNull { it.region.id == id }?.bytes ?: 0L
        DeleteDialog(name, RegionsModel.formatBytes(bytes), onConfirm = { actions.onDelete(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

/** The Maps tab: restore offer and catalog status, then installed maps first, then every other map. */
private fun LazyListScope.mapsItems(
    state: RegionsUiState,
    actions: RegionsActions,
    catalog: RegionCatalog?,
    nameLanguage: String,
    query: String,
    searching: Boolean,
    rows: List<RegionRow>,
    installedRows: List<RegionRow>,
    orphans: List<InstalledEntry>,
    expanded: List<String>,
    setExpanded: (List<String>) -> Unit,
    askDelete: (Pair<String, String>) -> Unit,
) {
    if (!searching) item(key = "restored") { RestoredRegionsOffer(state, catalog, actions) }
    item(key = "status") { CatalogStatus(state, catalog, actions) }
    if (searching && rows.isEmpty() && orphans.isEmpty()) item(key = "search-empty") {
        BasicText(
            stringResource(R.string.regions_search_empty, query.trim()),
            Modifier.padding(16.dp).testTag("regions_search_empty"),
            style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel),
        )
    }
    val installedFirst = !searching && (installedRows.isNotEmpty() || orphans.isNotEmpty())
    if (installedFirst) {
        item(key = "installed-title") { SectionTitle(stringResource(R.string.regions_installed_title), "regions_installed_title") }
        items(installedRows, key = { "installed-" + it.region.id }) { row ->
            RegionRowView(
                row, actions, nameLanguage, onToggle = {}, tagPrefix = "installed_",
                onAskDelete = { askDelete(row.region.id to row.region.displayName(nameLanguage)) },
            )
        }
    }
    if (orphans.isNotEmpty() && !searching) orphanItems(orphans, askDelete)
    if (installedFirst && rows.isNotEmpty()) item(key = "all-title") { SectionTitle(stringResource(R.string.regions_all_title), "regions_all_title") }
    items(rows, key = { it.region.id }) { row ->
        RegionRowView(
            row, actions, nameLanguage,
            onToggle = {
                // Opening or closing a row never touches the search text.
                setExpanded(if (row.region.id in expanded) expanded - row.region.id else expanded + row.region.id)
            },
            onAskDelete = { askDelete(row.region.id to row.region.displayName(nameLanguage)) },
        )
    }
    if (orphans.isNotEmpty() && searching) orphanItems(orphans, askDelete)
}

private fun LazyListScope.orphanItems(orphans: List<InstalledEntry>, askDelete: (Pair<String, String>) -> Unit) {
    item(key = "orphans-title") {
        BasicText(
            stringResource(R.string.regions_installed_other),
            Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        )
    }
    items(orphans, key = { "orphan-" + it.region.id }) { e ->
        OrphanRow(e) { askDelete(e.region.id to e.region.id) }
    }
}

@Composable
private fun SectionTitle(text: String, tag: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp).testTag(tag), style = Mapas.typography.title.copy(color = Mapas.colors.label))
}

/** The sections of the Maps screen; each has one purpose. */
enum class RegionsTab(val tag: String) { MAPS("regions_tab_maps"), TRANSIT("regions_tab_transit"), SETTINGS("regions_tab_settings") }

/**
 * Segmented control with the screen's sections. Equal-width segments whose label may wrap to a second line (never cut or
 * ellipsized) at large font sizes; at least 48 dp tall, more in glove mode.
 */
@Composable
private fun RegionsTabs(selected: RegionsTab, onSelect: (RegionsTab) -> Unit) {
    val colors = Mapas.colors
    val height = maxOf(48.dp, Mapas.dimens.touchTarget)
    val group = stringResource(R.string.regions_tabs_group)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(Mapas.shapes.control)
            .background(colors.field)
            .padding(2.dp)
            .selectableGroup()
            .semantics { contentDescription = group }
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RegionsTab.entries.forEach { t ->
            val label = stringResource(
                when (t) {
                    RegionsTab.MAPS -> R.string.regions_tab_maps
                    RegionsTab.TRANSIT -> R.string.regions_tab_transit
                    RegionsTab.SETTINGS -> R.string.regions_tab_settings
                },
            )
            val on = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = height)
                    .clip(Mapas.shapes.field)
                    .background(if (on) colors.segmentThumb else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onSelect(t) }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .testTag(t.tag),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = Mapas.typography.callout.copy(color = if (on) colors.label else colors.secondaryLabel, textAlign = TextAlign.Center),
                    modifier = Modifier.testTag(t.tag + "_label"),
                )
            }
        }
    }
}

/**
 * After a settings restore: the maps that were installed on the old phone and are not here. The owner chooses; nothing
 * downloads by itself, and with offline mode on the button is not offered (the downloads would be refused anyway).
 */
@Composable
private fun RestoredRegionsOffer(state: RegionsUiState, catalog: RegionCatalog?, actions: RegionsActions) {
    val missing = state.restoredRegionIds - state.installed.map { it.region.id }.toSet() - state.downloads.keys
    if (missing.isEmpty()) return
    val available = catalog?.let { c -> missing.sorted().mapNotNull { id -> c[id]?.takeIf { it.isDownloadable } } }.orEmpty()
    val unavailable = if (catalog == null) 0 else missing.size - available.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card("restored_regions_card") {
            BasicText(stringResource(R.string.regions_restored_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            BasicText(
                stringResource(R.string.regions_restored_body, missing.size),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("restored_regions_body"),
            )
            if (unavailable > 0) {
                BasicText(
                    stringResource(R.string.regions_restored_unavailable, unavailable),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    modifier = Modifier.testTag("restored_regions_unavailable"),
                )
            }
            if (state.offline) {
                BasicText(
                    stringResource(R.string.regions_restored_offline),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                    modifier = Modifier.testTag("restored_regions_offline"),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!state.offline && available.isNotEmpty()) {
                    TextButton(
                        stringResource(R.string.regions_restored_download, RegionsModel.formatBytes(available.sumOf { it.totalBytes })),
                        "restored_regions_download",
                    ) { available.forEach(actions.onDownload) }
                }
                TextButton(stringResource(R.string.regions_restored_dismiss), "restored_regions_dismiss", onClick = actions.onDismissRestored)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val clear = stringResource(R.string.regions_search_clear)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(Mapas.shapes.field)
            .background(Mapas.colors.field)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (query.isEmpty()) BasicText(stringResource(R.string.regions_search_hint), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
                cursorBrush = SolidColor(Mapas.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("regions_search"),
            )
        }
        if (query.isNotEmpty()) {
            BasicText(
                "✕",
                Modifier
                    .clickable(role = Role.Button, onClickLabel = clear) { onChange("") }
                    .semantics { contentDescription = clear }
                    .size(48.dp)
                    .wrapContentSize(Alignment.Center)
                    .testTag("regions_search_clear"),
                style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel),
            )
        }
    }
}

@Composable
private fun SettingsSection(state: RegionsUiState, actions: RegionsActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Offline mode
        Card("offline_card") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(R.string.offline_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                    BasicText(stringResource(R.string.offline_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
                }
                Spacer(Modifier.width(12.dp))
                SwitchControl(state.offline, stringResource(R.string.offline_title), actions.onSetOffline)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Server
        Card("server_card") {
            var text by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
            var invalid by remember { mutableStateOf(false) }
            BasicText(stringResource(R.string.server_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().clip(Mapas.shapes.field).background(Mapas.colors.field).padding(10.dp)) {
                if (text.isEmpty()) BasicText(stringResource(R.string.server_hint), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
                BasicTextField(
                    value = text, onValueChange = { text = it; invalid = false }, singleLine = true,
                    textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
                    cursorBrush = SolidColor(Mapas.colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("server_field"),
                )
            }
            if (invalid) BasicText(stringResource(R.string.server_invalid), Modifier.testTag("server_invalid"), style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.serverUrl != RegionsController.DEFAULT_CATALOG_URL) {
                    TextButton(stringResource(R.string.server_use_default), "server_default") {
                        text = RegionsController.DEFAULT_CATALOG_URL
                        invalid = !actions.onSaveServer(text)
                        if (!invalid) actions.onRefresh()
                    }
                }
                TextButton(stringResource(R.string.server_save), "server_save") {
                    invalid = !actions.onSaveServer(text)
                    if (!invalid) actions.onRefresh()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Storage
        Card("storage_card") {
            BasicText(stringResource(R.string.storage_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            state.storage.forEach { s ->
                val selected = s.location.id == state.selectedLocationId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { actions.onSelectLocation(s.location.id) }
                        .padding(vertical = 8.dp)
                        .testTag("storage_" + s.location.id),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(if (selected) "◉" else "○", style = Mapas.typography.body.copy(color = Mapas.colors.accent))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        BasicText(s.location.label, style = Mapas.typography.body.copy(color = Mapas.colors.label))
                        BasicText(
                            stringResource(R.string.storage_free, RegionsModel.formatBytes(s.freeBytes), RegionsModel.formatBytes(s.totalBytes)),
                            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                        )
                    }
                }
            }
            if (state.storage.firstOrNull { it.location.id == state.selectedLocationId }?.location?.removable == true) {
                BasicText(stringResource(R.string.storage_card_warning), style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CatalogStatus(state: RegionsUiState, catalog: RegionCatalog?, actions: RegionsActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (state.restartForSearch) Card("status_restart") {
            BasicText(stringResource(R.string.search_restart_needed), style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
        }
        if (state.linkProblem) Card("status_link_problem") {
            BasicText(stringResource(R.string.search_link_problem), style = Mapas.typography.callout.copy(color = Mapas.colors.warning))
        }
        when (val c = state.catalog) {
            CatalogState.NoServer -> Card("status_no_server") {
                BasicText(stringResource(R.string.server_none_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                BasicText(stringResource(R.string.server_none_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            }
            CatalogState.Loading -> Card("status_loading") {
                BasicText(stringResource(R.string.catalog_loading), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            }
            is CatalogState.Failed -> Card("status_error") {
                BasicText(catalogErrorText(c.error), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.region_retry), "status_retry", onClick = actions.onRefresh)
                }
            }
            is CatalogState.Loaded -> {
                if (c.refreshError != null) Card("status_stale") {
                    BasicText(stringResource(R.string.catalog_stale), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
                    BasicText(catalogErrorText(c.refreshError), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(stringResource(R.string.region_retry), "status_retry", onClick = actions.onRefresh)
                    }
                }
                if (catalog != null && catalog.regions.isEmpty()) Card("status_empty") {
                    BasicText(stringResource(R.string.catalog_empty), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun catalogErrorText(e: CatalogError) = stringResource(
    when (e) {
        CatalogError.OFFLINE_MODE -> R.string.catalog_error_offline
        CatalogError.NOT_ALLOWED -> R.string.catalog_error_denied
        CatalogError.NETWORK -> R.string.catalog_error_network
        CatalogError.INVALID -> R.string.catalog_error_invalid
    },
)

@Composable
private fun RegionRowView(row: RegionRow, actions: RegionsActions, nameLanguage: String, onToggle: () -> Unit, onAskDelete: () -> Unit, tagPrefix: String = "") {
    val r = row.region
    val dl = row.download
    val shown = r.displayName(nameLanguage)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (row.isGroup) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 16.dp + 18.dp * row.depth, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tagPrefix + "region_" + r.id),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isGroup) BasicText(if (row.expanded) "▾" else "▸", Modifier.width(22.dp), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            Column(Modifier.weight(1f)) {
                BasicText(if (row.path != null) RegionSearch.segments(shown).last() else shown, style = Mapas.typography.body.copy(color = Mapas.colors.label))
                if (row.path != null && row.path != RegionSearch.segments(shown).last()) BasicText(row.path, Modifier.testTag(tagPrefix + "path_" + r.id), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
                BasicText(subtitle(row), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            }
            if (!row.isGroup) RowActions(row, actions, onAskDelete, tagPrefix)
        }
        when (dl) {
            is DownloadState.Running -> Progress(RegionsModel.percent(dl.done, dl.total), tagPrefix)
            is DownloadState.Paused -> Progress(RegionsModel.percent(dl.done, dl.total), tagPrefix)
            else -> Unit
        }
    }
}

@Composable
private fun subtitle(row: RegionRow): String {
    val r = row.region
    val size = RegionsModel.formatBytes(row.totalBytes)
    return when (val d = row.download) {
        is DownloadState.Queued -> stringResource(R.string.region_queued)
        is DownloadState.Running -> stringResource(R.string.region_progress, RegionsModel.percent(d.done, d.total), RegionsModel.formatBytes(d.total))
        is DownloadState.Paused -> stringResource(R.string.region_paused, RegionsModel.percent(d.done, d.total))
        is DownloadState.Failed -> failureText(d)
        null -> when {
            row.isGroup -> if (row.downloadableCount == 0) stringResource(R.string.region_unavailable)
            else stringResource(R.string.region_group_summary, row.installedCount, row.downloadableCount, size)
            row.updateAvailable -> stringResource(R.string.region_update_available, size)
            row.installedVersion != null -> stringResource(R.string.region_installed_size, size)
            !r.isDownloadable -> stringResource(R.string.region_unavailable)
            else -> size
        }
    }
}

@Composable
private fun failureText(d: DownloadState.Failed) = when (d.reason) {
    FailureReason.OFFLINE_MODE -> stringResource(R.string.fail_offline)
    FailureReason.NOT_ALLOWED -> stringResource(R.string.fail_not_allowed)
    FailureReason.NO_SPACE -> stringResource(R.string.fail_no_space, RegionsModel.formatBytes(d.neededBytes), RegionsModel.formatBytes(d.freeBytes))
    FailureReason.NETWORK -> stringResource(R.string.fail_network)
    FailureReason.CORRUPT -> stringResource(R.string.fail_corrupt)
}

@Composable
private fun RowActions(row: RegionRow, actions: RegionsActions, onAskDelete: () -> Unit, tagPrefix: String = "") {
    val id = row.region.id
    fun tag(base: String) = "$tagPrefix${base}_$id"
    Row {
        when (val d = row.download) {
            is DownloadState.Queued, is DownloadState.Running -> {
                TextButton(stringResource(R.string.region_pause), tag("pause")) { actions.onPause(id) }
                TextButton(stringResource(R.string.region_cancel), tag("cancel")) { actions.onCancel(id) }
            }
            is DownloadState.Paused -> {
                TextButton(stringResource(R.string.region_resume), tag("resume")) { actions.onDownload(row.region) }
                TextButton(stringResource(R.string.region_cancel), tag("cancel")) { actions.onCancel(id) }
            }
            is DownloadState.Failed -> {
                TextButton(stringResource(R.string.region_retry), tag("retry")) { actions.onDownload(row.region) }
                TextButton(stringResource(R.string.close), tag("dismiss")) { actions.onDismissFailure(id) }
            }
            null -> when {
                row.updateAvailable -> {
                    TextButton(stringResource(R.string.region_update), tag("update")) { actions.onDownload(row.region) }
                    TextButton(stringResource(R.string.region_delete), tag("delete"), onClick = onAskDelete)
                }
                row.installedVersion != null -> TextButton(stringResource(R.string.region_delete), tag("delete"), onClick = onAskDelete)
                row.region.isDownloadable -> TextButton(stringResource(R.string.region_download), tag("download")) { actions.onDownload(row.region) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun OrphanRow(e: InstalledEntry, onAskDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp).testTag("orphan_" + e.region.id), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(e.region.id, style = Mapas.typography.body.copy(color = Mapas.colors.label))
            BasicText(stringResource(R.string.region_installed_size, RegionsModel.formatBytes(e.bytes)), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        TextButton(stringResource(R.string.region_delete), "delete_" + e.region.id, onClick = onAskDelete)
    }
}

@Composable
private fun Progress(percent: Int, tagPrefix: String = "") {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape).background(Mapas.colors.field).testTag(tagPrefix + "progress")) {
        Box(Modifier.fillMaxWidth(percent / 100f).height(4.dp).background(Mapas.colors.accent))
    }
}

@Composable
private fun Card(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Mapas.shapes.control).background(Mapas.colors.field).padding(12.dp).testTag(tag)) { content() }
}

@Composable
private fun TextButton(label: String, tag: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = Mapas.typography.callout.copy(color = Mapas.colors.accent),
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag(tag),
    )
}

@Composable
private fun SwitchControl(checked: Boolean, description: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (checked) Mapas.colors.accent else Mapas.colors.separator)
            .padding(2.dp)
            .testTag("offline_switch"),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(27.dp).clip(CircleShape).background(Mapas.colors.onAccent))
    }
}

@Composable
private fun DeleteDialog(name: String, size: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(Mapas.shapes.control).background(Mapas.colors.sheet).padding(20.dp).testTag("delete_dialog")) {
            BasicText(stringResource(R.string.delete_title, name), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            BasicText(stringResource(R.string.delete_body, size), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(stringResource(R.string.region_cancel), "delete_no", onClick = onDismiss)
                TextButton(stringResource(R.string.region_delete), "delete_yes", onClick = onConfirm)
            }
        }
    }
}

