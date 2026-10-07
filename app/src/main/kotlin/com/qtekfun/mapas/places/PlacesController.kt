package com.qtekfun.mapas.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.data.ImportResult
import com.qtekfun.mapas.core.data.PlaceList
import com.qtekfun.mapas.core.geo.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class PanelMode { SEARCH, LISTS }

/** One-line feedback shown in the panel; mapped to strings by the UI. */
sealed interface PlacesMessage {
    data class Saved(val listName: String) : PlacesMessage
    data object Removed : PlacesMessage
    data class Imported(val result: ImportResult) : PlacesMessage
    data class TakeoutImported(val summary: com.qtekfun.mapas.core.data.TakeoutSummary) : PlacesMessage
    data object ImportFailed : PlacesMessage
    data class Exported(val places: Int) : PlacesMessage
    data object ExportFailed : PlacesMessage
}

/** Observable state of the panel (search/lists tabs, place card, lists). Written from the main thread only. */
class PlacesState {
    var mode by mutableStateOf(PanelMode.SEARCH)
    var card by mutableStateOf<PlaceInfo?>(null)

    /** Id of the saved place that matches [card], or null when it is not saved. */
    var cardSavedId by mutableStateOf<Long?>(null)
    var lists by mutableStateOf<List<PlaceList>>(emptyList())

    /** The list being viewed; null = the overview of all lists. */
    var openList by mutableStateOf<PlaceList?>(null)
    var rows by mutableStateOf<List<PlaceRow>>(emptyList())
    var query by mutableStateOf("")
    var byDistance by mutableStateOf(true)
    var message by mutableStateOf<PlacesMessage?>(null)

    /** True while the open list is being customised (emoji, colour, notes). */
    var editingList by mutableStateOf(false)
}

/**
 * Asynchronous glue between the UI and [PlacesService]: everything that touches the database or a file runs
 * on [io]; state is updated on the caller's (main) scope. [onMarkers] receives the points to draw on the map
 * (the open list, or every saved place), and [near] the position of reference for distances.
 */
class PlacesController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val service: Lazy<PlacesService>,
    private val near: () -> LatLon?,
    private val onMarkers: (List<LatLon>) -> Unit,
    /** Called after every reload (imports and deletes end in one), for views of other stored data such as tracks. */
    private val onReloaded: () -> Unit = {},
) {
    val state = PlacesState()
    private var refreshJob: Job? = null

    // --- Place card ---

    fun showCard(info: PlaceInfo) {
        state.message = null
        state.card = info
        state.cardSavedId = null
        scope.launch {
            val id = withContext(io) { service.value.savedId(info) }
            if (state.card == info) state.cardSavedId = id
        }
    }

    fun closeCard() {
        state.card = null
        state.cardSavedId = null
    }

    fun toggleSaved() {
        val info = state.card ?: return
        val savedId = state.cardSavedId
        scope.launch {
            if (savedId != null) {
                withContext(io) { service.value.unsave(savedId) }
                if (state.card == info) state.cardSavedId = null
                state.message = PlacesMessage.Removed
            } else {
                val saved = withContext(io) { service.value.save(info) }
                if (state.card == info) state.cardSavedId = saved.placeId
                state.message = PlacesMessage.Saved(saved.listName)
            }
            reload()
        }
    }

    /** Whether [info] is already saved (asks the database off the main thread). */
    fun isSaved(info: PlaceInfo, onResult: (Boolean) -> Unit) {
        scope.launch { onResult(withContext(io) { service.value.savedId(info) } != null) }
    }

    /** Saves or unsaves [info] without opening the place card (used by the petrol-station card). */
    fun toggleSaved(info: PlaceInfo, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val savedId = withContext(io) { service.value.savedId(info) }
            if (savedId != null) {
                withContext(io) { service.value.unsave(savedId) }
                state.message = PlacesMessage.Removed
                onResult(false)
            } else {
                val saved = withContext(io) { service.value.save(info) }
                state.message = PlacesMessage.Saved(saved.listName)
                onResult(true)
            }
            reload()
        }
    }

    // --- Lists ---

    fun showMode(mode: PanelMode) {
        state.mode = mode
        state.message = null
        if (mode == PanelMode.LISTS) reload()
    }

    fun openList(list: PlaceList?) {
        state.editingList = false
        state.openList = list
        state.message = null
        state.query = ""
        reload()
    }

    fun setQuery(query: String) {
        state.query = query
        reload()
    }

    fun setByDistance(value: Boolean) {
        state.byDistance = value
        reload()
    }

    fun createList(name: String) = mutate { service.value.createList(name) }

    fun startEditingList() {
        if (state.openList != null) state.editingList = true
    }

    fun cancelEditingList() {
        state.editingList = false
    }

    /** Saves the editor of the open list; [ListStyle] cleans the values on the way in. */
    fun saveListStyle(edit: ListEdit) {
        val list = state.openList ?: return
        state.editingList = false
        mutate { service.value.customiseList(list.id, edit.name, edit.emoji, edit.color, edit.notes) }
    }

    fun deleteList(list: PlaceList) {
        if (state.openList?.id == list.id) state.openList = null
        mutate { service.value.deleteList(list.id) }
    }

    fun removePlace(placeId: Long) {
        val list = state.openList
        mutate { if (list != null) service.value.removeFromList(list.id, placeId) else service.value.unsave(placeId) }
    }

    /** Reloads lists, rows and map markers from the database. */
    fun reload() {
        refreshJob?.cancel()
        val open = state.openList
        val query = state.query
        val byDistance = state.byDistance
        val point = near()
        refreshJob = scope.launch {
            val loaded = withContext(io) {
                val svc = service.value
                val lists = svc.lists()
                val current = open?.let { o -> lists.firstOrNull { it.id == o.id } }
                val rows = svc.rows(current?.id, query, point, byDistance)
                Triple(lists, current, rows)
            }
            state.lists = loaded.first
            state.openList = loaded.second
            state.rows = loaded.third
            onMarkers(loaded.third.map { it.place.point })
            onReloaded()
        }
    }

    // --- Files (SAF: the caller opens the streams) ---

    fun import(open: () -> InputStream, fileName: String?) {
        val into = state.openList?.id
        scope.launch {
            val result = try {
                withContext(io) { service.value.importAny(open(), fileName, into) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            state.message = when (result) {
                null -> PlacesMessage.ImportFailed
                is ImportOutcome.Geo -> PlacesMessage.Imported(result.result)
                is ImportOutcome.Takeout ->
                    if (result.summary.isEmpty) PlacesMessage.ImportFailed else PlacesMessage.TakeoutImported(result.summary)
            }
            reload()
        }
    }

    fun export(format: GeoFormat, open: () -> OutputStream) {
        val listId = state.openList?.id
        scope.launch {
            val count = try {
                withContext(io) { open().use { service.value.export(format, listId, it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            state.message = if (count == null) PlacesMessage.ExportFailed else PlacesMessage.Exported(count)
        }
    }

    private fun mutate(block: () -> Unit) {
        scope.launch {
            withContext(io) { block() }
            reload()
        }
    }
}
