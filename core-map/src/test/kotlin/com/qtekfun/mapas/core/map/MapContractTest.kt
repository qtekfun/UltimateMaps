package com.qtekfun.mapas.core.map

import com.qtekfun.mapas.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapContractTest {
    @Test
    fun cameraStateRejectsBadZoomAndTilt() {
        assertFailsWith<IllegalArgumentException> { CameraState(LatLon(0.0, 0.0), 30.0) }
        assertFailsWith<IllegalArgumentException> { CameraState(LatLon(0.0, 0.0), 5.0, tilt = 90.0) }
        assertFailsWith<IllegalArgumentException> { CameraState(LatLon(0.0, 0.0), Double.NaN) }
    }

    @Test
    fun legacyEngineGetsDefaultCameraState() {
        val engine = object : MapEngine {
            override fun setCamera(center: LatLon, zoom: Double) {}
            override fun camera() = LatLon(1.0, 2.0) to 7.0
            override fun close() {}
        }
        assertEquals(CameraState(LatLon(1.0, 2.0), 7.0), engine.cameraState())
    }

    @Test
    fun simulatedSourceDeliversOnlyWhileStarted() {
        val src = SimulatedLocationSource()
        val got = mutableListOf<LocationFix>()
        assertNull(src.lastKnown())
        src.emit(40.0, -3.0)
        assertTrue(got.isEmpty())
        src.start { got += it }
        assertTrue(src.isStarted)
        src.emit(41.0, -3.5)
        src.stop()
        src.emit(42.0, -4.0)
        assertFalse(src.isStarted)
        assertEquals(listOf(LatLon(41.0, -3.5)), got.map { it.point })
        assertEquals(LatLon(42.0, -4.0), src.lastKnown()?.point)
    }
}
