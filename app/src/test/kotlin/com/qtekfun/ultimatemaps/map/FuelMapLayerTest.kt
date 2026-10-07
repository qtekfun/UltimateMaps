package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.fuel.FuelRepository
import com.qtekfun.ultimatemaps.core.fuel.FuelStation
import com.qtekfun.ultimatemaps.core.fuel.FuelSettings
import com.qtekfun.ultimatemaps.core.fuel.FuelType
import com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelRepository
import com.qtekfun.ultimatemaps.core.fuel.LatLonBounds
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.FuelPin
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuelMapLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val es = Locale.forLanguageTag("es-ES")

    private fun station(id: String, lat: Double, lon: Double, vararg prices: Pair<String, Double>) =
        FuelStation(id, "Brand $id", "Calle $id", "Madrid", "Madrid", LatLon(lat, lon), prices = mapOf(*prices))

    /** Counts queries and remembers the limit asked for; data can change under it. */
    private class Spy(var stations: List<FuelStation>) : FuelRepository {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var lastLimit = -1
        override val lastUpdateMillis = MutableStateFlow<Long?>(1L)
        override fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int): List<FuelStation> {
            calls.incrementAndGet()
            lastLimit = limit
            return InMemoryFuelRepository(stations).stationsIn(bounds, fuel, limit)
        }
        override fun station(id: String) = stations.firstOrNull { it.id == id }
    }

    private val view = GeoBounds(40.0, -4.0, 41.0, -3.0)
    private val drawn = CopyOnWriteArrayList<List<FuelPin>>()
    private val gasoline = "g95"
    private val diesel = "diesel"

    private val three = listOf(
        station("a", 40.1, -3.9, gasoline to 1.154, diesel to 1.300),
        station("b", 40.5, -3.5, gasoline to 1.249, diesel to 1.100),
        station("c", 40.9, -3.1, gasoline to 1.399, diesel to 1.250),
    )

    private fun layer(repo: FuelRepository, settings: StaticFuelSettings, debounce: Long = 10) =
        FuelMapLayer(scope, Dispatchers.IO, repo, settings.settings, { drawn += it }, { es }, debounce).also { it.start() }

    private fun on(fuel: String? = gasoline) = StaticFuelSettings(FuelSettings(enabled = true, downloadedFuels = setOf(gasoline, diesel), mapFuel = fuel))

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    /** Lets any pending debounce expire, to assert that nothing happened. */
    private fun settle() = Thread.sleep(150)

    @After fun tearDown() = scope.cancel()

    @Test
    fun drawsThePriceOfTheChosenFuelCheapestFirstAndFlagsTheCheapest() {
        val l = layer(Spy(three), on())
        l.onViewport(view, 12.0)
        await("draw") { drawn.isNotEmpty() }
        val pins = drawn.last()
        assertEquals(listOf("a", "b", "c"), pins.map { it.id })
        assertEquals(listOf("1,154 €", "1,249 €", "1,399 €"), pins.map { it.label })
        assertEquals(listOf(0, 1, 2), pins.map { it.rank })
        assertEquals(listOf(true, false, false), pins.map { it.cheap }) // 1 of 3: at least one, never all
    }

    @Test
    fun offOrNoDataDrawsNothingAndCostsNothing() {
        val spy = Spy(three)
        val off = layer(spy, StaticFuelSettings())
        off.onViewport(view, 14.0)
        settle()
        assertTrue(drawn.isEmpty())
        assertEquals(0, spy.calls.get())

        val noFuel = layer(spy, on(fuel = null))
        noFuel.onViewport(view, 14.0)
        settle()
        assertTrue(drawn.isEmpty())
        assertEquals(0, spy.calls.get())

        val empty = layer(NoFuelData, on())
        empty.onViewport(view, 14.0)
        settle()
        assertTrue(drawn.isEmpty()) // nothing found: nothing to redraw
    }

    @Test
    fun belowTheMinimumZoomNothingIsDrawnNorQueried() {
        val spy = Spy(three)
        layer(spy, on()).onViewport(view, 10.9)
        settle()
        assertTrue(drawn.isEmpty())
        assertEquals(0, spy.calls.get())
    }

    @Test
    fun theLimitGrowsWithZoomAndIsPassedToTheRepository() {
        val spy = Spy(three)
        val l = layer(spy, on())
        l.onViewport(view, 11.5)
        await("query") { spy.calls.get() == 1 }
        assertEquals(FuelMapLayer.limitFor(11.5), spy.lastLimit)
        l.onViewport(GeoBounds(40.0, -4.0, 41.0, -3.01), 15.0)
        await("second query") { spy.calls.get() == 2 }
        assertEquals(FuelMapLayer.limitFor(15.0), spy.lastLimit)
        assertTrue(FuelMapLayer.limitFor(11.5) < FuelMapLayer.limitFor(15.0))
    }

    @Test
    fun changingTheFuelRedraws() {
        val settings = on()
        layer(Spy(three), settings).onViewport(view, 12.0)
        await("first") { drawn.size == 1 }
        settings.update { it.copy(mapFuel = diesel) }
        await("redraw") { drawn.size == 2 }
        assertEquals(listOf("b", "c", "a"), drawn.last().map { it.id }) // diesel: 1,100 / 1,250 / 1,300
        assertEquals("1,100 €", drawn.last().first().label)
    }

    @Test
    fun switchingOffClearsTheMapAndStopsQuerying() {
        val spy = Spy(three)
        val settings = on()
        val l = layer(spy, settings)
        l.onViewport(view, 12.0)
        await("draw") { drawn.size == 1 }
        settings.update { it.copy(enabled = false) }
        await("clear") { drawn.size == 2 }
        assertTrue(drawn.last().isEmpty())
        val calls = spy.calls.get()
        l.onViewport(view, 13.0)
        settle()
        assertEquals(calls, spy.calls.get())
        assertEquals(2, drawn.size)
    }

    @Test
    fun anUnchangedResultIsNotRedrawn() {
        val l = layer(Spy(three), on())
        l.onViewport(view, 12.0)
        await("draw") { drawn.size == 1 }
        l.onViewport(view, 12.0) // same view again
        l.onViewport(GeoBounds(40.0, -4.0, 41.0, -2.99), 12.0) // pan that keeps the same stations
        settle()
        assertEquals(1, drawn.size)
    }

    @Test
    fun aBurstOfGesturesQueriesOnce() {
        val spy = Spy(three)
        // Real debounce: with 80 ms the loop could take longer (loaded machine) and 2 queries came out, not 1 (flaky test).
        val l = layer(spy, on(), debounce = 400)
        repeat(5) { l.onViewport(view, 12.0 + it * 0.01) }
        await("draw") { drawn.isNotEmpty() }
        settle()
        assertEquals(1, spy.calls.get())
    }

    @Test
    fun newRepositoryDataRedraws() {
        val spy = Spy(three.take(1))
        layer(spy, on()).onViewport(view, 12.0)
        await("first") { drawn.size == 1 }
        spy.stations = three
        spy.lastUpdateMillis.value = 2L
        await("redraw") { drawn.size == 2 }
        assertEquals(3, drawn.last().size)
    }

    @Test
    fun zoomedOutKeepsOnlyTheCheapestOfNeighbours() {
        val close = listOf(
            station("p", 40.5000, -3.5000, gasoline to 1.50),
            station("q", 40.5001, -3.5001, gasoline to 1.40),
            station("far", 40.9, -3.1, gasoline to 1.60),
        )
        layer(Spy(close), on()).onViewport(view, 11.0)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(listOf("q", "far"), drawn.last().map { it.id })
        // zoomed in all three show
        layer(Spy(close), on()).onViewport(view, 16.0)
        await("zoomed in") { drawn.size == 2 }
        assertEquals(3, drawn.last().size)
    }

    @Test
    fun cheapStationsAreFlaggedWithoutRelyingOnColourAlone() {
        // a pin carries a boolean (the engine draws a different icon shape and size), never only a colour
        val many = (1..20).map { station("s$it", 40.0 + it * 0.04, -3.5, gasoline to 1.0 + it * 0.01) }
        layer(Spy(many), on()).onViewport(view, 14.0)
        await("draw") { drawn.isNotEmpty() }
        val pins = drawn.last()
        assertEquals(2, pins.count { it.cheap }) // 10% of 20
        assertTrue(pins.filter { it.cheap }.all { it.rank < 2 })
        assertFalse(pins.last().cheap)
    }
}
