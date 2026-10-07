package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo

/** Hand-made clock: seconds since [FollowFixtures.T0], never real time. */
class TestClock(var sec: Long = 0L) {
    fun millis(): Long = (FollowFixtures.T0 + sec) * 1000
}

/**
 * Synthetic trip (1 degree of latitude is about 111 km, so 0.009 is about 1 km):
 *
 * ```
 *  O --walk-- A =metro L5 (A B C D E)= E --walk 170 m-- F =bus 27 (F G H)= H --walk-- destination
 * ```
 * Times in seconds after [T0]: walk 0..420, metro departs A at 600, B 720/750, C 870/900, D 1020/1050, E arrives 1170; walk to F
 * 1170..1350; bus departs F at 1500, G 1620/1650, H arrives 1800; last walk 1800..1920.
 */
object FollowFixtures {
    const val T0 = 1_800_000_000L

    val O = LatLon(40.0000, -3.0)
    val stopLat = doubleArrayOf(40.0036, 40.0126, 40.0216, 40.0306, 40.0396)
    private val metroArrive = longArrayOf(600, 720, 870, 1020, 1170)
    private val metroDepart = longArrayOf(600, 750, 900, 1050, 1170)
    val metroStops = listOf("A", "B", "C", "D", "E").mapIndexed { i, name ->
        ItineraryStop(name, LatLon(stopLat[i], -3.0), T0 + metroArrive[i], T0 + metroDepart[i])
    }
    val F = LatLon(40.0396, -3.0020)
    val busStops = listOf(
        ItineraryStop("F", F, T0 + 1500, T0 + 1500),
        ItineraryStop("G", LatLon(40.0486, -3.0020), T0 + 1620, T0 + 1650),
        ItineraryStop("H", LatLon(40.0576, -3.0020), T0 + 1800, T0 + 1800),
    )
    val destination = LatLon(40.0586, -3.0020)

    val metro = LineInfo("L5", "Line five", 0xFF00AA00.toInt(), 0xFFFFFFFF.toInt(), 1)
    val bus = LineInfo("27", "Route 27", 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(), 3)

    fun itinerary(): Itinerary = Itinerary(
        listOf(
            ItineraryLeg.Walk(null, "A", O, metroStops[0].point, 520, T0, T0 + 420),
            ItineraryLeg.Ride(metro, "Westbound", metroStops),
            ItineraryLeg.Walk("E", "F", metroStops[4].point, F, 220, T0 + 1170, T0 + 1350),
            ItineraryLeg.Ride(bus, "Hospital", busStops),
            ItineraryLeg.Walk("H", null, busStops[2].point, destination, 130, T0 + 1800, T0 + 1920),
        ),
    )

    fun fix(p: LatLon, speed: Float? = null, accuracy: Float = 10f) = LocationFix(p, accuracyMeters = accuracy, speedMps = speed)

    /** A point on the metro line, [frac] of the way from stop [i] to the next one. */
    fun onMetro(i: Int, frac: Double = 0.0): LatLon {
        val lat = stopLat[i] + frac * (if (i < 4) stopLat[i + 1] - stopLat[i] else 0.0)
        return LatLon(lat, -3.0)
    }

    fun onBus(i: Int, frac: Double = 0.0): LatLon {
        val a = busStops[i].point
        val b = busStops[minOf(i + 1, 2)].point
        return LatLon(a.lat + frac * (b.lat - a.lat), a.lon)
    }

    /** Walks to A, boards at the scheduled time and rides, stop by stop, to stop [i] (running exactly on the timetable). */
    fun rideMetroTo(f: ItineraryFollower, clock: TestClock, i: Int) {
        clock.sec = 0
        f.onFix(fix(O))
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        clock.sec = 600
        f.onFix(fix(LatLon(stopLat[0] + 0.0012, -3.0), speed = 8f))
        clock.sec = 605
        f.onFix(fix(LatLon(stopLat[0] + 0.0020, -3.0), speed = 8f))
        for (k in 1..i) {
            clock.sec = metroArrive[k]
            f.onFix(fix(onMetro(k), speed = 10f))
        }
    }
}
