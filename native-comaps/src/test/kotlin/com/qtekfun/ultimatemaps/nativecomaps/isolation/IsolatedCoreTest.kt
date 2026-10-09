package com.qtekfun.ultimatemaps.nativecomaps.isolation

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.nativecomaps.CoMapsCore
import com.qtekfun.ultimatemaps.nativecomaps.NativeBridge
import com.qtekfun.ultimatemaps.nativecomaps.RawGuidedRoute
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
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
        val categorySearches = AtomicInteger()
        val routes = AtomicInteger()
        @Volatile var routePoints = 2
        @Volatile var onRoute: () -> Unit = {}
        @Volatile var codeToReturn = 0
        @Volatile var altitudesPerPoint: ((Int) -> Double)? = null
        @Volatile var lastAvoidFlags = -1
        @Volatile var guidance: DoubleArray = DoubleArray(0)
        @Volatile var names: Array<String> = emptyArray()
        override fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String { inits.incrementAndGet(); return "" }
        override fun refreshMaps() = 7
        override fun search(query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String): Array<String> {
            searches.incrementAndGet()
            return arrayOf("Cafe Central", "Calle Mayor 1", "cafe", "40.4168", "-3.7038", "", "", "", "")
        }
        override fun searchCategory(query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String): Array<String> {
            categorySearches.incrementAndGet()
            return arrayOf("Farmacia Sol", "Calle Luna 2", "pharmacy", "40.42", "-3.70", "+34 911 111 111", "https://farmacia.example", "yes", "24/7")
        }
        override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray {
            routes.incrementAndGet()
            lastAvoidFlags = avoidFlags
            onRoute()
            if (codeToReturn != 0) return doubleArrayOf(codeToReturn.toDouble(), 0.0, 0.0)
            val n = routePoints
            val alt = altitudesPerPoint
            val raw = DoubleArray(3 + 2 * n + if (alt != null) n + 2 else 0)
            raw[1] = 1000.0 * n
            raw[2] = 60.0 * n
            for (i in 0 until n) { raw[3 + 2 * i] = 40.0 + i * 1e-5; raw[4 + 2 * i] = -3.0 + i * 1e-5 }
            if (alt != null) {
                // Same trailer as RouteFlat in um_jni.cpp: n heights, n, marker.
                for (i in 0 until n) raw[3 + 2 * n + i] = alt(i)
                raw[3 + 3 * n] = n.toDouble()
                raw[4 + 3 * n] = -7_777_777.0
            }
            return raw
        }
        override fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int) =
            RawGuidedRoute(route(profile, points, avoidFlags, timeoutSec), guidance, names)
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

    @Test fun `a category search crosses the process boundary as a category search`() {
        val t = FakeTransport(Bridge())
        val c = client(t)
        val r = c.searchEngine().searchCategory("pharmacy", a)
        assertEquals("Farmacia Sol", r.single().name)
        assertEquals("tel:+34911111111", com.qtekfun.ultimatemaps.core.search.PlaceExtras.dialUri(r.single().extras?.phone))
        assertEquals(com.qtekfun.ultimatemaps.core.search.Wheelchair.YES, r.single().extras?.wheelchair)
        assertEquals(1, t.bridge.categorySearches.get())
        assertEquals(0, t.bridge.searches.get())
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
            override fun init(apk: String, writableDir: String, tmpDir: String, locale: String) = "missing classificator"
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

    /** Wire guidance (see `GuidanceWire`): DEPART, then RIGHT onto "Calle Mayor" with two lanes, and a 50 km/h stretch. */
    private fun guidedBridge() = Bridge().apply {
        routePoints = 3
        names = arrayOf("Calle Mayor")
        guidance = doubleArrayOf(
            1.0, 2.0, 1.0,
            0.0, 0.0, -1.0, -1.0, 0.0,
            2.0, 3.0, -1.0, 0.0, 2.0, ((1 shl 3) or (1 shl 6)).toDouble(), 0.0, (1 shl 9).toDouble(), 1.0,
            0.0, 2.0, 50.0,
        )
    }

    @Test fun `guidance crosses the isolation boundary intact, and only when asked for`() {
        val c = client(FakeTransport(guidedBridge()))
        val g = c.routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b)).plan!!.guidance
        assertEquals(2, g.maneuvers.size)
        assertEquals(com.qtekfun.ultimatemaps.core.routing.TurnType.RIGHT, g.maneuvers[1].type)
        assertEquals("Calle Mayor", g.maneuvers[1].streetName)
        assertEquals(2, g.maneuvers[1].lanes.size)
        assertEquals(setOf(com.qtekfun.ultimatemaps.core.routing.LaneDirection.RIGHT), g.maneuvers[1].lanes[1].directions)
        assertTrue(g.maneuvers[1].lanes[1].recommended && !g.maneuvers[1].lanes[0].recommended)
        assertEquals(listOf(com.qtekfun.ultimatemaps.core.routing.SpeedLimit(0, 2, 50)), g.speedLimits)
        // Without the flag the plain route is asked for: no guidance, same as before.
        assertEquals(com.qtekfun.ultimatemaps.core.routing.RouteGuidance.EMPTY, c.routingEngine().routeDetailed(RouteRequest(a, b)).plan!!.guidance)
    }

    @Test fun `a guided route with a large guidance is chunked under the Binder limit`() {
        val n = 6_000
        val bridge = Bridge().apply {
            routePoints = n
            names = arrayOf("Calle de la Gran Via de Colon")
            val per = 5 + 2 * 2
            val g = DoubleArray(3 + n / 2 * per)
            g[0] = 1.0; g[1] = (n / 2).toDouble(); g[2] = 0.0
            for (i in 0 until n / 2) {
                val o = 3 + i * per
                g[o] = (2 * i).toDouble(); g[o + 1] = 3.0; g[o + 2] = -1.0; g[o + 3] = 0.0; g[o + 4] = 2.0
                g[o + 5] = (1 shl 6).toDouble(); g[o + 6] = 0.0; g[o + 7] = (1 shl 9).toDouble(); g[o + 8] = 1.0
            }
            guidance = g
        }
        val t = FakeTransport(bridge, maxTransaction = 250_000)
        val plan = client(t).routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b)).plan!!
        assertEquals(n, plan.geometry.size)
        assertEquals(n / 2, plan.guidance.maneuvers.size)
        assertEquals(0, t.tooLarge.get())
    }

    // ---- a 500 km route (Leganes to Motril) with every trailer the wire has grown: the bug report that motivated these ----

    /** Guidance wire for [points] points: exit-ramp maneuvers with exit data, speed limits, tunnels, and the exit section. */
    private fun longGuidance(points: Int, maneuvers: Int): DoubleArray {
        val g = ArrayList<Double>()
        fun add(vararg v: Number) = v.forEach { g += it.toDouble() }
        val limits = 1_000
        val tunnels = 300
        add(1, maneuvers, limits)
        for (i in 0 until maneuvers) {
            val exit = i % 10 == 5
            add((i + 1) * (points - 2) / (maneuvers + 1), if (exit) 13 else 3, -1, i % 50, 0) // index, turn, roundabout exit, name, no lanes
        }
        for (i in 0 until limits) add(i * (points - 2) / limits, (i + 1) * (points - 2) / limits, 50 + (i % 8) * 10)
        add(tunnels)
        for (i in 0 until tunnels) add(i * (points - 2) / tunnels, i * (points - 2) / tunnels + 5)
        val exits = (0 until maneuvers).filter { it % 10 == 5 }
        add(exits.size)
        for (m in exits) add(m, 50 + m % 7, 60, 61)
        return g.toDoubleArray()
    }

    @Test fun `a 500 km guided route with heights, tunnels and exit data crosses the boundary intact and in chunks`() {
        val n = 60_000
        val bridge = Bridge().apply {
            routePoints = n
            altitudesPerPoint = { i -> if (i % 17 == 0) -32768.0 else 600.0 + (i % 400) / 4.0 }
            names = Array(62) { "Autovia del Sur $it" }
            guidance = longGuidance(n, 2_000)
        }
        val t = FakeTransport(bridge, maxTransaction = 250_000)
        val outcome = client(t).routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b))
        val plan = outcome.plan
        assertNotNull(plan, "guidanceError=${outcome.guidanceError} code=${outcome.code}")
        assertNull(outcome.guidanceError)
        assertEquals(n, plan.geometry.size)
        assertEquals(n, plan.altitudes.size)
        assertTrue(plan.altitudes[0].isNaN() && plan.altitudes[17].isNaN())
        assertEquals(600.0, plan.altitudes[1]) // whole metres on the wire
        assertEquals(2_000, plan.guidance.maneuvers.size)
        assertEquals(1_000, plan.guidance.speedLimits.size)
        assertEquals(300, plan.guidance.tunnels.size)
        val withExit = plan.guidance.maneuvers.filter { it.towardRef != null }
        assertEquals(200, withExit.size)
        assertEquals("Autovia del Sur 60", withExit.first().towardRef)
        assertEquals(0, t.tooLarge.get())
        // Size of what crossed: the wire must stay far below the sanity limit of the protocol.
        val encoded = CoreProtocol.encodeOutcome(outcome)
        assertTrue(encoded.size < 3_000_000, "encoded plan is ${encoded.size} bytes")
        assertTrue(encoded.size > CoreProtocol.MAX_INLINE) // so it really was chunked
    }

    @Test fun `avoid tolls and the other options reach the native side as the same bits the C++ constants have`() {
        val bridge = Bridge()
        val c = client(FakeTransport(bridge))
        val toll = com.qtekfun.ultimatemaps.core.routing.RouteOptions(avoidTolls = true)
        c.routingEngine().routeDetailed(RouteRequest(a, b, options = toll))
        assertEquals(2, bridge.lastAvoidFlags) // um::kAvoidToll = 1 << 1
        val all = com.qtekfun.ultimatemaps.core.routing.RouteOptions(true, true, true, true)
        c.routingEngine().routeDetailed(RouteRequest(a, b, options = all))
        assertEquals(15, bridge.lastAvoidFlags)
        assertEquals(all, com.qtekfun.ultimatemaps.core.routing.RouteOptions.fromFlags(bridge.lastAvoidFlags))
    }

    @Test fun `every router result code crosses the boundary as a result, never as an exception`() {
        val bridge = Bridge()
        val c = client(FakeTransport(bridge))
        for (code in listOf(1, 2, 3, 4, 5, 6, 7, 8, RouteCode.NEED_MORE_MAPS, 10, 11, 12, 13, 14, 15, 1004, 99_999, -3)) {
            bridge.codeToReturn = code
            val outcome = c.routingEngine().routeDetailed(RouteRequest(a, b))
            assertEquals(code, outcome.code, "code $code")
            assertNull(outcome.plan, "code $code")
        }
    }

    /** A connection that answers every call with the given reply (or throws), to probe how the client copes with nonsense. */
    private fun scripted(reply: (op: Int) -> ByteArray) = CoreTransport {
        object : CoreConnection {
            override val isAlive = true
            override fun call(op: Int, payload: ByteArray) = reply(op)
            override fun kill() = Unit
            override fun close() = Unit
        }
    }

    @Test fun `a route reply that is garbage is a failed route, not an exception in the caller`() {
        val ok = CoreProtocol.encodeReply(CoreProtocol.Reply(CoreProtocol.KIND_OK, ByteArray(0)))
        val garbage = CoreProtocol.encodeReply(CoreProtocol.Reply(CoreProtocol.KIND_OK, ByteArray(300) { (it * 31).toByte() }))
        val c = IsolatedCore(scripted { op -> if (op == CoreProtocol.OP_ROUTE) garbage else ok }, fast())
        c.init("a.apk", "/maps", "/tmp", "en")
        val outcome = c.routingEngine().routeDetailed(RouteRequest(a, b))
        assertNull(outcome.plan)
        assertEquals(RouteCode.CORE_INTERNAL, outcome.code)
    }

    @Test fun `a plan whose altitude count disagrees with its geometry is refused cleanly`() {
        val bridge = Bridge().apply { routePoints = 50 }
        val host = CoreHost(CoMapsCore(bridge))
        // Hand-built outcome with 50 points and 7 altitudes (what a mismatch between the two native lists would produce).
        val bad = CoreProtocol.encodeOutcome(
            com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome(
                0,
                com.qtekfun.ultimatemaps.core.routing.RoutePlan(List(50) { LatLon(40.0 + it * 1e-4, -3.0) }, 1.0, 1.0, altitudes = List(7) { 600.0 }),
            ),
        )
        assertFailsWith<java.io.IOException> { CoreProtocol.decodeOutcome(bad) }
        // And through the client, as a reply the host would have sent.
        val reply = CoreProtocol.encodeReply(CoreProtocol.Reply(CoreProtocol.KIND_OK, bad))
        val ok = CoreProtocol.encodeReply(CoreProtocol.Reply(CoreProtocol.KIND_OK, ByteArray(0)))
        val c = IsolatedCore(scripted { op -> if (op == CoreProtocol.OP_ROUTE) reply else ok }, fast())
        c.init("a.apk", "/maps", "/tmp", "en")
        val outcome = c.routingEngine().routeDetailed(RouteRequest(a, b))
        assertNull(outcome.plan)
        assertEquals(RouteCode.CORE_INTERNAL, outcome.code)
        host.parkedCount // the host is untouched
    }

    @Test fun `an out of memory error inside the host is an error reply, not a dead binder thread`() {
        val bridge = object : NativeBridge by Bridge() {
            override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray = throw OutOfMemoryError("huge")
        }
        val host = CoreHost(CoMapsCore(bridge))
        host.handle(CoreProtocol.OP_INIT, CoreProtocol.encodeInit(CoreProtocol.InitArgs("a", "m", "t", "en")))
        val raw = host.handle(CoreProtocol.OP_ROUTE, CoreProtocol.encodeRoute(CoreProtocol.RouteArgs(0, doubleArrayOf(40.0, -3.0, 40.1, -3.1), 2, 1, false)))
        assertEquals(CoreProtocol.KIND_ERROR, raw[0].toInt())
    }

    @Test fun `the client tells its observer, in fixed words, when the core dies`() {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val t = FakeTransport(Bridge())
        t.dieOnceOnOp = CoreProtocol.OP_ROUTE
        val c = IsolatedCore(t, fast(), onEvent = { events += it })
        c.init("a.apk", "/maps", "/tmp", "en")
        assertNotNull(c.routingEngine().routeDetailed(RouteRequest(a, b)).plan)
        assertEquals(1, events.size)
        assertTrue(events.single().startsWith("core process died"), events.toString())
    }
}
