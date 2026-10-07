package com.qtekfun.mapas.nativecomaps.isolation

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.nativecomaps.CoMapsCore
import com.qtekfun.mapas.nativecomaps.NativeBridge
import com.qtekfun.mapas.nativecomaps.RawGuidedRoute
import com.qtekfun.mapas.nativecomaps.RouteCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The isolation client against a fake transport: the real native core is NOT involved (it does not even load);
 * what runs is the real [CoreHost] on top of a fake [NativeBridge], connected through a transport that can die,
 * hang, refuse, or limit the size of a transaction like Binder does.
 */
class IsolatedCoreTest {
    /** Counts what reaches the "native" side. */
    private class Bridge : NativeBridge {
        val inits = AtomicInteger()
        val searches = AtomicInteger()
        val routes = AtomicInteger()
        @Volatile var routePoints = 2
        @Volatile var onRoute: () -> Unit = {}
        override fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String { inits.incrementAndGet(); return "" }
        override fun refreshMaps() = 7
        override fun search(query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String): Array<String> {
            searches.incrementAndGet()
            return arrayOf("Cafe Central", "Calle Mayor 1", "cafe", "40.4168", "-3.7038")
        }
        override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray {
            routes.incrementAndGet()
            onRoute()
            val n = routePoints
            val raw = DoubleArray(3 + 2 * n)
            raw[1] = 1000.0 * n
            raw[2] = 60.0 * n
            for (i in 0 until n) { raw[3 + 2 * i] = 40.0 + i * 1e-5; raw[4 + 2 * i] = -3.0 + i * 1e-5 }
            return raw
        }
        override fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int) =
            RawGuidedRoute(route(profile, points, avoidFlags, timeoutSec), DoubleArray(0), emptyArray())
    }

    /** A fake "process": a [CoreHost] with its own state. A new connect starts a new one (state lost, like a restart). */
    private class FakeTransport(val bridge: Bridge, val maxTransaction: Int = 1_000_000) : CoreTransport {
        val connects = AtomicInteger()
        val kills = AtomicInteger()
        @Volatile var failConnect = false
        @Volatile var dieOnCallNumber = -1 // global call counter
        @Volatile var dieOnOp = -1
        @Volatile var dieOnceOnOp = -1
        @Volatile var dieAlways = false
        @Volatile var hangOnOp = -1
        @Volatile var connectDelayMs = 0L
        val calls = AtomicInteger()
        val tooLarge = AtomicInteger()

        override fun connect(timeoutMillis: Long): CoreConnection {
            if (failConnect) throw CoreDiedException("cannot bind")
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
            connects.incrementAndGet()
            val host = CoreHost(CoMapsCore(bridge))
            return object : CoreConnection {
                @Volatile var alive = true
                val released = CountDownLatch(1)
                override val isAlive get() = alive
                override fun call(op: Int, payload: ByteArray): ByteArray {
                    val n = calls.incrementAndGet()
                    if (!alive) throw CoreDiedException()
                    if (op == dieOnceOnOp) { dieOnceOnOp = -1; alive = false; throw CoreDiedException("SIGABRT") }
                    if (dieAlways || n == dieOnCallNumber || op == dieOnOp) { alive = false; throw CoreDiedException("SIGABRT") }
                    if (op == hangOnOp) {
                        released.await() // a native call that never returns, until the process is killed
                        throw CoreDiedException("killed while hanging")
                    }
                    check(payload.size <= maxTransaction) { "request too large" }
                    val out = host.handle(op, payload)
                    if (out.size > maxTransaction) { tooLarge.incrementAndGet(); throw IllegalStateException("TransactionTooLargeException") }
                    return out
                }
                override fun kill() { kills.incrementAndGet(); alive = false; released.countDown() }
                override fun close() { alive = false }
            }
        }
    }

    private val a = LatLon(40.0, -3.0)
    private val b = LatLon(40.1, -3.1)
    private fun fast() = IsolationConfig(restartBudgetMillis = 500, searchSlackMillis = 200, routeSlackMillis = 200, circuitOpenMillis = 400)

    private fun client(t: FakeTransport, config: IsolationConfig = fast()) =
        IsolatedCore(t, config).also { it.init("a.apk", "/maps", "/tmp", "en") }

    @Test fun `nothing starts until the first call and init happens once per process`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        assertEquals(0, t.connects.get())
        assertEquals(1, c.searchEngine().search("cafe").size)
        c.searchEngine().search("cafe")
        c.init("a.apk", "/maps", "/tmp", "en") // same arguments: no restart, no second init
        c.searchEngine().search("cafe")
        assertEquals(1, t.connects.get())
        assertEquals(1, t.bridge.inits.get())
        assertEquals(3, t.bridge.searches.get())
    }

    @Test fun `refresh is remembered and replayed after a restart`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        assertEquals(7, c.refreshMaps())
        t.dieOnCallNumber = t.calls.get() + 1
        assertEquals(1, c.searchEngine().search("cafe").size) // died, restarted, re-initialised, retried once
        assertEquals(2, t.connects.get())
        assertEquals(2, t.bridge.inits.get())
    }

    @Test fun `a death during a call restarts the core and retries exactly once`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("warm up")
        t.dieOnCallNumber = t.calls.get() + 1
        val plan = c.routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b))
        assertNotNull(plan.plan)
        assertEquals(2, t.connects.get())
        assertEquals(1, t.bridge.routes.get()) // the dying attempt never reached the native side
    }

    @Test fun `two deaths in a row give a structured error, not a loop`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("warm up")
        t.dieAlways = true
        val e = assertFailsWith<CoreException> { c.searchEngine().search("cafe") }
        assertEquals(CoreFailureKind.CRASHED, e.kind)
        val before = t.connects.get()
        val outcome = c.routingEngine().routeDetailed(RouteRequest(a, b))
        assertNull(outcome.plan)
        assertTrue(outcome.code == RouteCode.CORE_CRASHED || outcome.code == RouteCode.CORE_UNAVAILABLE, "code ${outcome.code}")
        assertTrue(t.connects.get() - before <= 2)
    }

    @Test fun `a death during init is retried once too`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        t.dieOnceOnOp = CoreProtocol.OP_INIT // the first INIT dies; the retry gets a fresh process
        assertEquals(1, c.searchEngine().search("cafe").size)
        assertEquals(2, t.connects.get())
        assertEquals(1, t.bridge.inits.get()) // the dying INIT never reached the native side
    }

    @Test fun `a hung native call is stopped at its deadline and the core is killed`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("warm up")
        t.hangOnOp = CoreProtocol.OP_SEARCH
        val started = System.nanoTime()
        val e = assertFailsWith<CoreException> { c.searchEngine(timeoutMs = 100).search("cafe") }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(CoreFailureKind.TIMEOUT, e.kind)
        assertTrue(ms < 2_000, "took $ms ms")
        assertEquals(1, t.kills.get())
        // Not left half alive: the next call starts a clean process and works.
        t.hangOnOp = -1
        assertEquals(1, c.searchEngine().search("cafe").size)
        assertEquals(2, t.connects.get())
    }

    @Test fun `a route that times out reports the cancelled code the UI already understands`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.refreshMaps()
        t.hangOnOp = CoreProtocol.OP_ROUTE
        val outcome = c.routingEngine(timeoutSec = 0).routeDetailed(RouteRequest(a, b))
        assertEquals(RouteCode.CANCELLED, outcome.code)
        assertNull(outcome.plan)
    }

    @Test fun `abandoned calls do not pile up`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("warm up")
        t.hangOnOp = CoreProtocol.OP_SEARCH
        repeat(3) { assertFailsWith<CoreException> { c.searchEngine(timeoutMs = 50).search("cafe") } }
        assertEquals(3, t.kills.get()) // one kill per stuck call, each released its worker
        t.hangOnOp = -1
        assertEquals(1, c.searchEngine().search("cafe").size) // and the core is usable afterwards
    }

    @Test fun `interrupting the caller kills the core and does not wait for the call`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("warm up")
        t.hangOnOp = CoreProtocol.OP_SEARCH
        var failure: Throwable? = null
        val worker = Thread { failure = runCatching { c.searchEngine(timeoutMs = 60_000).search("cafe") }.exceptionOrNull() }
        worker.start()
        while (t.calls.get() < 3) Thread.sleep(2)
        worker.interrupt()
        worker.join(2_000)
        assertTrue(!worker.isAlive)
        assertEquals(CoreFailureKind.CANCELLED, (failure as CoreException).kind)
        assertEquals(1, t.kills.get())
    }

    @Test fun `a huge route crosses in chunks under the transaction limit`() {
        val bridge = Bridge().apply { routePoints = 40_000 } // ~640 KB of points
        val t = FakeTransport(bridge, maxTransaction = 250_000)
        val c = client(t)
        val plan = c.routingEngine().routeDetailed(RouteRequest(a, b)).plan
        assertNotNull(plan)
        assertEquals(40_000, plan.geometry.size)
        assertEquals(LatLon(40.0 + 39_999 * 1e-5, -3.0 + 39_999 * 1e-5), plan.geometry.last())
        assertEquals(0, t.tooLarge.get())
        assertTrue(t.calls.get() >= 5, "expected several chunk fetches, got ${t.calls.get()} calls")
    }

    @Test fun `without chunking the same route would have been refused by the transport`() {
        // Guards the test above: the fake really enforces the limit (a plain 640 KB reply is over it).
        val bridge = Bridge().apply { routePoints = 40_000 }
        val t = FakeTransport(bridge, maxTransaction = 250_000)
        val host = CoreHost(CoMapsCore(bridge))
        host.handle(CoreProtocol.OP_INIT, CoreProtocol.encodeInit(CoreProtocol.InitArgs("a", "m", "t", "en")))
        val raw = host.handle(CoreProtocol.OP_ROUTE, CoreProtocol.encodeRoute(CoreProtocol.RouteArgs(0, doubleArrayOf(40.0, -3.0, 40.1, -3.1), 0, 1, false)))
        assertTrue(raw.size < 250_000) // chunked: the first answer is small
        assertEquals(CoreProtocol.KIND_CHUNKED, raw[0].toInt())
        assertEquals(1, host.parkedCount)
        assertEquals(0, t.tooLarge.get())
    }

    @Test fun `a core that dies in the middle of a chunked reply is retried from scratch`() {
        val bridge = Bridge().apply { routePoints = 40_000 }
        val t = FakeTransport(bridge, maxTransaction = 250_000)
        val c = client(t)
        c.refreshMaps()
        t.dieOnceOnOp = CoreProtocol.OP_FETCH
        val plan = c.routingEngine().routeDetailed(RouteRequest(a, b)).plan
        assertEquals(40_000, plan?.geometry?.size)
        assertEquals(2, t.connects.get())
    }

    @Test fun `parked replies are bounded so a vanished client cannot make the host leak`() {
        val bridge = Bridge().apply { routePoints = 40_000 }
        val host = CoreHost(CoMapsCore(bridge), maxParked = 2)
        host.handle(CoreProtocol.OP_INIT, CoreProtocol.encodeInit(CoreProtocol.InitArgs("a", "m", "t", "en")))
        val req = CoreProtocol.encodeRoute(CoreProtocol.RouteArgs(0, doubleArrayOf(40.0, -3.0, 40.1, -3.1), 0, 1, false))
        repeat(10) { host.handle(CoreProtocol.OP_ROUTE, req) }
        assertEquals(2, host.parkedCount)
    }

    @Test fun `repeated crashes open the circuit and calls fail fast, then it closes`() {
        val t = FakeTransport(Bridge())
        val cfg = IsolationConfig(restartBudgetMillis = 300, searchSlackMillis = 100, maxCrashes = 2, crashWindowMillis = 10_000, circuitOpenMillis = 300)
        val c = client(t, cfg)
        t.dieAlways = true
        assertFailsWith<CoreException> { c.searchEngine().search("x") }
        val connects = t.connects.get()
        val e = assertFailsWith<CoreException> { c.searchEngine().search("x") }
        assertEquals(CoreFailureKind.UNAVAILABLE, e.kind)
        assertEquals(connects, t.connects.get()) // failed fast: did not even try to start the process
        t.dieAlways = false
        Thread.sleep(350)
        assertEquals(1, c.searchEngine().search("x").size) // closed again
    }

    @Test fun `a core that cannot be started is unavailable, with no exception escaping from a route`() {
        val t = FakeTransport(Bridge()).apply { failConnect = true }
        val c = client(t)
        val outcome = c.routingEngine().routeDetailed(RouteRequest(a, b))
        assertNull(outcome.plan)
        assertTrue(outcome.code == RouteCode.CORE_CRASHED || outcome.code == RouteCode.CORE_UNAVAILABLE)
        val e = assertFailsWith<CoreException> { c.searchEngine().search("x") }
        assertTrue(e.kind == CoreFailureKind.CRASHED || e.kind == CoreFailureKind.UNAVAILABLE, "kind ${e.kind}")
    }

    @Test fun `using the core before init is a structured error`() {
        val c = IsolatedCore(FakeTransport(Bridge()), fast())
        val e = assertFailsWith<CoreException> { c.searchEngine().search("x") }
        assertEquals(CoreFailureKind.INIT_FAILED, e.kind)
    }

    @Test fun `an init the core refuses is reported with its reason`() {
        val refusing = object : NativeBridge by Bridge() {
            override fun init(apk: String, writableDir: String, tmpDir: String, locale: String) = "sin classificator"
        }
        val host = CoreHost(CoMapsCore(refusing))
        val reply = CoreProtocol.decodeReply(host.handle(CoreProtocol.OP_INIT, CoreProtocol.encodeInit(CoreProtocol.InitArgs("a", "m", "t", "en"))))
        assertEquals(CoreProtocol.KIND_ERROR, reply.kind)
        assertEquals(CoreProtocol.ERR_INIT_FAILED, CoreProtocol.decodeError(reply.bytes).first)
        // Through the client: INIT_FAILED, not a crash, not retried as one.
        val c = IsolatedCore({ _ -> hostConnection(host) }, fast())
        c.init("a", "m", "t")
        assertEquals(CoreFailureKind.INIT_FAILED, assertFailsWith<CoreException> { c.searchEngine().search("x") }.kind)
    }

    private fun hostConnection(host: CoreHost) = object : CoreConnection {
        override val isAlive = true
        override fun call(op: Int, payload: ByteArray) = host.handle(op, payload)
        override fun kill() = Unit
        override fun close() = Unit
    }

    @Test fun `the host turns malformed requests and Java exceptions into error replies`() {
        val boom = object : NativeBridge by Bridge() {
            override fun search(query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String): Array<String> =
                throw IllegalStateException("secret query text")
        }
        val host = CoreHost(CoMapsCore(boom))
        host.handle(CoreProtocol.OP_INIT, CoreProtocol.encodeInit(CoreProtocol.InitArgs("a", "m", "t", "en")))
        val search = CoreProtocol.decodeReply(host.handle(CoreProtocol.OP_SEARCH, CoreProtocol.encodeSearch(CoreProtocol.SearchArgs("q", null, 5, "en", 100))))
        val (kind, message) = CoreProtocol.decodeError(search.bytes)
        assertEquals(CoreProtocol.ERR_INTERNAL, kind)
        assertTrue("secret" !in message) // only the exception type travels, never its message
        assertEquals(CoreProtocol.KIND_ERROR, CoreProtocol.decodeReply(host.handle(CoreProtocol.OP_ROUTE, byteArrayOf(1, 2, 3))).kind)
        assertEquals(CoreProtocol.KIND_ERROR, CoreProtocol.decodeReply(host.handle(77, ByteArray(0))).kind)
        assertEquals(CoreProtocol.KIND_ERROR, CoreProtocol.decodeReply(host.handle(CoreProtocol.OP_FETCH, CoreProtocol.encodeFetch(999, 0))).kind)
    }

    @Test fun `calls from several threads are serialised and all succeed`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        val done = CountDownLatch(8)
        val ok = AtomicInteger()
        repeat(8) { Thread { if (c.searchEngine().search("cafe").size == 1) ok.incrementAndGet(); done.countDown() }.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(8, ok.get())
        assertEquals(1, t.connects.get())
        assertEquals(1, t.bridge.inits.get())
    }

    @Test fun `changing the data directory restarts the core with the new arguments`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        c.searchEngine().search("cafe")
        c.init("a.apk", "/other-maps", "/tmp", "en")
        c.searchEngine().search("cafe")
        assertEquals(2, t.connects.get())
        assertEquals(2, t.bridge.inits.get())
    }
}
