package com.qtekfun.ultimatemaps.core.weather

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "eyJhbGciOiJIUzI1NiJ9.aaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbb"

/** A policy that fails the test on ANY call: used where nothing at all may be asked. */
private object ForbiddenPolicy : NetworkPolicy {
    override var offlineMode: Boolean = false
    override fun authorize(host: String, purpose: ConnectionPurpose): NetworkDecision = error("the network policy was called for $host")
    override fun possibleConnections(): List<AllowedEndpoint> = error("no")
    override fun setEndpointEnabled(host: String, purpose: ConnectionPurpose, enabled: Boolean) = error("no")
    override fun recentConnections() = error("no")
    override fun clearLog() = error("no")
}

class WeatherAlertRepositoryTest {
    private var now = OffsetDateTime.parse("2026-10-08T10:00:00+02:00").toInstant().toEpochMilli()
    private val min = 60_000L
    private var on = true
    private var key: String? = KEY
    private val calls = CopyOnWriteArrayList<String>()
    private var next: () -> List<ByteArray> = { listOf(CapFixtures.doc().toByteArray()) }

    private val source = AemetSource { k -> calls += k; next() }
    private fun repo(src: AemetSource = source, lang: String = "es") =
        WeatherAlertRepository(src, { on }, { key }, { now }, { lang })

    @Test fun `switch off - no policy call, no request, empty index`() {
        on = false
        val r = WeatherAlertRepository(HttpAemetSource(ForbiddenPolicy), { on }, { key }, { now })
        assertEquals(WeatherRefresh.Disabled, r.refresh(force = true))
        assertEquals(WeatherRefresh.Disabled, r.refresh(force = false))
        assertTrue(r.index().warnings.isEmpty())
    }

    @Test fun `empty or missing key - no policy call, no request`() {
        val r = WeatherAlertRepository(HttpAemetSource(ForbiddenPolicy), { on }, { key }, { now })
        key = null
        assertEquals(WeatherRefresh.NoKey, r.refresh(true))
        key = "   "
        assertEquals(WeatherRefresh.NoKey, r.refresh(true))
        key = ""
        assertEquals(WeatherRefresh.NoKey, r.refresh(false))
    }

    @Test fun `fetch, then the cache answers inside the TTL`() {
        val r = repo()
        assertEquals(WeatherRefresh.Updated, r.refresh(false))
        assertEquals(1, r.index().warnings.size)
        now += 29 * min
        assertEquals(WeatherRefresh.Fresh, r.refresh(false))
        assertEquals(1, calls.size)
    }

    @Test fun `after the TTL the automatic refresh asks again`() {
        val r = repo()
        r.refresh(false)
        now += 31 * min
        assertEquals(WeatherRefresh.Updated, r.refresh(false))
        assertEquals(2, calls.size)
    }

    @Test fun `Check now skips the TTL but never the 15 minute floor`() {
        val r = repo()
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        now += 5 * min
        assertEquals(WeatherRefresh.TooSoon, r.refresh(true))
        now += 9 * min
        assertEquals(WeatherRefresh.TooSoon, r.refresh(true))
        now += 2 * min
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        assertEquals(2, calls.size)
    }

    @Test fun `a failed attempt also counts for the floor and keeps the old data`() {
        val r = repo()
        r.refresh(false)
        now += 31 * min
        next = { throw AemetException(WeatherFailure.NETWORK, "down") }
        assertEquals(WeatherRefresh.Failed(WeatherFailure.NETWORK), r.refresh(false))
        assertEquals(WeatherRefresh.TooSoon, r.refresh(true))
        assertEquals(2, calls.size)
        assertEquals(1, r.index().warnings.size, "the previous data stays")
    }

    @Test fun `401 or 403 stop everything until the key changes`() {
        val r = repo()
        next = { throw AemetException(WeatherFailure.UNAUTHORIZED, "no") }
        assertEquals(WeatherRefresh.KeyRejected, r.refresh(true))
        now += 60 * min
        assertEquals(WeatherRefresh.KeyRejected, r.refresh(true))
        assertEquals(1, calls.size, "not asked again with the same key")
        key = KEY + "x"
        r.keyChanged()
        next = { listOf(CapFixtures.doc().toByteArray()) }
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
    }

    @Test fun `429 waits an hour`() {
        val r = repo()
        next = { throw AemetException(WeatherFailure.RATE_LIMITED, "slow") }
        assertEquals(WeatherRefresh.Failed(WeatherFailure.RATE_LIMITED), r.refresh(true))
        next = { listOf(CapFixtures.doc().toByteArray()) }
        now += 20 * min
        assertEquals(WeatherRefresh.TooSoon, r.refresh(true))
        now += 41 * min
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
    }

    @Test fun `offline is silent`() {
        val r = repo()
        next = { throw AemetException(WeatherFailure.OFFLINE, "offline") }
        assertEquals(WeatherRefresh.Failed(WeatherFailure.OFFLINE), r.refresh(true))
        assertTrue(r.index().warnings.isEmpty())
    }

    @Test fun `unreadable documents are a failure, a partly readable bundle is used`() {
        val r = repo()
        next = { listOf("garbage".toByteArray()) }
        assertEquals(WeatherRefresh.Failed(WeatherFailure.INVALID), r.refresh(true))
        now += 16 * min
        next = { listOf("garbage".toByteArray(), CapFixtures.doc().toByteArray()) }
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        assertEquals(1, r.index().warnings.size)
    }

    @Test fun `no warnings in force is a success with an empty index`() {
        val r = repo()
        next = { emptyList() }
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        assertTrue(r.index().warnings.isEmpty())
    }

    @Test fun `data older than three hours is hidden, and turning the switch off hides it at once`() {
        val r = repo()
        r.refresh(true)
        // the fixture warning ends at 18:00 = 8 hours after "now"; staleness is what hides it here
        now += 3 * 60 * min + 1
        assertTrue(r.index().warnings.isEmpty())
        now -= 3 * 60 * min + 1
        assertEquals(1, r.index().warnings.size)
        on = false
        assertTrue(r.index().warnings.isEmpty())
    }

    @Test fun `the index follows the device language`() {
        var lang = "es"
        val r = WeatherAlertRepository(source, { on }, { key }, { now }, { lang })
        r.refresh(true)
        assertEquals("Vientos", r.index().warnings.single().event)
        lang = "en"
        assertEquals("Wind", r.index().warnings.single().event)
    }

    @Test fun `clear forgets the data and the wait`() {
        val r = repo()
        r.refresh(true)
        r.clear()
        assertTrue(r.index().warnings.isEmpty())
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        assertNull(WeatherStatus().lastResult)
    }
}

/** The real two-step source against a local server: what is sent, to whom, and how failures are named. */
class HttpAemetSourceTest {
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, List<String>>>>()
    private var envelopeStatus = 200
    private var envelope: (String) -> String = { base -> """{"descripcion":"exito","estado":200,"datos":"$base/data/abc","metadatos":"$base/meta/abc"}""" }
    private var dataStatus = 200
    private var data: ByteArray = CapFixtures.tar(listOf("a.xml" to CapFixtures.doc().toByteArray()))
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/avisos_cap/ultimoelaborado/area/esp") { ex ->
            requests += (ex.requestURI.toString() to ex.requestHeaders.toMap())
            val body = envelope(base()).toByteArray()
            ex.sendResponseHeaders(envelopeStatus, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/data/abc") { ex ->
            requests += (ex.requestURI.toString() to ex.requestHeaders.toMap())
            ex.sendResponseHeaders(dataStatus, if (dataStatus == 200) data.size.toLong() else -1)
            if (dataStatus == 200) ex.responseBody.use { it.write(data) } else ex.close()
        }
        start()
    }
    private fun base(): String = "http://127.0.0.1:${server.address.port}"
    private val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.WEATHER_ALERTS, true)))
    private fun source(p: NetworkPolicy = policy) = HttpAemetSource(p, baseUrl = "${base()}/api", allowInsecure = true)

    @AfterTest fun stop() = server.stop(0)

    @Test fun `two requests, the key only in a header of the first, nothing else about the user`() {
        val docs = source().fetchNational(KEY)
        assertEquals(1, docs.size)
        assertEquals(2, requests.size)
        val (firstUrl, first) = requests[0]
        val (secondUrl, second) = requests[1]
        assertEquals("/api/avisos_cap/ultimoelaborado/area/esp", firstUrl, "no query: no position, no area, no key in the URL")
        assertEquals("/data/abc", secondUrl)
        assertEquals(listOf(KEY), first.mapKeys { it.key.lowercase() }["api_key"])
        assertFalse("api_key" in second.mapKeys { it.key.lowercase() }, "the key is not sent to the data URL")
        for (h in listOf(first, second).map { it.mapKeys { e -> e.key.lowercase() } }) {
            assertFalse("cookie" in h); assertFalse("authorization" in h); assertFalse("referer" in h)
            assertEquals(listOf(HttpAemetSource.GENERIC_USER_AGENT), h["user-agent"])
        }
        // the policy saw the host and the purpose only
        val rec = policy.recentConnections()
        assertEquals(2, rec.size)
        assertTrue(rec.all { it.host == "127.0.0.1" && it.purpose == ConnectionPurpose.WEATHER_ALERTS && it.allowed })
    }

    @Test fun `401, 403 and 429 are named as such`() {
        envelopeStatus = 401
        assertEquals(WeatherFailure.UNAUTHORIZED, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        envelopeStatus = 403
        assertEquals(WeatherFailure.UNAUTHORIZED, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        envelopeStatus = 429
        assertEquals(WeatherFailure.RATE_LIMITED, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        envelopeStatus = 503
        assertEquals(WeatherFailure.SERVER, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
    }

    @Test fun `the estado field of a 200 answer is honoured`() {
        envelope = { """{"descripcion":"Unauthorized","estado":401}""" }
        assertEquals(WeatherFailure.UNAUTHORIZED, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        envelope = { """{"descripcion":"No hay datos","estado":404}""" }
        assertTrue(source().fetchNational(KEY).isEmpty())
        envelope = { "<html>not json</html>" }
        assertEquals(WeatherFailure.INVALID, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        envelope = { """{"estado":200}""" }
        assertEquals(WeatherFailure.INVALID, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
    }

    @Test fun `a datos URL on another host is never followed`() {
        envelope = { """{"estado":200,"datos":"http://localhost:1/x"}""" }
        assertEquals(WeatherFailure.INVALID, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        assertEquals(1, requests.size)
    }

    @Test fun `offline mode and a disabled host stop it before any connection`() {
        val off = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.WEATHER_ALERTS, true)), offlineMode = true)
        assertEquals(WeatherFailure.OFFLINE, assertFailsWith<AemetException> { source(off).fetchNational(KEY) }.failure)
        val disabled = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.WEATHER_ALERTS, false)))
        assertEquals(WeatherFailure.NOT_ALLOWED, assertFailsWith<AemetException> { source(disabled).fetchNational(KEY) }.failure)
        assertEquals(WeatherFailure.NOT_ALLOWED, assertFailsWith<AemetException> { source(DefaultNetworkPolicy()).fetchNational(KEY) }.failure)
        assertTrue(requests.isEmpty())
    }

    @Test fun `plain http is refused unless a test allows it`() {
        val strict = HttpAemetSource(policy, baseUrl = "${base()}/api")
        assertEquals(WeatherFailure.NOT_ALLOWED, assertFailsWith<AemetException> { strict.fetchNational(KEY) }.failure)
        assertTrue(requests.isEmpty())
    }

    @Test fun `an unreadable archive is invalid and a 404 on the data URL means no warnings`() {
        data = ByteArray(3000) { 9 }
        assertEquals(WeatherFailure.INVALID, assertFailsWith<AemetException> { source().fetchNational(KEY) }.failure)
        dataStatus = 404
        assertTrue(source().fetchNational(KEY).isEmpty())
    }

    @Test fun `the whole chain gives warnings that match a position`() {
        val r = WeatherAlertRepository(source(), { true }, { KEY }, { OffsetDateTime.parse("2026-10-08T13:00:00+02:00").toInstant().toEpochMilli() }, { "es" })
        assertEquals(WeatherRefresh.Updated, r.refresh(true))
        val now = OffsetDateTime.parse("2026-10-08T13:00:00+02:00").toInstant().toEpochMilli()
        val hit = r.index().at(LatLon(39.5, -0.4), now).single()
        assertEquals(AlertLevel.ORANGE, hit.level)
        assertIs<WeatherWarning>(hit)
    }
}

class WeatherSettingsTest {
    @Test fun `everything is off by default`() {
        val s = WeatherAlertSettings()
        assertFalse(s.enabled)
        assertFalse(s.showYellow)
        assertFalse(InMemoryWeatherAlertSettings().settings.value.enabled)
    }

    @Test fun `keys are cleaned and checked`() {
        assertEquals(KEY, ApiKeys.clean("  $KEY\n"))
        assertEquals(KEY, ApiKeys.clean(KEY.substring(0, 20) + " \n " + KEY.substring(20)))
        assertNull(ApiKeys.clean(""))
        assertNull(ApiKeys.clean("short"))
        assertNull(ApiKeys.clean("<script>alert(1)</script>aaaaaaaaaaaaaaaaaaaa"))
        assertNull(ApiKeys.clean("a".repeat(3000)))
    }

    @Test fun `the in-memory key store keeps and forgets`() {
        val k = InMemoryApiKeyStore()
        assertNull(k.read())
        k.write(KEY)
        assertEquals(KEY, k.read())
        k.clear()
        assertNull(k.read())
    }
}
