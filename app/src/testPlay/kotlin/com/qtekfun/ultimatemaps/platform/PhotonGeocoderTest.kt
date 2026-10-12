package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.search.InMemoryOnlineSearchSettings
import com.qtekfun.ultimatemaps.core.search.OnlineOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Photon client over a fake HTTP layer, on virtual time: parsing, empty, errors, rate limit, switch and offline mode. */
@RunWith(RobolectricTestRunner::class) // org.json needs the Android implementation
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PhotonGeocoderTest {
    private val madrid = LatLon(40.416775, -3.70379)

    private val sample = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.7001,40.4202]},
           "properties":{"osm_type":"N","osm_id":1,"osm_key":"amenity","osm_value":"cafe","name":"Cafe Luna",
             "street":"Calle Mayor","housenumber":"12","postcode":"28013","city":"Madrid","state":"Community of Madrid","country":"Spain"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.7001,40.4202]},
           "properties":{"osm_key":"amenity","osm_value":"cafe","name":"cafe luna","city":"Madrid"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.69,40.41]},
           "properties":{"osm_key":"place","osm_value":"house","street":"Calle de Alcala","housenumber":"3","postcode":"28014","city":"Madrid","country":"Spain"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.0,40.0]},"properties":{"osm_key":"boundary","osm_value":"yes"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[999,999]},"properties":{"name":"Nowhere"}},
          {"type":"Feature","properties":{"name":"No geometry"}}
        ]}
    """.trimIndent()

    private class Fetch(var reply: (String) -> String = { error("not set") }) : PhotonFetch {
        val urls = mutableListOf<String>()
        val times = mutableListOf<Long>()
        var clock: () -> Long = { 0L }
        override fun get(url: String): String {
            urls += url
            times += clock()
            return reply(url)
        }
    }

    private fun policy(offline: Boolean = false) = DefaultNetworkPolicy(
        listOf(AllowedEndpoint("photon.komoot.io", ConnectionPurpose.ONLINE_SEARCH, enabled = true)), offlineMode = offline,
    )

    private fun TestScope.geocoder(
        fetch: Fetch,
        settings: InMemoryOnlineSearchSettings = InMemoryOnlineSearchSettings(enabled = true),
        policy: DefaultNetworkPolicy = policy(),
        lang: String? = null,
    ): PhotonGeocoder {
        fetch.clock = { currentTime }
        return PhotonGeocoder(settings, policy, fetch, language = { lang }, io = StandardTestDispatcher(testScheduler), clock = { currentTime })
    }

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun resultsAreParsedMarkedOnlineAndDeduplicated() = runTest {
        val f = Fetch { sample }
        val r = geocoder(f).search("cafe luna", madrid, 8)
        val list = assertIs<OnlineOutcome.Found>(r).results
        assertEquals(listOf("Cafe Luna", "Calle de Alcala 3"), list.map { it.name })
        assertTrue(list.all { it.online })
        val cafe = list[0]
        assertEquals(LatLon(40.4202, -3.7001), cafe.point)
        assertEquals("Cafe", cafe.category)
        assertEquals("Calle Mayor 12, 28013 Madrid, Community of Madrid, Spain", cafe.address)
        val house = list[1]
        assertEquals("28014 Madrid, Spain", house.address)
        assertEquals("House", house.category)
    }

    @Test
    fun theRequestCarriesOnlyTheTextTheRoundedCentreTheLanguageAndTheLimit() = runTest {
        val f = Fetch { """{"features":[]}""" }
        geocoder(f, lang = "de").search("  Calle Mayor 12, Ávila ", madrid, 8)
        val url = f.urls.single()
        assertTrue(url.startsWith("https://photon.komoot.io/api/?"), url)
        assertEquals(mapOf("q" to "Calle Mayor 12, Ávila", "lat" to "40.42", "lon" to "-3.7", "lang" to "de", "limit" to "8"), query(url))
    }

    @Test
    fun noBiasAndNoLanguageAreLeftOut() = runTest {
        val f = Fetch { """{"features":[]}""" }
        geocoder(f).search("plaza mayor", null, 5)
        assertEquals(mapOf("q" to "plaza mayor", "limit" to "5"), query(f.urls.single()))
    }

    @Test
    fun theConfiguredServerIsUsedAndAnApiSuffixIsAccepted() = runTest {
        val f = Fetch { """{"features":[]}""" }
        val s = InMemoryOnlineSearchSettings(enabled = true, serverUrl = "https://photon.example.org/api/")
        geocoder(f, s).search("plaza mayor", null, 5)
        assertTrue(f.urls.single().startsWith("https://photon.example.org/api/?q="), f.urls.single())
        // An insecure or malformed address falls back to the default and never reaches the network.
        val g = Fetch { """{"features":[]}""" }
        geocoder(g, InMemoryOnlineSearchSettings(enabled = true, serverUrl = "http://photon.example.org")).search("plaza mayor", null, 5)
        assertTrue(g.urls.single().startsWith("https://photon.komoot.io/api/?q="))
    }

    @Test
    fun emptyAnswersAreFoundWithNoResults() = runTest {
        assertEquals(OnlineOutcome.Found(emptyList()), geocoder(Fetch { """{"type":"FeatureCollection","features":[]}""" }).search("zzzz", null, 8))
    }

    @Test
    fun garbageAndServerErrorsAreFailures() = runTest {
        assertEquals(OnlineOutcome.Failed, geocoder(Fetch { "<html>502</html>" }).search("plaza", null, 8))
        assertEquals(OnlineOutcome.Failed, geocoder(Fetch { """{"message":"not supported language"}""" }).search("plaza", null, 8))
        assertEquals(
            OnlineOutcome.Failed,
            geocoder(Fetch { throw DownloadException(DownloadFailure.SERVER, "HTTP 503") }).search("plaza", null, 8),
        )
        assertEquals(OnlineOutcome.Failed, geocoder(Fetch { throw DownloadException(DownloadFailure.TIMEOUT, "slow") }).search("plaza", null, 8))
        assertEquals(OnlineOutcome.Failed, geocoder(Fetch { throw IOException("reset") }).search("plaza", null, 8))
    }

    @Test
    fun theSwitchOffSendsNothing() = runTest {
        val f = Fetch { error("must not be called") }
        val g = geocoder(f, InMemoryOnlineSearchSettings(enabled = false))
        assertFalse(g.enabled)
        assertEquals(OnlineOutcome.Disabled, g.search("plaza mayor", null, 8))
        assertTrue(f.urls.isEmpty())
    }

    @Test
    fun offlineModeSendsNothingAndLogsNoAttempt() = runTest {
        val f = Fetch { error("must not be called") }
        val p = policy(offline = true)
        val g = geocoder(f, policy = p)
        assertFalse(g.enabled)
        assertEquals(OnlineOutcome.Offline, g.search("plaza mayor", null, 8))
        assertTrue(f.urls.isEmpty())
        assertTrue(p.recentConnections().isEmpty())
    }

    @Test
    fun aDeniedConnectionIsDisabledNotAFailure() = runTest {
        val f = Fetch { throw DownloadException(DownloadFailure.NOT_ALLOWED, "not allowed: DISABLED_BY_USER") }
        assertEquals(OnlineOutcome.Disabled, geocoder(f).search("plaza mayor", null, 8))
    }

    @Test
    fun veryShortTextIsNeverSent() = runTest {
        val f = Fetch { error("must not be called") }
        assertEquals(OnlineOutcome.Found(emptyList()), geocoder(f).search(" a ", null, 8))
        assertTrue(f.urls.isEmpty())
    }

    @Test
    fun requestsAreAtLeastASecondApart() = runTest {
        val f = Fetch { """{"features":[]}""" }
        val g = geocoder(f)
        val a = async { g.search("plaza mayor", null, 8) }
        val b = async { g.search("calle mayor", null, 8) }
        val c = async { g.search("gran via", null, 8) }
        runCurrent()
        assertEquals(1, f.urls.size, "the first goes at once, the others wait")
        advanceTimeBy(999); runCurrent()
        assertEquals(1, f.urls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.urls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, f.urls.size)
        a.await(); b.await(); c.await()
        assertEquals(listOf(0L, 1_000L, 2_000L), f.times)
    }

    @Test
    fun aLaterRequestAfterTheIntervalIsNotDelayed() = runTest {
        val f = Fetch { """{"features":[]}""" }
        val g = geocoder(f)
        g.search("plaza mayor", null, 8)
        advanceTimeBy(5_000)
        val t = currentTime
        g.search("calle mayor", null, 8)
        assertEquals(t, currentTime)
        assertEquals(2, f.urls.size)
    }

    @Test
    fun theUserAgentNamesTheAppAndItsVersionOnly() {
        assertEquals("UltimateMaps/0.1.0-rc.26-play (+https://github.com/qtekfun/UltimateMaps)", PhotonGeocoder.userAgent("0.1.0-rc.26-play"))
        assertEquals("UltimateMaps/unknown (+https://github.com/qtekfun/UltimateMaps)", PhotonGeocoder.userAgent(null))
        assertNull(PhotonGeocoder.parse("not json", 8))
        assertEquals(2, PhotonGeocoder.parse(sample, 8)?.size)
        assertEquals(1, PhotonGeocoder.parse(sample, 1)?.size)
    }
}
