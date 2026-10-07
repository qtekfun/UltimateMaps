package com.qtekfun.mapas.nav

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.regions.AssetKind
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionAsset
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteFailurePolicyTest {
    private val a = LatLon(40.0, -3.0)
    private val b = LatLon(41.0, 2.0)
    private val request = RouteRequest(a, b)
    private val plan = RoutePlan(listOf(a, b), 600_000.0, 20_000.0)

    private class Engine(val reply: () -> RouteOutcome) : DetailedRoutingEngine {
        val calls = AtomicInteger()
        override fun routeDetailed(request: RouteRequest): RouteOutcome { calls.incrementAndGet(); return reply() }
        override fun route(request: RouteRequest) = routeDetailed(request).plan
        override fun close() = Unit
    }

    @Test fun everyCodeIsClassified() {
        fun kind(code: Int) = RouteFailurePolicy.classify(RouteOutcome(code, null)).kind
        assertEquals(RouteFailureKind.NEED_MORE_MAPS, kind(RouteCode.NEED_MORE_MAPS))
        assertEquals(RouteFailureKind.START_NOT_FOUND, kind(RouteCode.START_NOT_FOUND))
        assertEquals(RouteFailureKind.END_NOT_FOUND, kind(RouteCode.END_NOT_FOUND))
        assertEquals(RouteFailureKind.INTERMEDIATE_NOT_FOUND, kind(RouteCode.INTERMEDIATE_NOT_FOUND))
        assertEquals(RouteFailureKind.ROUTE_NOT_FOUND, kind(RouteCode.ROUTE_NOT_FOUND))
        assertEquals(RouteFailureKind.TIMEOUT, kind(RouteCode.CANCELLED))
        assertEquals(RouteFailureKind.CORE_CRASHED, kind(RouteCode.CORE_CRASHED))
        assertEquals(RouteFailureKind.CORE_UNAVAILABLE, kind(RouteCode.CORE_UNAVAILABLE))
        assertEquals(RouteFailureKind.INTERNAL, kind(RouteCode.INTERNAL_ERROR))
        assertEquals(RouteFailureKind.INTERNAL, kind(12345))
        assertEquals(RouteAdvice.DOWNLOAD_MAPS, RouteFailurePolicy.classify(RouteOutcome(RouteCode.NEED_MORE_MAPS, null)).advice)
    }

    @Test fun onlyTransientFailuresAreRetriedAutomatically() {
        assertEquals(1, RouteFailurePolicy.retries(RouteFailureKind.CORE_CRASHED))
        assertEquals(1, RouteFailurePolicy.retries(RouteFailureKind.INTERNAL))
        for (k in listOf(RouteFailureKind.ROUTE_NOT_FOUND, RouteFailureKind.NEED_MORE_MAPS, RouteFailureKind.START_NOT_FOUND, RouteFailureKind.TIMEOUT)) {
            assertEquals(0, RouteFailurePolicy.retries(k), k.name)
        }
    }

    @Test fun absentCountriesBecomeCatalogRegionNames() {
        fun leaf(id: String, name: String, comaps: String?) = Region(
            id, name, null, "261005",
            AssetKind.entries.associateWith { RegionAsset("https://x/$id", 1, "0".repeat(64), "$id.${it.key}") }, comaps,
        )
        val catalog = RegionCatalog("1", listOf(leaf("es-cat-bcn", "Barcelona", "Spain_Catalonia_Barcelona"), leaf("es-ara", "Aragón", "Spain_Aragon")))
        val names = RouteFailurePolicy.missingRegionNames(listOf("Spain_Aragon", "Spain_Catalonia_Barcelona", "Mars_Olympus"), catalog)
        assertEquals(listOf("Aragón", "Barcelona"), names)
        assertTrue(RouteFailurePolicy.missingRegionNames(emptyList(), catalog).isEmpty())
        assertTrue(RouteFailurePolicy.missingRegionNames(listOf("Spain_Aragon"), null).isEmpty())
        val context: Application = ApplicationProvider.getApplicationContext()
        val failure = RouteFailure(RouteFailureKind.NEED_MORE_MAPS, RouteAdvice.DOWNLOAD_MAPS, listOf("Spain_Aragon"))
        assertTrue(RouteFailureMessages.of(context, failure, catalog).contains("Aragón"))
        assertTrue(RouteFailureMessages.of(context, failure.copy(absentRegionIds = emptyList()), catalog).isNotBlank())
        for (k in RouteFailureKind.entries) assertTrue(RouteFailureMessages.of(context, RouteFailure(k, RouteAdvice.RETRY), null).isNotBlank(), k.name)
    }

    @Test fun deterministicFailuresAreNotRetried() = runBlocking {
        val engine = Engine { RouteOutcome(RouteCode.ROUTE_NOT_FOUND, null) }
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 5_000).run(request) { engine }
        assertEquals(RouteFailureKind.ROUTE_NOT_FOUND, run.failure?.kind)
        assertEquals(1, engine.calls.get())
    }

    @Test fun aCoreCrashIsRetriedOnceAndTheSecondTrySucceeds() = runBlocking {
        val engine = Engine { if (it0.incrementAndGet() == 1) RouteOutcome(RouteCode.CORE_CRASHED, null) else RouteOutcome(RouteCode.NO_ERROR, plan) }
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 10_000).run(request) { engine }
        assertNotNull(run.plan)
        assertEquals(2, run.attempts)
    }

    @Test fun aCoreThatKeepsCrashingEndsWithAStructuredFailure() = runBlocking {
        val engine = Engine { RouteOutcome(RouteCode.CORE_CRASHED, null) }
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 10_000).run(request) { engine }
        assertNull(run.plan)
        assertEquals(RouteFailureKind.CORE_CRASHED, run.failure?.kind)
        assertEquals(2, engine.calls.get())
    }

    @Test fun noRegionsMeansNoEngineAndNoCall() = runBlocking {
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 1_000).run(request) { null }
        assertEquals(RouteFailureKind.NO_REGIONS, run.failure?.kind)
    }

    @Test fun anExceptionFromTheEngineIsAnInternalFailureNotACrash() = runBlocking {
        val engine = Engine { error("boom") }
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 10_000).run(request) { engine }
        assertEquals(RouteFailureKind.INTERNAL, run.failure?.kind)
    }

    @Test fun aStuckCalculationIsInterruptedAtTheDeadline() = runBlocking {
        val interrupted = CompletableDeferred<Boolean>()
        val engine = Engine {
            try {
                Thread.sleep(60_000)
                RouteOutcome(RouteCode.NO_ERROR, plan)
            } catch (e: InterruptedException) {
                interrupted.complete(true) // what IsolatedCore turns into "kill the core process"
                throw e
            }
        }
        val started = System.nanoTime()
        val run = RouteRunner(Dispatchers.IO, timeoutMillis = 300).run(request) { engine }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(RouteFailureKind.TIMEOUT, run.failure?.kind)
        assertTrue(ms < 3_000, "took $ms ms")
        assertTrue(interrupted.await(), "the abandoned calculation was not interrupted")
    }

    @Test fun cancellingTheCoroutineInterruptsTheCalculation() = runBlocking {
        val interrupted = CompletableDeferred<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val engine = Engine {
            entered.complete(Unit)
            try { Thread.sleep(60_000); RouteOutcome(RouteCode.NO_ERROR, plan) } catch (e: InterruptedException) { interrupted.complete(true); throw e }
        }
        val job = async { RouteRunner(Dispatchers.IO, timeoutMillis = 60_000).run(request) { engine } }
        entered.await()
        job.cancelAndJoin()
        assertTrue(interrupted.await())
    }

    private val it0 = AtomicInteger()
}
