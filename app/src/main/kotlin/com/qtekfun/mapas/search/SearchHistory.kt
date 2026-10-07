package com.qtekfun.mapas.search

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.places.PanelRow
import com.qtekfun.mapas.places.PlacesService
import com.qtekfun.mapas.ui.theme.Mapas
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the on-device search history is kept. On by default; the history never leaves the device either way. */
interface HistorySettings {
    var enabled: Boolean
}

/** [HistorySettings] over SharedPreferences (`mapas_history`). */
class PrefsHistorySettings(private val prefs: SharedPreferences) : HistorySettings {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        const val PREFS = "mapas_history"
        const val KEY_ENABLED = "enabled"
    }
}

class SearchHistoryState {
    /** Most recent first; always empty while the history is switched off. */
    var items by mutableStateOf<List<String>>(emptyList())
}

/**
 * Recent searches: the text of a query is remembered when the user picks one of its results, in the on-device
 * database, only while [settings] allows it. Nothing is ever logged or sent. Database work runs on [io].
 */
class SearchHistory(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val service: Lazy<PlacesService>,
    private val settings: HistorySettings,
) {
    val state = SearchHistoryState()

    /** Re-reads the switch and the stored searches (call on start: the switch lives in another screen). */
    fun refresh() {
        if (!settings.enabled) {
            state.items = emptyList()
            return
        }
        scope.launch {
            val loaded = withContext(io) { service.value.recentSearches() }
            if (settings.enabled) state.items = loaded
        }
    }

    /** Remembers [query] (ignored when blank, under 2 characters, or the history is off). */
    fun record(query: String) {
        val text = query.trim()
        if (!settings.enabled || text.length < MIN_LENGTH) return
        scope.launch {
            val loaded = withContext(io) { service.value.addSearch(text.take(MAX_LENGTH)); service.value.recentSearches() }
            if (settings.enabled) state.items = loaded
        }
    }

    fun clear() {
        state.items = emptyList()
        scope.launch { withContext(io) { service.value.clearSearches() } }
    }

    companion object {
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 100
    }
}

/** The recent searches as rows of a result list: a header with the clear button, then one row per search. */
fun LazyListScope.recentSearchItems(history: SearchHistory, onPick: (String) -> Unit) {
    val items = history.state.items
    if (items.isEmpty()) return
    item(key = "recent_header") {
        Row(Modifier.fillMaxWidth().testTag("recent_header"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                stringResource(R.string.recent_title),
                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.weight(1f),
            )
            PanelButton(stringResource(R.string.recent_clear), history::clear, tag = "recent_clear")
        }
    }
    items(items.size, key = { "recent_$it" }) { i ->
        PanelRow(items[i], null, null, { onPick(items[i]) }, tag = "recent_row")
    }
}
