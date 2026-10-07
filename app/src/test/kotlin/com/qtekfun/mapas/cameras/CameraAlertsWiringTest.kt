package com.qtekfun.mapas.cameras

import com.qtekfun.mapas.core.cameras.AlertEvent
import com.qtekfun.mapas.core.cameras.AxisSense
import com.qtekfun.mapas.core.cameras.CameraDataRepository
import com.qtekfun.mapas.core.cameras.CameraDataset
import com.qtekfun.mapas.core.cameras.CameraKind
import com.qtekfun.mapas.core.cameras.CameraSettings
import com.qtekfun.mapas.core.cameras.CameraSources
import com.qtekfun.mapas.core.cameras.IncidentDataRepository
import com.qtekfun.mapas.core.cameras.InMemoryCameraSettingsStore
import com.qtekfun.mapas.core.cameras.SpeedCamera
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.nav.NavTestRig
import com.qtekfun.mapas.nav.pt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CameraAlerts] over the real navigation controller (on the nav test rig: one thread, a clock that only the test moves) and a
 * simulated location. They answer: when are alerts active, and do they work without the screen (locked, in background).
 */
class CameraAlertsWiringTest {
    private val on = CameraSettings(fixedEnabled = true, acknowledged = true)
    private val cameraNorth1500 = SpeedCamera("c", CameraKind.FIXED, pt(0.0, 1500.0), null, "A-1", 120, null, AxisSense.BOTH, CameraSources.DGT)

    private fun plan(): RoutePlan = RoutePlan((0..150).map { pt(0.0, it * 20.0) }, 3000.0, 300.0, RouteGuidance.EMPTY)

    private class Setup(rig: NavTestRig, settings: CameraSettings, camera: SpeedCamera?, val permission: AtomicBoolean = AtomicBoolean(true)) {
        val store = InMemoryCameraSettingsStore(settings)
        val cameras = CameraDataRepository().also { r -> camera?.let { r.install(CameraDataset(1L, CameraSources.DGT, listOf(it), emptyList(), emptyList())) } }
        val freeSource = SimulatedLocationSource()
        val freeSources = AtomicInteger()
        val alerts = CopyOnWriteArrayList<AlertEvent>()
        val alertsObj = CameraAlerts(
            rig.scope, store, cameras, IncidentDataRepository(), rig.controller,
            location = { freeSources.incrementAndGet(); freeSource }, hasLocationPermission = { permission.get() },
            onAlert = { alerts += it }, clock = { rig.clock.get() },
        ).also { it.start() }
    }

    private fun NavTestRig.driveNorth(fromMeters: Int, toMeters: Int, speed: Float = 25f) {
        var m = fromMeters
        while (m <= toMeters) {
            emitReal(pt(0.0, m.toDouble()), 1, speed)
            settle()
            m += speed.toInt()
        }
    }

    @Test fun aTripResumedByTheServiceWithTheScreenOffStillGetsRouteAlerts() {
        NavTestRig().use { rig ->
            val s = Setup(rig, on, cameraNorth1500)
            // The app is not on screen (locked phone / background) and nobody told the alerts about the trip: the service resumed it.
            s.alertsObj.onForeground(false)
            assertTrue(rig.controller.start(plan()))
            rig.settle()
            rig.driveNorth(0, 1450)
            rig.settle()
            assertEquals(1, s.alerts.size, "one alert for the camera on the route")
            assertEquals(0, s.freeSources.get(), "free driving never started: the route-based mode did it")
            assertNotNull(s.alertsObj.banner.state.value, "and the visual alert is up")
        }
    }

    @Test fun switchesOffMeanNoAlertsAndNoLocationListener() {
        NavTestRig().use { rig ->
            val s = Setup(rig, CameraSettings(), cameraNorth1500)
            s.alertsObj.onForeground(true)
            rig.controller.start(plan())
            rig.settle()
            rig.driveNorth(0, 1450)
            rig.settle()
            assertTrue(s.alerts.isEmpty())
            assertNull(s.alertsObj.banner.state.value)
            assertEquals(0, s.freeSources.get(), "nothing listens to the location for the free mode while every switch is off")
        }
    }

    @Test fun aSwitchTurnedOnInTheMiddleOfATripWorksForTheRestOfIt() {
        NavTestRig().use { rig ->
            val s = Setup(rig, CameraSettings(), cameraNorth1500)
            rig.controller.start(plan())
            rig.settle()
            rig.driveNorth(0, 300)
            s.store.update { on }
            rig.settle()
            rig.driveNorth(325, 1450)
            rig.settle()
            assertEquals(1, s.alerts.size)
        }
    }

    @Test fun freeDrivingNeedsTheSwitchThePermissionAndTheScreenAndStopsWhenANavigationStarts() {
        NavTestRig().use { rig ->
            val s = Setup(rig, on, cameraNorth1500, permission = AtomicBoolean(false))
            s.alertsObj.onForeground(true)
            assertFalse(s.freeSource.isStarted, "no location permission: no listener")
            s.permission.set(true)
            s.alertsObj.refreshFree() // what the permission result triggers
            assertTrue(s.freeSource.isStarted)
            var second = 0
            var m = 600.0
            while (m <= 1450.0) {
                s.freeSource.emit(LocationFix(pt(0.0, m), 5f, 0f, 25f, 2_000_000L + second * 1000L))
                second++; m += 25.0
            }
            assertEquals(1, s.alerts.size, "driving towards the camera without a navigation warns")
            s.alertsObj.onForeground(false)
            assertFalse(s.freeSource.isStarted, "app off screen: the free mode stops (no background service for it)")
            s.alertsObj.onForeground(true)
            assertTrue(s.freeSource.isStarted)
            rig.controller.start(plan())
            rig.settle()
            assertFalse(s.freeSource.isStarted, "a navigation takes over: route-based mode, one listener")
            rig.controller.stop()
            rig.settle()
            assertTrue(s.freeSource.isStarted, "and the free mode comes back when it ends")
        }
    }
}
