package com.qtekfun.ultimatemaps.core.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteCameraFitTest {
    private val madrid = LatLon(40.4168, -3.7038)
    private val barcelona = LatLon(41.3874, 2.1686)
    private val sevilla = LatLon(37.3891, -5.9845)

    private fun px(p: LatLon, cam: CameraState, w: Int, h: Int): Pair<Double, Double> {
        val scale = 512.0 * 2.0.pow(cam.zoom)
        fun x(lon: Double) = (lon + 180.0) / 360.0
        fun y(lat: Double) = 0.5 - ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)
        return (w / 2.0 + (x(p.lon) - x(cam.center.lon)) * scale) to (h / 2.0 + (y(p.lat) - y(cam.center.lat)) * scale)
    }

    private fun assertInside(points: List<LatLon>, cam: CameraState, w: Int, h: Int, pad: CameraPadding) {
        val eps = 1.0
        for (pt in points) {
            val (x, y) = px(pt, cam, w, h)
            assertTrue(x >= pad.left - eps && x <= w - pad.right + eps, "x=$x outside for $pt")
            assertTrue(y >= pad.top - eps && y <= h - pad.bottom + eps, "y=$y outside for $pt")
        }
    }

    @Test fun `long route is entirely inside the free area for several screens and sheet heights`() {
        val route = listOf(madrid, barcelona, sevilla)
        for ((w, h) in listOf(1080 to 2400, 720 to 1280, 1440 to 3120, 2000 to 1200)) {
            for (bottom in listOf(300, (h * 0.46).toInt(), (h * 0.7).toInt())) {
                val pad = CameraPadding(80, 200, 200, bottom)
                val cam = assertNotNull(RouteCameraFit.fit(route, w, h, pad))
                assertInside(route, cam, w, h, RouteCameraFit.clampPadding(pad, w, h))
            }
        }
    }

    @Test fun `the bounds touch the free area on the limiting axis`() {
        val route = listOf(madrid, barcelona)
        val cam = assertNotNull(RouteCameraFit.fit(route, 1000, 1000, CameraPadding()))
        val (x1, _) = px(madrid, cam, 1000, 1000)
        val (x2, _) = px(barcelona, cam, 1000, 1000)
        assertEquals(1000.0, x2 - x1, 1.0) // wider than tall: the width limits
    }

    @Test fun `more sheet means more zoom out and the route moves up`() {
        val route = listOf(madrid, barcelona)
        val low = assertNotNull(RouteCameraFit.fit(route, 1080, 2400, CameraPadding(50, 100, 50, 300)))
        val high = assertNotNull(RouteCameraFit.fit(route, 1080, 2400, CameraPadding(50, 100, 50, 1100)))
        assertTrue(high.zoom <= low.zoom)
        // The camera centre is further south when the route is shown in the upper part of the screen.
        assertTrue(high.center.lat < low.center.lat)
    }

    @Test fun `short route is capped at the max zoom`() {
        val cam = assertNotNull(RouteCameraFit.fit(listOf(LatLon(40.0, -3.0), LatLon(40.0001, -3.0001)), 1080, 2400, CameraPadding()))
        assertEquals(RouteCameraFit.MAX_ZOOM, cam.zoom)
    }

    @Test fun `single point and identical points do not fail`() {
        val one = assertNotNull(RouteCameraFit.fit(listOf(madrid), 1080, 2400, CameraPadding(50, 100, 50, 900)))
        assertEquals(RouteCameraFit.MAX_ZOOM, one.zoom)
        val same = assertNotNull(RouteCameraFit.fit(listOf(madrid, madrid, madrid), 1080, 2400, CameraPadding()))
        assertEquals(RouteCameraFit.MAX_ZOOM, same.zoom)
        assertEquals(madrid.lat, same.center.lat, 1e-6)
        assertEquals(madrid.lon, same.center.lon, 1e-6)
    }

    @Test fun `single point lands in the middle of the free area`() {
        val cam = assertNotNull(RouteCameraFit.fit(listOf(madrid), 1000, 2000, CameraPadding(0, 100, 0, 900)))
        val (x, y) = px(madrid, cam, 1000, 2000)
        assertEquals(500.0, x, 0.5)
        assertEquals((100 + (2000 - 900)) / 2.0, y, 0.5)
    }

    @Test fun `empty route and zero-size screen give null`() {
        assertNull(RouteCameraFit.fit(emptyList(), 1080, 2400, CameraPadding()))
        assertNull(RouteCameraFit.fit(listOf(madrid), 0, 2400, CameraPadding()))
        assertNull(RouteCameraFit.fit(listOf(madrid), 1080, 0, CameraPadding()))
    }

    @Test fun `padding larger than the screen still leaves a quarter free`() {
        val pad = CameraPadding(900, 1000, 900, 3000)
        val clamped = RouteCameraFit.clampPadding(pad, 1080, 2400)
        assertTrue(clamped.left + clamped.right <= 1080 * 0.75 + 1)
        assertTrue(clamped.top + clamped.bottom <= 2400 * 0.75 + 1)
        assertNotNull(RouteCameraFit.fit(listOf(madrid, barcelona), 1080, 2400, pad))
    }

    @Test fun `zoom stays in range for a world-wide route`() {
        val cam = assertNotNull(RouteCameraFit.fit(listOf(LatLon(-80.0, -179.0), LatLon(80.0, 179.0)), 1080, 2400, CameraPadding()))
        assertTrue(cam.zoom in 0.0..RouteCameraFit.MAX_ZOOM)
    }

    @Test fun `a denser screen needs a lower zoom by log2 of the density and the route still fits`() {
        val route = listOf(LatLon(40.4046, -3.6840), LatLon(40.4154, -3.7074)) // about 2.6 km, as seen on a Pixel 8
        val pad = CameraPadding(84, 279, 189, 1188)
        val flat = assertNotNull(RouteCameraFit.fit(route, 1080, 2400, pad, density = 1.0))
        val pixel = assertNotNull(RouteCameraFit.fit(route, 1080, 2400, pad, density = 2.625))
        assertEquals(kotlin.math.log2(2.625), flat.zoom - pixel.zoom, 1e-9)
        // MapLibre draws the world 512 * density * 2^zoom device pixels wide: every point must land inside the free area.
        val scale = 512.0 * 2.625 * 2.0.pow(pixel.zoom)
        fun x(lon: Double) = (lon + 180.0) / 360.0
        fun y(lat: Double) = 0.5 - ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)
        for (pt in route) {
            val px = 1080 / 2.0 + (x(pt.lon) - x(pixel.center.lon)) * scale
            val py = 2400 / 2.0 + (y(pt.lat) - y(pixel.center.lat)) * scale
            assertTrue(px in (pad.left - 1.0)..(1080 - pad.right + 1.0), "x=$px")
            assertTrue(py in (pad.top - 1.0)..(2400 - pad.bottom + 1.0), "y=$py")
        }
        assertTrue(pixel.zoom < 14.0, "a 2.6 km route on a 2.6x screen is about zoom 12.7, not ${pixel.zoom}")
    }
}
