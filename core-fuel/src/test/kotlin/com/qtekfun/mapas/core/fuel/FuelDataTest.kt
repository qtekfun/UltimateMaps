package com.qtekfun.mapas.core.fuel

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelDataTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/fuel/$name")!!.readBytes()

    private fun product(vararg stations: String) =
        """{"Fecha":"07/10/2026 12:00:00","ListaEESSPrecio":[${stations.joinToString(",")}],"Nota":"x","ResultadoConsulta":"OK"}"""

    private fun st(id: String, lat: String, lon: String, price: String) =
        """{"IDEESS":"$id","Rótulo":"R$id","Dirección":"C/ $id","Municipio":"M","Provincia":"P","Latitud":"$lat","Longitud (WGS84)":"$lon","Horario":"L-D: 24H","PrecioProducto":"$price"}"""

    // ---- parser ----

    @Test fun parsesNationalExtractForOneFuelWithBomAndDecimalComma() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + resource("combustible-extracto.json")
        val glp = FuelFeedParser.parse(bytes.inputStream(), FuelTypes.LPG)
        assertEquals(listOf("ANON1", "ANON2"), glp.stations.map { it.id })
        assertEquals(1.154, glp.stations[0].price, 1e-9)
        assertEquals(4, glp.skipped) // stations without that price
        val g95 = FuelFeedParser.parse(bytes.inputStream(), FuelTypes.G95_E5)
        assertEquals(4, g95.stations.size)
        assertEquals(39.0, g95.stations[0].location.lat, 1e-9)
        assertEquals("07/10/2026 12:02:03", g95.serviceDate)
    }

    @Test fun parsesProductShapeAndIgnoresBadEntries() {
        val json = product(
            st("1", "40,5", "-3,7", "1,849"),
            st("2", "40,5", "-3,7", ""),        // empty price
            st("3", "abc", "-3,7", "1,5"),      // bad latitude
            st("4", "40,5", "-3,7", "-2"),      // negative price
            """{"IDEESS":"5","Latitud":"41,0","Longitud (WGS84)":"-2,0","PrecioProducto":1.5,"Extra":{"a":[1,2,{"b":null}]},"Nuevo":true}""",
        )
        val feed = FuelFeedParser.parse(json)
        assertEquals(listOf("1", "5"), feed.stations.map { it.id })
        assertEquals(3, feed.skipped)
        assertEquals(1.5, feed.stations[1].price, 1e-9)
    }

    @Test fun truncatedAndGarbageAreRejected() {
        val full = product(st("1", "40,5", "-3,7", "1,849"), st("2", "40,6", "-3,7", "1,9"))
        for (cut in listOf(10, full.length / 2, full.length - 30, full.length - 1)) {
            assertFailsWith<FuelParseException>("cut at $cut") { FuelFeedParser.parse(full.substring(0, cut)) }
        }
        assertFailsWith<FuelParseException> { FuelFeedParser.parse("<html>error</html>") }
        assertFailsWith<FuelParseException> { FuelFeedParser.parse("""{"ResultadoConsulta":"KO","ListaEESSPrecio":[]}""") }
        assertFailsWith<FuelParseException> { FuelFeedParser.parse("""{"Fecha":"x"}""") }
        // entries present but none usable: the format probably changed
        assertFailsWith<FuelParseException> { FuelFeedParser.parse("""{"ListaEESSPrecio":[{"foo":"bar"}],"ResultadoConsulta":"OK"}""") }
        // an empty list is a valid answer (a fuel nobody sells)
        assertEquals(0, FuelFeedParser.parse("""{"ListaEESSPrecio":[],"ResultadoConsulta":"OK"}""").stations.size)
    }

    @Test fun decimalParsing() {
        assertEquals(1.849, FuelFeedParser.parseDecimal("1,849"))
        assertEquals(1234.5, FuelFeedParser.parseDecimal("1.234,5"))
        assertEquals(-1.539167, FuelFeedParser.parseDecimal(" -1,539167 "))
        assertNull(FuelFeedParser.parseDecimal(""))
        assertNull(FuelFeedParser.parseDecimal("NaN"))
        assertNull(FuelFeedParser.parseDecimal(null))
    }

    @Test fun catalogHasRealIdsAndUniqueKeys() {
        assertEquals(17, FuelTypes.LPG.sourceProductId)
        assertEquals(18, FuelTypes.CNG.sourceProductId)
        assertEquals(19, FuelTypes.LNG.sourceProductId)
        assertEquals(1, FuelTypes.G95_E5.sourceProductId)
        assertEquals(FuelTypes.all.size, FuelTypes.all.map { it.id }.toSet().size)
        assertEquals(FuelTypes.all.size, FuelTypes.all.map { it.sourceProductId }.toSet().size)
        assertTrue(FuelTypes.all.all { FuelTypes.nationalField(it.id) != null })
    }

    // ---- index and cache ----

    private fun data(fuel: FuelType, at: Long, vararg s: RawStation) = FuelData(fuel.id, at, null, s.toList())
    private fun raw(id: String, lat: Double, lon: Double, price: Double) =
        RawStation(id, "B$id", "addr", "muni", "prov", LatLon(lat, lon), "L-D: 24H", price)

    @Test fun snapshotJoinsFuelsByStationAndQueriesGrid() {
        val snap = FuelSnapshot.build(
            listOf(
                data(FuelTypes.LPG, 100, raw("a", 40.0, -3.0, 0.9), raw("b", 40.2, -3.1, 0.8), raw("far", 43.0, -8.0, 0.1)),
                data(FuelTypes.G95_E5, 200, raw("a", 40.0, -3.0, 1.8)),
            ),
        )
        val a = snap.station("a")!!
        assertEquals(mapOf("glp" to 0.9, "g95e5" to 1.8), a.prices)
        val box = LatLonBounds(39.5, -3.5, 40.5, -2.5)
        assertEquals(listOf("b", "a"), snap.stationsIn(box, FuelTypes.LPG, 10).map { it.id })
        assertEquals(listOf("b"), snap.stationsIn(box, FuelTypes.LPG, 1).map { it.id })
        assertEquals(listOf("a"), snap.stationsIn(box, FuelTypes.G95_E5, 10).map { it.id })
        assertEquals(emptyList(), snap.stationsIn(box, FuelTypes.CNG, 10))
        // whole of Spain: more cells than the index has -> iterates the index
        assertEquals(3, snap.stationsIn(LatLonBounds(27.0, -19.0, 44.0, 5.0), FuelTypes.LPG, 10).size)
        assertEquals(emptyList(), snap.stationsIn(LatLonBounds(10.0, 0.0, 11.0, 1.0), FuelTypes.LPG, 10))
    }

    @Test fun cacheRoundTripsAndRejectsDamage() {
        val dir = Files.createTempDirectory("fuelcache").toFile()
        val cache = FuelCache(dir)
        val d = FuelData("glp", 12345L, "07/10/2026", listOf(raw("a", 40.0, -3.0, 0.919), raw("b", 41.1, -2.2, 1.0)))
        cache.write(d)
        assertEquals(d, cache.read("glp"))
        assertNull(cache.read("gnc"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        val f = File(dir, "fuel-glp.bin")
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 20)) // truncated
        assertNull(cache.read("glp"))
        cache.write(d)
        val flipped = f.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        f.writeBytes(flipped) // damaged
        assertNull(cache.read("glp"))
    }

    // ---- client and manager against a local server ----

    private class Server(val handler: (path: String, count: Int) -> Triple<Int, String, Map<String, String>>) {
        val hits = AtomicInteger()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex ->
                paths += ex.requestURI.toString()
                val (code, body, headers) = handler(ex.requestURI.path, hits.incrementAndGet())
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val bytes = body.toByteArray()
                if (code == 599) { // announce more than we send, then drop the connection
                    ex.sendResponseHeaders(200, (bytes.size + 1000).toLong())
                    ex.responseBody.write(bytes); ex.responseBody.flush(); ex.close(); return@createContext
                }
                if (body == "SLOW") { Thread.sleep(1500) }
                ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
                ex.close()
            }
            s.start()
        }
        val base get() = "http://127.0.0.1:${http.address.port}/PreciosCarburantes/"
        fun stop() = http.stop(0)
    }

    private fun policy() = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", FUEL_PURPOSE, enabled = true)))

    @Test fun clientRequestsOneFilePerFuelWithNoLocation() {
        val srv = Server { _, _ -> Triple(200, product(st("1", "40,5", "-3,7", "1,1")), emptyMap()) }
        try {
            val c = FuelClient(policy(), allowInsecure = true)
            assertEquals(1, c.fetch(srv.base, FuelTypes.LPG).stations.size)
            assertEquals(listOf("/PreciosCarburantes/EstacionesTerrestres/FiltroProducto/17"), srv.paths)
            assertTrue(srv.paths.none { it.contains("?") || it.contains("Filtro" + "Provincia") || it.contains("Municipio") })
        } finally { srv.stop() }
    }

    @Test fun clientFailureKinds() {
        fun kind(handler: (String, Int) -> Triple<Int, String, Map<String, String>>, client: (DefaultNetworkPolicy) -> FuelClient = { FuelClient(it, allowInsecure = true) }, pol: DefaultNetworkPolicy = policy()): FuelFailure {
            val srv = Server(handler)
            try { return assertFailsWith<FuelException> { client(pol).fetch(srv.base, FuelTypes.LPG) }.failure } finally { srv.stop() }
        }
        assertEquals(FuelFailure.SERVER, kind({ _, _ -> Triple(500, "boom", emptyMap()) }))
        assertEquals(FuelFailure.NETWORK, kind({ _, _ -> Triple(404, "no", emptyMap()) }))
        assertEquals(FuelFailure.INVALID_DATA, kind({ _, _ -> Triple(200, product(st("1", "40,5", "-3,7", "1,1")).dropLast(40), emptyMap()) }))
        assertTrue(kind({ _, _ -> Triple(599, product(st("1", "40,5", "-3,7", "1,1")).dropLast(40), emptyMap()) }) in setOf(FuelFailure.INVALID_DATA, FuelFailure.NETWORK)) // connection dropped mid-body
        assertEquals(FuelFailure.TOO_LARGE, kind({ _, _ -> Triple(200, product(st("1", "40,5", "-3,7", "1,1")), emptyMap()) }, { FuelClient(it, allowInsecure = true, maxBytes = 100) }))
        assertEquals(FuelFailure.TIMEOUT, kind({ _, _ -> Triple(200, "SLOW", emptyMap()) }, { FuelClient(it, allowInsecure = true, readTimeoutMs = 300) }))
        // redirect to a host that is not authorized
        assertEquals(FuelFailure.NOT_ALLOWED, kind({ _, _ -> Triple(302, "", mapOf("Location" to "http://localhost:1/x")) }))
        // offline mode: zero connections
        val off = policy().also { it.offlineMode = true }
        val srv = Server { _, _ -> Triple(200, product(), emptyMap()) }
        try {
            val e = assertFailsWith<FuelException> { FuelClient(off, allowInsecure = true).fetch(srv.base, FuelTypes.LPG) }
            assertEquals(FuelFailure.OFFLINE_MODE, e.failure)
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
        // https is mandatory without the test flag
        assertEquals(FuelFailure.NOT_ALLOWED, kind({ _, _ -> Triple(200, product(), emptyMap()) }, { FuelClient(it) }))
    }

    private fun lenientHost(url: String) = runCatching { java.net.URI(url).host }.getOrNull()

    private inner class Env(val srv: Server, settings: FuelSettings, val clock: () -> Long = System::currentTimeMillis) {
        val dir: File = Files.createTempDirectory("fuelmgr").toFile()
        val store = InMemoryFuelSettingsStore(settings, normalize = false)
        val policy = DefaultNetworkPolicy()
        val manager = FuelDataManager(
            store, policy, policy::addEndpoint, policy::removeEndpoint, FuelCache(dir),
            FuelClient(policy, allowInsecure = true), clock, io = Dispatchers.Unconfined, hostOf = ::lenientHost,
        )
    }

    private fun settings(vararg fuels: FuelType) = FuelSettings(enabled = true, downloadedFuels = fuels.map { it.id }.toSet(), sourceUrl = "https://127.0.0.1:1/x/")

    @Test fun managerFetchesPerFuelKeepsLastGoodDataAndIsolatesFailures() = runBlocking {
        var failGnc = false
        val srv = Server { path, _ ->
            when {
                path.endsWith("/17") -> Triple(200, product(st("a", "40,0", "-3,0", "0,9"), st("b", "40,1", "-3,1", "0,8")), emptyMap())
                path.endsWith("/18") -> if (failGnc) Triple(500, "x", emptyMap()) else Triple(200, product(st("a", "40,0", "-3,0", "1,5")), emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        try {
            var now = 1_000_000L
            val env = Env(srv, settings(FuelTypes.LPG, FuelTypes.CNG).copy(sourceUrl = srv.base), clock = { now })
            // nothing happens before an explicit refresh
            env.manager.start(); Thread.sleep(200)
            assertEquals(0, srv.hits.get())
            val host = "127.0.0.1"
            assertTrue(env.policy.possibleConnections().any { it.host == host })
            val out = env.manager.refresh(FuelTrigger.ENABLED)
            assertTrue(out.all { it.failure == null })
            assertEquals(2, srv.hits.get())
            val box = LatLonBounds(39.0, -4.0, 41.0, -2.0)
            assertEquals(listOf("b", "a"), env.manager.repository.stationsIn(box, FuelTypes.LPG).map { it.id })
            assertEquals(mapOf("glp" to 0.9, "gnc" to 1.5), env.manager.repository.station("a")!!.prices)
            assertEquals(now, env.manager.repository.lastUpdateMillis.value)
            // within the TTL a foreground refresh does nothing
            now += 10 * 60_000
            assertEquals(emptyList(), env.manager.refresh(FuelTrigger.FOREGROUND))
            assertEquals(2, srv.hits.get())
            // past the TTL: GNC fails, LPG still refreshes; GNC keeps its last good data
            now += 100 * 60_000
            failGnc = true
            val out2 = env.manager.refresh(FuelTrigger.FOREGROUND)
            assertEquals(FuelFailure.SERVER, out2.single { it.fuelId == "gnc" }.failure)
            assertNull(out2.single { it.fuelId == "glp" }.failure)
            assertEquals(1.5, env.manager.repository.station("a")!!.prices["gnc"])
            // the failed fuel is not hammered by another foreground right away
            val hits = srv.hits.get()
            env.manager.refresh(FuelTrigger.FOREGROUND)
            assertEquals(hits, srv.hits.get())
            // a new manager opens with no network from the cache
            val srv2hits = srv.hits.get()
            val policy2 = DefaultNetworkPolicy()
            val m2 = FuelDataManager(env.store, policy2, policy2::addEndpoint, policy2::removeEndpoint, FuelCache(env.dir), FuelClient(policy2, allowInsecure = true), { now }, io = Dispatchers.Unconfined, hostOf = ::lenientHost)
            m2.start(); Thread.sleep(300)
            assertEquals(2, m2.repository.stationsIn(box, FuelTypes.LPG).size)
            assertEquals(srv2hits, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun enablingAndDisablingMovesTheHostInThePolicy() = runBlocking {
        val srv = Server { _, _ -> Triple(200, product(st("a", "40,0", "-3,0", "0,9")), emptyMap()) }
        try {
            val env = Env(srv, FuelSettings(enabled = false, downloadedFuels = setOf("glp"), sourceUrl = srv.base))
            env.manager.start(); Thread.sleep(200)
            assertTrue(env.policy.possibleConnections().isEmpty())
            assertEquals(emptyList(), env.manager.refresh(FuelTrigger.USER)) // disabled: nothing
            assertEquals(0, srv.hits.get())
            env.store.update { it.copy(enabled = true) }; Thread.sleep(300)
            assertEquals(listOf("127.0.0.1"), env.policy.possibleConnections().map { it.host })
            assertEquals(1, env.manager.refresh(FuelTrigger.ENABLED).size)
            assertEquals(1, env.manager.repository.stationsIn(LatLonBounds(39.0, -4.0, 41.0, -2.0), FuelTypes.LPG).size)
            // host withdrawn: connection denied and nothing listed; with the feature off the data is not served
            env.store.update { it.copy(enabled = false) }; Thread.sleep(300)
            assertTrue(env.policy.possibleConnections().isEmpty())
            assertEquals(emptyList(), env.manager.repository.stationsIn(LatLonBounds(39.0, -4.0, 41.0, -2.0), FuelTypes.LPG))
            assertNotNull(env.policy.authorize("127.0.0.1", FUEL_PURPOSE).takeIf { !it.isAllowed })
            val hits = srv.hits.get()
            val direct = assertFailsWith<FuelException> { FuelClient(env.policy, allowInsecure = true).fetch(srv.base, FuelTypes.LPG) }
            assertEquals(FuelFailure.NOT_ALLOWED, direct.failure)
            assertEquals(hits, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun settingsNormalizeAndAttribution() {
        val s = FuelSettings(enabled = true, downloadedFuels = setOf("glp", "nope"), mapFuel = "gnc", refreshMinutes = 5, sourceUrl = "http://x").normalized()
        assertEquals(setOf("glp"), s.downloadedFuels)
        assertEquals("glp", s.mapFuel)
        assertEquals(30, s.refreshMinutes)
        assertEquals(FuelSettings.DEFAULT_SOURCE_URL, s.sourceUrl)
        val t = FuelAttribution.text(0L, zone = java.time.ZoneId.of("UTC"))
        assertTrue(t.contains("Ley 37/2007") && t.contains("01/01/1970 00:00") && !t.contains("oficial.") )
        assertTrue(FuelAttribution.text(null, english = true).contains("Not downloaded"))
    }
}
