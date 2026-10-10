package com.qtekfun.ultimatemaps.transit.follow

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.MapasApp
import com.qtekfun.ultimatemaps.core.transit.follow.FollowerSnapshot
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The foreground service over the real application object: persistence, resume after the process died, stop and the manifest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitTripServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val tripFile = File(app.noBackupFilesDir, "transit/trip.bin")

    @After fun cleanup() {
        (app as? MapasApp)?.transitTrip?.stop()
        File(app.noBackupFilesDir, "transit").deleteRecursively()
    }

    private fun savedTrip(snapshot: FollowerSnapshot = FollowerSnapshot(0, false, 0.0, null)) {
        // The saved file carries the clock of the real application; a fresh timestamp keeps it valid for the 3 h expiry.
        assertTrue(TransitTripStore(tripFile).save(TripTestSupport.itinerary(), snapshot, TripTestSupport.ZONE))
    }

    @Test fun aNullIntentAfterTheProcessWasKilledResumesTheSavedTripAndGoesForeground() {
        savedTrip(FollowerSnapshot(1, true, 2.5, 40))
        val controller = Robolectric.buildService(TransitTripService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        val host = (app as MapasApp).transitTrip
        assertTrue(host.active, "the trip was resumed from disk")
        // the controller (synchronous), not the host's ui flow, which is derived on another thread and may not have caught up yet
        assertEquals("Delta", host.controller.state.value!!.follow.nextStopName)
        val n = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(n) // the first notification exists at once; the watcher refreshes it with the trip text afterwards
        controller.destroy()
    }

    @Test fun withNothingSavedTheServiceStopsAtOnce() {
        val controller = Robolectric.buildService(TransitTripService::class.java).create()
        controller.get().onStartCommand(Intent(TransitTripService.ACTION_RESUME), 0, 1)
        assertFalse((app as MapasApp).transitTrip.active)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun anExpiredTripIsNotResumedAndItsFileIsDeleted() {
        TransitTripStore(tripFile, clock = { System.currentTimeMillis() - 4 * 60 * 60 * 1000L }).save(
            TripTestSupport.itinerary(), FollowerSnapshot(0, false, 0.0, null), TripTestSupport.ZONE,
        )
        val controller = Robolectric.buildService(TransitTripService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        assertFalse((app as MapasApp).transitTrip.active)
        assertFalse(tripFile.exists())
        controller.destroy()
    }

    @Test fun theStopActionEndsTheTripAndRemovesTheSavedState() {
        savedTrip()
        val controller = Robolectric.buildService(TransitTripService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        assertTrue((app as MapasApp).transitTrip.active)
        controller.get().onStartCommand(Intent(TransitTripService.ACTION_STOP), 0, 2)
        assertFalse(app.transitTrip.active)
        assertFalse(tripFile.exists())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun theNotificationIsQuietOngoingAndOffersOpenAndStop() {
        savedTrip(FollowerSnapshot(1, true, 1.5, 0))
        val controller = Robolectric.buildService(TransitTripService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        val n: Notification = shadowOf(controller.get()).lastForegroundNotification
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Open", "Stop"), n.actions.map { it.title.toString() })
        assertNull(n.sound)
        controller.destroy()
    }

    @Test fun theServiceIsALocationForegroundServiceAndNothingNewIsExported() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("<service[^>]*TransitTripService[^>]*/>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertNotNull(service)
        assertTrue(service.contains("android:foregroundServiceType=\"location\""))
        assertTrue(service.contains("android:exported=\"false\""))
        assertFalse(service.contains("android:process"))
    }
}
