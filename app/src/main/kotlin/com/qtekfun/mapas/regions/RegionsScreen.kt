package com.qtekfun.mapas.regions

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
import androidx.compose.runtime.Composable
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
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.ui.theme.Mapas

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
)

@Composable
fun RegionsScreen(state: RegionsUiState, actions: RegionsActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf<Pair<String, String>?>(null) } // id to display name
    val installedVersions = state.installed.associate { it.region.id to it.region.version }
    val catalog = (state.catalog as? CatalogState.Loaded)?.catalog
    val rows = remember(catalog, expanded, state.installed, state.downloads) {
        catalog?.let { RegionsModel.rows(it, expanded.toSet(), installedVersions, state.downloads) }.orEmpty()
    }
    val orphans = RegionsModel.orphans(catalog, installedVersions).mapNotNull { id -> state.installed.firstOrNull { it.region.id == id } }

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
        LazyColumn(Modifier.fillMaxSize().testTag("regions_list"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item(key = "settings") { SettingsSection(state, actions) }
            item(key = "status") { CatalogStatus(state, catalog, actions) }
            items(rows, key = { it.region.id }) { row ->
                RegionRowView(
                    row, actions,
                    onToggle = { expanded = if (row.region.id in expanded) expanded - row.region.id else expanded + row.region.id },
                    onAskDelete = { confirmDelete = row.region.id to row.region.name },
                )
            }
            if (orphans.isNotEmpty()) {
                item(key = "orphans-title") {
                    BasicText(
                        stringResource(R.string.regions_installed_other),
                        Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                        style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                    )
                }
                items(orphans, key = { "orphan-" + it.region.id }) { e ->
                    OrphanRow(e) { confirmDelete = e.region.id to e.region.id }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirmDelete?.let { (id, name) ->
        val bytes = state.installed.firstOrNull { it.region.id == id }?.bytes ?: 0L
        DeleteDialog(name, RegionsModel.formatBytes(bytes), onConfirm = { actions.onDelete(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
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
private fun RegionRowView(row: RegionRow, actions: RegionsActions, onToggle: () -> Unit, onAskDelete: () -> Unit) {
    val r = row.region
    val dl = row.download
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (row.isGroup) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 16.dp + 18.dp * row.depth, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .testTag("region_" + r.id),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isGroup) BasicText(if (row.expanded) "▾" else "▸", Modifier.width(22.dp), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
            Column(Modifier.weight(1f)) {
                BasicText(r.name, style = Mapas.typography.body.copy(color = Mapas.colors.label))
                BasicText(subtitle(row), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            }
            if (!row.isGroup) RowActions(row, actions, onAskDelete)
        }
        when (dl) {
            is DownloadState.Running -> Progress(RegionsModel.percent(dl.done, dl.total))
            is DownloadState.Paused -> Progress(RegionsModel.percent(dl.done, dl.total))
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
private fun RowActions(row: RegionRow, actions: RegionsActions, onAskDelete: () -> Unit) {
    val id = row.region.id
    Row {
        when (val d = row.download) {
            is DownloadState.Queued, is DownloadState.Running -> {
                TextButton(stringResource(R.string.region_pause), "pause_$id") { actions.onPause(id) }
                TextButton(stringResource(R.string.region_cancel), "cancel_$id") { actions.onCancel(id) }
            }
            is DownloadState.Paused -> {
                TextButton(stringResource(R.string.region_resume), "resume_$id") { actions.onDownload(row.region) }
                TextButton(stringResource(R.string.region_cancel), "cancel_$id") { actions.onCancel(id) }
            }
            is DownloadState.Failed -> {
                TextButton(stringResource(R.string.region_retry), "retry_$id") { actions.onDownload(row.region) }
                TextButton(stringResource(R.string.close), "dismiss_$id") { actions.onDismissFailure(id) }
            }
            null -> when {
                row.updateAvailable -> {
                    TextButton(stringResource(R.string.region_update), "update_$id") { actions.onDownload(row.region) }
                    TextButton(stringResource(R.string.region_delete), "delete_$id", onClick = onAskDelete)
                }
                row.installedVersion != null -> TextButton(stringResource(R.string.region_delete), "delete_$id", onClick = onAskDelete)
                row.region.isDownloadable -> TextButton(stringResource(R.string.region_download), "download_$id") { actions.onDownload(row.region) }
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
private fun Progress(percent: Int) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape).background(Mapas.colors.field).testTag("progress")) {
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

/** Entry shown in the map's bottom sheet. Lives here so the shared screen needs a single call. */
@Composable
fun MapsEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.field)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .testTag("open_maps"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.maps_entry_title), style = Mapas.typography.callout.copy(color = Mapas.colors.label))
            BasicText(stringResource(R.string.maps_entry_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        BasicText("›", style = Mapas.typography.title.copy(color = Mapas.colors.secondaryLabel))
    }
}
