package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.TrackLine
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackFeaturesTest {
    @Test
    fun oneLineStringPerSegmentWithIdAndColour() {
        val track = TrackLine(
            7,
            listOf(
                listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1)),
                listOf(LatLon(41.0, 2.0)), // a lone point draws nothing
                listOf(LatLon(42.0, 3.0), LatLon(42.1, 3.1), LatLon(42.2, 3.2)),
            ),
            0xFFE5484D.toInt(),
        )
        val features = TrackFeatures.collection(listOf(track, TrackLine(8, emptyList(), 0xFF000000.toInt()))).features()!!
        assertEquals(2, features.size)
        features.forEach {
            assertEquals(7, it.getNumberProperty(TrackFeatures.ID).toInt())
            assertEquals("#E5484D", it.getStringProperty(TrackFeatures.COLOR))
        }
        val first = features[0].geometry() as org.maplibre.geojson.LineString
        assertEquals(-3.0, first.coordinates()[0].longitude(), 0.0) // GeoJSON order is lon, lat
        assertEquals(40.0, first.coordinates()[0].latitude(), 0.0)
    }

    @Test
    fun colourIsOpaqueHex() {
        assertEquals("#0A84FF", TrackFeatures.hex(0x800A84FF.toInt()))
    }

    @Test
    fun emptyInputGivesEmptyCollection() {
        assertEquals(0, TrackFeatures.collection(emptyList()).features()!!.size)
    }
}
