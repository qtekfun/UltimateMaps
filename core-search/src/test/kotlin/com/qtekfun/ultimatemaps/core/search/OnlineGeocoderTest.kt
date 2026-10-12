package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Virtual time: nothing here sleeps or starts a thread. */
private class FakeScheduler : GeocodeScheduler {
    var time = 0L

    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)

    private val tasks = mutableListOf<Task>()

    override fun now() = time

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(time + delayMs, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    fun advance(ms: Long) {
        time += ms
        tasks.filter { !it.cancelled && it.at <= time }.forEach { it.cancelled = true; it.run() }
    }
}

private class FakeBackend : GeocoderBackend {
    var last: Triple<String, GeocodeBounds?, Int>? = null
    var callback: GeocoderBackend.Callback? = null
    var calls = 0
    var throwUnavailable = false

    override fun forwardGeocode(query: String, bounds: GeocodeBounds?, maxResults: Int, callback: GeocoderBackend.Callback) {
        calls++
        if (throwUnavailable) throw BackendUnavailableException("no geocoder")
        last = Triple(query, bounds, maxResults)
        this.callback = callback
    }
}

class OnlineGeocoderTest {
    private val sched = FakeScheduler()
    private val backend = FakeBackend()
    private var on = true
    private var ok = true
    private val geocoder = SystemGeocoderFallback(backend, sched, enabled = { on }, allowed = { ok })
    private val out = mutableListOf<OnlineGeocodeOutcome>()

    private fun ask(q: String = "calle mayor 5 getafe", near: GeocodeBounds? = null, limit: Int = 5) =
        geocoder.search(q, near, limit) { out += it }

    private val street = GeocodedAddress(
        featureName = "5", addressLines = listOf("Calle Mayor, 5", "28901 Getafe", "Spain"), thoroughfare = "Calle Mayor",
        subThoroughfare = "5", locality = "Getafe", latitude = 40.3, longitude = -3.7,
    )

    @Test fun switchOffSendsNothing() {
        on = false
        ask()
        assertEquals(0, backend.calls)
        assertEquals(OnlineGeocodeOutcome.Skipped(SkipReason.DISABLED), out.single())
    }

    @Test fun notAllowedSendsNothing() {
        ok = false
        ask()
        assertEquals(0, backend.calls)
        assertEquals(OnlineGeocodeOutcome.Skipped(SkipReason.NOT_ALLOWED), out.single())
    }

    @Test fun shortQueryIsNotSent() {
        ask("ab ")
        assertEquals(0, backend.calls)
        assertEquals(OnlineGeocodeOutcome.Skipped(SkipReason.QUERY_TOO_SHORT), out.single())
    }

    @Test fun passesTrimmedQueryBoundsAndLimit() {
        val b = GeocodeBounds.around(LatLon(40.0, -3.7), 20_000.0)
        ask("  farmacia  ", b, 3)
        assertEquals(Triple("farmacia", b, 3), backend.last)
    }

    @Test fun mapsResultsAndMeasuresLatency() {
        ask()
        sched.advance(250)
        backend.callback!!.onResults(listOf(street))
        val f = assertIs<OnlineGeocodeOutcome.Found>(out.single())
        assertEquals(250, f.latencyMs)
        val r = f.results.single()
        assertEquals("Calle Mayor 5", r.name)
        assertEquals("Calle Mayor, 5, 28901 Getafe, Spain", r.address)
        assertEquals(LatLon(40.3, -3.7), r.point)
        assertNull(r.category)
    }

    @Test fun businessNameKeepsFeatureNameAndDropsDuplicateFirstLine() {
        val a = GeocodedAddress(
            featureName = "Hospital Universitario de Getafe",
            addressLines = listOf("Hospital Universitario de Getafe", "Ctra. de Toledo, km 12,500", "Getafe"),
            latitude = 40.27, longitude = -3.72,
        )
        val r = GeocodedAddressMapper.map(a)!!
        assertEquals("Hospital Universitario de Getafe", r.name)
        assertEquals("Ctra. de Toledo, km 12,500, Getafe", r.address)
    }

    @Test fun resultsWithoutCoordinatesAreDropped() {
        ask()
        backend.callback!!.onResults(listOf(street.copy(latitude = null), street.copy(longitude = null), street.copy(latitude = 99.0)))
        assertIs<OnlineGeocodeOutcome.NoMatch>(out.single())
    }

    @Test fun duplicatesAreCollapsedAndLimitApplied() {
        ask(limit = 2)
        val other = street.copy(featureName = "Plaza Mayor", thoroughfare = null, latitude = 40.31)
        val third = street.copy(featureName = "Otra", latitude = 40.4)
        backend.callback!!.onResults(listOf(street, street, other, third))
        assertEquals(2, assertIs<OnlineGeocodeOutcome.Found>(out.single()).results.size)
    }

    @Test fun cityOnlyAddressFallsBackToLocality() {
        val a = GeocodedAddress(locality = "Alcala de Henares", countryName = "Spain", latitude = 40.48, longitude = -3.36)
        val r = GeocodedAddressMapper.map(a)!!
        assertEquals("Alcala de Henares", r.name)
        assertEquals("Spain", r.address)
    }

    @Test fun nothingUsableToNameIsDropped() {
        assertNull(GeocodedAddressMapper.map(GeocodedAddress(latitude = 1.0, longitude = 1.0)))
    }

    @Test fun backendErrorIsReportedOnce() {
        ask()
        backend.callback!!.onError("boom")
        backend.callback!!.onResults(listOf(street))
        val f = assertIs<OnlineGeocodeOutcome.Failed>(out.single())
        assertEquals(FailureReason.BACKEND_ERROR, f.reason)
        assertEquals("boom", f.message)
    }

    @Test fun timeoutFiresAndLateAnswerIsIgnored() {
        ask()
        sched.advance(SystemGeocoderFallback.DEFAULT_TIMEOUT_MS - 1)
        assertTrue(out.isEmpty())
        sched.advance(1)
        assertEquals(FailureReason.TIMEOUT, assertIs<OnlineGeocodeOutcome.Failed>(out.single()).reason)
        backend.callback!!.onResults(listOf(street))
        assertEquals(1, out.size)
    }

    @Test fun answerCancelsTheTimer() {
        ask()
        backend.callback!!.onResults(emptyList())
        sched.advance(60_000)
        assertEquals(1, out.size)
        assertIs<OnlineGeocodeOutcome.NoMatch>(out.single())
    }

    @Test fun missingBackendIsReported() {
        backend.throwUnavailable = true
        ask()
        assertEquals(FailureReason.NO_BACKEND, assertIs<OnlineGeocodeOutcome.Failed>(out.single()).reason)
    }

    @Test fun probeIsWorkingOnlyWithAUsableResult() {
        var result: Boolean? = null
        GeocoderProbe(geocoder).run { result = it }
        assertEquals(GeocoderProbe.QUERY, backend.last!!.first)
        backend.callback!!.onResults(listOf(street))
        assertEquals(true, result)

        result = null
        GeocoderProbe(geocoder).run { result = it }
        backend.callback!!.onResults(emptyList())
        assertEquals(false, result)
    }

    @Test fun boundsAreClampedAndOrdered() {
        val b = GeocodeBounds.around(LatLon(89.99, 179.99), 50_000.0)
        assertTrue(b.lowerLeft.lat < b.upperRight.lat && b.upperRight.lat <= 90.0 && b.upperRight.lon <= 180.0)
    }
}
