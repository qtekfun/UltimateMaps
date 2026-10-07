package com.qtekfun.ultimatemaps.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.LocationSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Navigation needs a precise, regular stream: on API 31+ the request must say so explicitly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLocationSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val shadow = shadowOf(lm)

    @Test
    fun theFusedProviderIsAskedForHighAccuracyAtTheConfiguredInterval() {
        shadow.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val source = AndroidLocationSource(context, intervalMillis = 1000L)
        source.start { _ -> }
        val requests = shadow.getLocationRequests(LocationManager.FUSED_PROVIDER)
        assertEquals(1, requests.size)
        val r = requests.single()
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, r.quality)
        assertEquals(1000L, r.intervalMillis)
        source.stop()
    }

    @Test
    fun fixesReachTheListenerWithTheirAccuracy() {
        shadow.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val source = AndroidLocationSource(context)
        val got = mutableListOf<LocationFix>()
        source.start(LocationSource.Listener { got += it })
        val loc = Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 40.4168
            longitude = -3.7038
            accuracy = 8f
            time = 1_000L
        }
        shadow.simulateLocation(loc)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(got.isNotEmpty())
        assertEquals(8f, got.last().accuracyMeters)
        source.stop()
    }
}
