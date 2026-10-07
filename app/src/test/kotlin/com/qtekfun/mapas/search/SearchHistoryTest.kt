package com.qtekfun.mapas.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.places.PersonalFixture
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryHistorySettings(override var enabled: Boolean = true) : HistorySettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryTest {
    private val fx = PersonalFixture()
    private val settings = MemoryHistorySettings()

    private fun history() = SearchHistory(fx.scope, fx.io, fx.lazyService, settings)

    @After fun tearDown() = fx.close()

    @Test fun recordedSearchesAreShownNewestFirstWithoutDuplicates() {
        val h = history()
        h.record("farmacia"); h.record("café"); h.record("Farmacia")
        assertEquals(listOf("Farmacia", "café"), h.state.items)
    }

    @Test fun theyComeBackAfterARestart() {
        history().record("gasolinera")
        val again = history()
        assertTrue(again.state.items.isEmpty())
        again.refresh()
        assertEquals(listOf("gasolinera"), again.state.items)
    }

    @Test fun blankAndTooShortQueriesAreIgnored() {
        val h = history()
        h.record(""); h.record("   "); h.record("a")
        assertTrue(h.state.items.isEmpty())
        h.record("ab")
        assertEquals(listOf("ab"), h.state.items)
    }

    @Test fun veryLongQueriesAreCut() {
        val h = history()
        h.record("x".repeat(500))
        assertEquals(SearchHistory.MAX_LENGTH, h.state.items.single().length)
    }

    @Test fun nothingIsRecordedWhileTheHistoryIsOff() {
        val h = history()
        settings.enabled = false
        h.record("secreto")
        assertTrue(h.state.items.isEmpty())
        assertTrue(fx.service.recentSearches().isEmpty())
    }

    @Test fun switchingItOffHidesWhatWasStoredAndOnShowsItAgain() {
        val h = history()
        h.record("farmacia")
        settings.enabled = false
        h.refresh()
        assertTrue(h.state.items.isEmpty())
        // Hiding is not deleting: the Settings screen deletes when the switch goes off (see SettingsHistoryTest).
        settings.enabled = true
        h.refresh()
        assertEquals(listOf("farmacia"), h.state.items)
    }

    @Test fun clearEmptiesTheListAndTheDatabase() {
        val h = history()
        h.record("farmacia"); h.record("café")
        h.clear()
        assertTrue(h.state.items.isEmpty())
        assertTrue(fx.service.recentSearches().isEmpty())
    }

    @Test fun theSwitchIsOnByDefaultAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsHistorySettings(context)
        assertTrue(prefs.enabled)
        prefs.enabled = false
        assertFalse(PrefsHistorySettings(context).enabled)
        prefs.enabled = true
        assertTrue(PrefsHistorySettings(context).enabled)
    }
}
