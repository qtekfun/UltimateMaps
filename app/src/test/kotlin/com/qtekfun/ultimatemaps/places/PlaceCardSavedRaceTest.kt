package com.qtekfun.ultimatemaps.places

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Holds every task of the IO dispatcher until the test runs it, so the order of the answers is in the test's hands. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
    fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    fun runOne() { queue.removeFirst().run() }
    fun runNewest() { queue.removeLast().run() }

    /** Runs the newest task first: the answers reach the main thread in the opposite order they were produced. */
    fun runAllNewestFirst() { while (queue.isNotEmpty()) queue.removeLast().run() }
}

/** Save right after opening a card, while the "already saved?" lookup is still pending, must not be undone by it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceCardSavedRaceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun savingBeforeTheLookupAnswersKeepsTheSavedState() {
        val fx = PersonalFixture()
        try {
            val io = ManualDispatcher()
            val main = ManualDispatcher()
            val scope = CoroutineScope(SupervisorJob() + main)
            val places = PlacesController(scope, io, fx.lazyService, near = { null }, onMarkers = {})
            val sol = PlaceInfo("Puerta del Sol", LatLon(40.41689, -3.70351), null, "Square")

            places.showCard(sol)
            main.runAll()
            io.runOne() // the lookup reads "not saved" ...
            places.toggleSaved()
            main.runNewest() // Save is tapped before the lookup's answer is delivered,
            io.runAll() // ... Save writes ...
            main.runAllNewestFirst() // ... and the lookup's answer reaches the main thread last.
            io.runAll()
            main.runAll()

            assertNotNull(places.state.cardSavedId)
            assertNotNull(fx.service.savedId(sol))
            places.toggleSaved()
            main.runAll()
            io.runAll()
            main.runAll()
            assertNull(places.state.cardSavedId)
            assertNull(fx.service.savedId(sol))
        } finally {
            fx.close()
        }
    }
}
