package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.nav.Announcement
import com.qtekfun.ultimatemaps.core.nav.NavConfig
import com.qtekfun.ultimatemaps.core.nav.NavEnvironment
import com.qtekfun.ultimatemaps.core.nav.NavEvent
import com.qtekfun.ultimatemaps.core.nav.NavStateStore
import com.qtekfun.ultimatemaps.core.nav.NavigationController
import com.qtekfun.ultimatemaps.core.nav.RouteProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertTrue

class FakeService : NavServiceControl {
    val starts = AtomicInteger()
    val resumes = AtomicInteger()
    val stops = AtomicInteger()
    override fun start() { starts.incrementAndGet() }
    override fun resume() { resumes.incrementAndGet() }
    override fun stop() { stops.incrementAndGet() }
}

class RecordingSink : NavEventSink {
    val log = CopyOnWriteArrayList<String>()
    val announcements = CopyOnWriteArrayList<Announcement>()
    val events = CopyOnWriteArrayList<NavEvent>()
    override fun onNavigationStarted(simulated: Boolean) { log += "started:$simulated" }
    override fun onAnnouncement(announcement: Announcement) { announcements += announcement }
    override fun onEvent(event: NavEvent) { events += event }
    override fun onNavigationEnded(arrived: Boolean) { log += "ended:$arrived" }
}

private class AlwaysOk : NavEnvironment {
    override fun hasLocationPermission() = true
    override fun isLocationEnabled() = true
    override fun isPowerSaveMode() = false
}

/**
 * One "process" of the navigation for the tests: the real [NavigationController] and follower, a fake location
 * and engine, and the screen model, all on ONE thread with a clock that only moves when the test (or the
 * simulation) moves it. The simulation yields between fixes instead of sleeping, so the order of events is fixed
 * and nothing depends on timing; [await] only waits for that thread to reach a state (its timeout is a failure
 * guard, never part of the logic).
 */
class NavTestRig(
    val file: File = File.createTempFile("navui", ".bin").also { it.delete(); it.deleteOnExit() },
    routes: RouteProvider? = null,
    config: NavConfig = NavConfig(),
    overviewMillis: Long = 60_000L,
    cameraSettings: com.qtekfun.ultimatemaps.core.cameras.CameraSettingsStore? = null,
) : AutoCloseable {
    val settings = com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "nav-test").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val clock = AtomicLong(1_000_000L)
    val real = SimulatedLocationSource()
    val switch = SwitchableLocationSource(real)
    val service = FakeService()
    val sink = RecordingSink()
    val store = NavStateStore(file, { clock.get() })
    val controller = NavigationController(
        scope, switch, store, AlwaysOk(), routes, config, { clock.get() }, dispatcher, watchEveryMillis = 50L,
    )

    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var gateWhen: (NavUi) -> Boolean = { false }

    val simulation = NavSimulation(scope, switch, { clock.get() }, 1_000L) { millis ->
        clock.addAndGet(millis)
        yield()
        val g = gate
        if (g != null && gateWhen(screen.ui.value)) g.await()
    }
    val screen: NavScreenController = NavScreenController(scope, controller, simulation, switch, service, settings = settings, clock = { clock.get() }, stopFlashMillis = 60_000L, overviewMillis = overviewMillis, cameraSettings = cameraSettings)

    init { screen.addSink(sink) }

    fun await(what: String, cond: (NavUi) -> Boolean): NavUi = runBlocking {
        withTimeout(20_000) { screen.ui.first(cond) }
    }.also { assertTrue(cond(it), what) }

    fun emitReal(point: LatLon, seconds: Int = 1, speed: Float = 10f) {
        // The follower starts listening on the test thread; wait for that (a state, not a time).
        val end = System.nanoTime() + 10_000_000_000L
        while (!real.isStarted) {
            check(System.nanoTime() < end) { "the follower never started listening" }
            Thread.sleep(1)
        }
        repeat(seconds) {
            clock.addAndGet(1_000L)
            real.emit(LocationFix(point, 5f, null, speed, clock.get()))
        }
    }

    /** Lets everything already queued on the test thread run. */
    fun settle() = runBlocking { kotlinx.coroutines.withContext(dispatcher) { yield() } }

    /** Waits until nothing moves any more (the simulation is parked at its gate) and returns that final state. */
    fun awaitParked(): NavUi {
        var last = screen.ui.value
        repeat(200) {
            settle()
            settle()
            val now = screen.ui.value
            if (now == last) return now
            last = now
        }
        error("the navigation never settled")
    }

    override fun close() {
        scope.cancel()
        executor.shutdownNow()
    }
}
