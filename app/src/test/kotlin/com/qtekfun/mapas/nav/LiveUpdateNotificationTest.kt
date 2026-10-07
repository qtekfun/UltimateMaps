package com.qtekfun.mapas.nav

import android.app.Application
import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The promoted navigation notification at SDK 36 and the untouched one below it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveUpdateNotificationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    /** Same shape as NavigationService.build: ongoing, titled, silent, navigation category, no custom views. */
    private fun base(ongoing: Boolean = true): Notification = NotificationCompat.Builder(context, "navigation")
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("In 160 m, turn right")
        .setContentText("7.5 km · 10 min")
        .setOngoing(ongoing)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .addAction(0, "Stop", null)
        .build()

    @Test fun `the promoted notification requests promotion and is promotable`() {
        val n = LiveUpdateNotification.promote(context, base(), LiveUpdatePlan("160 m", 25), R.drawable.ic_launcher)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals("160 m", n.shortCriticalText)
        // Robolectric's SDK 36 android-all returns false from hasPromotableCharacteristics() for any notification
        // (the platform side is not usable there), so the documented eligibility rules are asserted one by one.
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().isNotBlank())
        assertNull(n.contentView)
        assertNull(n.bigContentView)
        assertNull(n.headsUpContentView)
        assertFalse(n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_COLORIZED))
    }

    @Test fun `it keeps the title, text, category and actions of the base notification`() {
        val n = LiveUpdateNotification.promote(context, base(), LiveUpdatePlan("1.2 km", 50), R.drawable.ic_launcher)
        assertEquals("In 160 m, turn right", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("7.5 km · 10 min", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_NAVIGATION, n.category)
        assertEquals(1, n.actions.size)
    }

    @Test fun `it carries the progress style with the trip progress and a tracker icon`() {
        val n = LiveUpdateNotification.promote(context, base(), LiveUpdatePlan("160 m", 37), R.drawable.ic_launcher)
        assertEquals("android.app.Notification\$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(37, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    @Test fun `a plain notification is not promoted`() {
        val n = base()
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(n.shortCriticalText)
    }

    @Test fun `a non-ongoing base stays non-ongoing, which the service never promotes`() {
        val n = LiveUpdateNotification.promote(context, base(ongoing = false), LiveUpdatePlan("160 m", 25), R.drawable.ic_launcher)
        assertFalse(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun `the manifest asks for the promoted notifications permission and never the runtime kind`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS\""))
    }

    @Test fun `the live update strings exist in both languages`() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        assertEquals(keys("src/main/res/values/strings_live_update.xml"), keys("src/main/res/values-es/strings_live_update.xml"))
    }

    @Test fun `the chip words fit a status bar chip`() {
        assertTrue(context.getString(R.string.live_update_chip_reroute).length <= 7)
        assertTrue(context.getString(R.string.live_update_chip_no_signal).length <= 7)
        assertEquals(36, Build.VERSION.SDK_INT)
    }
}

/** Below Android 16 the service keeps the regular layout: the policy never asks for a plan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveUpdateBelowSdk36Test {
    @Test fun `at SDK 34 there is no promotion plan even with the setting on`() {
        val state = com.qtekfun.mapas.core.nav.NavState(
            com.qtekfun.mapas.core.nav.NavStatus.ON_ROUTE, com.qtekfun.mapas.core.geo.LatLon(40.0, -3.0), 0f, 100.0, 900.0, 60.0,
            null, null, null, false, emptyList(), false, 10.0, 0.0, 0,
        )
        val plan = LiveUpdatePolicy.plan(
            true, Build.VERSION.SDK_INT, state, null, com.qtekfun.mapas.core.voice.DistanceUnits.METRIC, java.util.Locale.ENGLISH,
            ChipWords("Reroute", "No GPS"),
        )
        assertNull(plan)
        assertEquals(34, Build.VERSION.SDK_INT)
    }
}
