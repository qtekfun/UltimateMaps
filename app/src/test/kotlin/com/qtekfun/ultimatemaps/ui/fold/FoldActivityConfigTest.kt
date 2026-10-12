package com.qtekfun.ultimatemaps.ui.fold

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding, unfolding, rotating and resizing the window must not recreate the activity: the navigation screen, the search
 * state, the sheet detent and the map camera live in it. The manifest declares that it handles these changes itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoldActivityConfigTest {
    private val info: ActivityInfo by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
    }

    @Test fun `the main activity handles the configuration changes of a fold itself`() {
        val needed = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_UI_MODE or
            ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        assertEquals(needed, info.configChanges and needed, "configChanges ${Integer.toHexString(info.configChanges)}")
    }

    @Test fun `no activity opts out of resizing or locks the orientation, so split-screen and the fold work`() {
        // The manifest source (the unit test runs in the module directory): the hidden resizeMode of ActivityInfo is not public API.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("resizeableActivity=\"false\"" !in manifest, "an activity is not resizable")
        assertTrue("screenOrientation" !in manifest, "an activity locks the orientation")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
    }
}
