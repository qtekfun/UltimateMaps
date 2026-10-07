package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.cameras.AxisSense
import com.qtekfun.ultimatemaps.core.cameras.CameraDataRepository
import com.qtekfun.ultimatemaps.core.cameras.CameraDataset
import com.qtekfun.ultimatemaps.core.cameras.CameraKind
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.CameraSources
import com.qtekfun.ultimatemaps.core.cameras.IncidentData
import com.qtekfun.ultimatemaps.core.cameras.IncidentDataRepository
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.core.cameras.MobileZone
import com.qtekfun.ultimatemaps.core.cameras.SpeedCamera
import com.qtekfun.ultimatemaps.core.cameras.TrafficIncident
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.map.HazardKind
import com.qtekfun.ultimatemaps.core.map.HazardLine
import com.qtekfun.ultimatemaps.core.map.HazardPin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HazardMapLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val view = GeoBounds(40.0, -4.0, 41.0, -3.0)
    private val pins = CopyOnWriteArrayList<List<HazardPin>>()
    private val lines = CopyOnWriteArrayList<List<HazardLine>>()

    private fun cam(id: String, lat: Double, lon: Double, kind: CameraKind = CameraKind.FIXED, end: LatLon? = null) =
        SpeedCamera(id, kind, LatLon(lat, lon), end, "A-1", null, null, AxisSense.BOTH, CameraSources.DGT)

    private val data = CameraDataset(
        1L, CameraSources.DGT,
        fixed = listOf(cam("f0", 40.2, -3.8), cam("f1", 45.0, -3.8)), // the second is outside the view
        sections = listOf(cam("s0", 40.5, -3.5, CameraKind.SECTION, LatLon(40.55, -3.5))),
        zones = listOf(
            MobileZone("z0", "A-2", "Madrid", 1000, 2000, listOf(LatLon(40.6, -3.4), LatLon(40.65, -3.4))),
            MobileZone("z1", "CM-9", "Toledo", 1000, 2000, emptyList()), // text only: never drawn
        ),
    )

    private fun incident(id: String, kind: IncidentKind, lat: Double, lon: Double, end: LatLon? = null) =
        TrafficIncident(id, kind, "N-1", LatLon(lat, lon), end, null, null, null, null, null, null)

    private val incidentData = IncidentData(
        2L, null,
        listOf(
            incident("i0", IncidentKind.V16, 40.7, -3.7),
            incident("i1", IncidentKind.ACCIDENT, 40.8, -3.6, LatLon(40.81, -3.6)),
            incident("i2", IncidentKind.ROADWORKS, 40.9, -3.5),
        ),
    )

    private val cameras = CameraDataRepository().also { it.install(data) }
    private val incidents = IncidentDataRepository().also { it.install(incidentData) }

    private fun layer(settings: MutableStateFlow<CameraSettings>, debounce: Long = 10) =
        HazardMapLayer(scope, Dispatchers.IO, cameras, incidents, settings, { p, l -> pins += p; lines += l }, debounce).also { it.start() }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun settle() = Thread.sleep(150)

    @After fun tearDown() = scope.cancel()

    private val all = CameraSettings(fixedEnabled = true, mobileZonesEnabled = true, incidentsEnabled = true, v16Enabled = true, acknowledged = true)

    @Test fun drawsOnlyWhatIsEnabledInsideTheView() {
        val l = layer(MutableStateFlow(all))
        l.onViewport(view, 10.0)
        await("draw") { pins.isNotEmpty() }
        val ids = pins.last().associate { it.id to it.kind }
        assertEquals(HazardKind.FIXED_CAMERA, ids["cam:f0"])
        assertEquals(HazardKind.SECTION, ids["cam:s0"])
        assertEquals(HazardKind.V16, ids["inc:i0"])
        assertEquals(HazardKind.ACCIDENT, ids["inc:i1"])
        assertTrue("cam:f1" !in ids, "outside the view")
        assertTrue("inc:i2" !in ids, "roadworks are off unless asked for")
        val lineIds = lines.last().associate { it.id to it.zone }
        assertEquals(true, lineIds["zone:z0"], "a mobile zone is drawn as a zone line")
        assertEquals(false, lineIds["cam:s0"], "an average-speed section is a plain line")
        assertEquals(false, lineIds["inc:i1"], "an incident stretch is a plain line")
        assertTrue("zone:z1" !in lineIds, "a zone without geometry is text only")
    }

    @Test fun eachSwitchControlsItsOwnCategory() {
        val s = MutableStateFlow(CameraSettings(v16Enabled = true))
        val l = layer(s)
        l.onViewport(view, 10.0)
        await("v16 only") { pins.isNotEmpty() }
        assertEquals(listOf("inc:i0"), pins.last().map { it.id })
        assertTrue(lines.last().isEmpty())
        s.value = CameraSettings(fixedEnabled = true, acknowledged = true)
        await("cameras only") { pins.lastOrNull()?.map { it.id }?.toSet() == setOf("cam:f0", "cam:s0") }
        assertEquals(setOf("cam:f0", "cam:s0"), pins.last().map { it.id }.toSet())
        s.value = CameraSettings(incidentsEnabled = true, roadworksEnabled = true)
        await("incidents + roadworks") { pins.lastOrNull()?.map { it.id }?.toSet() == setOf("inc:i1", "inc:i2") }
        assertEquals(setOf("inc:i1", "inc:i2"), pins.last().map { it.id }.toSet())
    }

    @Test fun offDrawsNothingAndBelowTheMinimumZoomToo() {
        val l = layer(MutableStateFlow(CameraSettings()))
        l.onViewport(view, 12.0)
        settle()
        assertTrue(pins.isEmpty() && lines.isEmpty(), "off by default: nothing drawn")
        val on = layer(MutableStateFlow(all))
        on.onViewport(view, HazardMapLayer.MIN_ZOOM - 0.1)
        settle()
        assertTrue(pins.isEmpty())
    }

    @Test fun switchingOffClearsTheMapAndNewDataRedraws() {
        val s = MutableStateFlow(all)
        val l = layer(s)
        l.onViewport(view, 10.0)
        await("draw") { pins.isNotEmpty() }
        s.value = CameraSettings()
        await("cleared") { pins.last().isEmpty() && lines.last().isEmpty() }
        s.value = all
        await("drawn again") { pins.last().isNotEmpty() }
        val before = pins.size
        incidents.install(IncidentData(3L, null, listOf(incident("n0", IncidentKind.CLOSURE, 40.3, -3.3))))
        await("new data") { pins.size > before }
        assertTrue(pins.last().any { it.id == "inc:n0" && it.kind == HazardKind.CLOSURE })
    }
}
