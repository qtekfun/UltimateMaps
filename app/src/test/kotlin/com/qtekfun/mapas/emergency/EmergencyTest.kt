package com.qtekfun.mapas.emergency

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EmergencyTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val madrid = LatLon(40.41689, -3.70351)

    // --- Text and intents ---

    @Test fun coordinatesUseFiveDecimalsWithADotWhateverTheLocale() {
        assertEquals("40.41689, -3.70351", EmergencyText.coordinates(madrid))
        assertEquals("-0.00001, 0.00000", EmergencyText.coordinates(LatLon(-0.00001, 0.0)))
    }

    @Test fun theShareTextHasTheHeaderTheCoordinatesAndAGeoLink() {
        val text = EmergencyText.shareText("My position (emergency):", madrid)
        assertEquals(
            listOf("My position (emergency):", "40.41689, -3.70351", "geo:40.416890,-3.703510"),
            text.lines(),
        )
    }

    @Test fun dialOpensTheDialerWith112AndDoesNotCall() {
        val i = EmergencyIntents.dial()
        assertEquals(Intent.ACTION_DIAL, i.action) // ACTION_CALL would place the call and need CALL_PHONE
        assertEquals("tel:112", i.dataString)
    }

    @Test fun shareGoesThroughTheSystemChooserAsPlainText() {
        val chooser = EmergencyIntents.share("hello")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("hello", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun theAppRequestsNeitherCallPhoneNorSmsPermissions() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.CALL_PHONE" in requested)
        assertFalse("android.permission.SEND_SMS" in requested)
        assertFalse("android.permission.READ_SMS" in requested)
    }

    // --- Controller ---

    private fun controller(source: SimulatedLocationSource, permission: Boolean = true, provider: Boolean = true) =
        EmergencyController(source, hasPermission = { permission }, providerAvailable = { provider })

    @Test fun theControllerShowsTheLastKnownFixAtOnceThenLiveFixes() {
        val source = SimulatedLocationSource()
        source.emit(LocationFix(madrid, accuracyMeters = 12f))
        val c = controller(source)
        c.start()
        assertEquals(madrid, c.state.position)
        assertEquals(12f, c.state.accuracyMeters)
        assertTrue(source.isStarted)

        val moved = LatLon(40.5, -3.7)
        source.emit(LocationFix(moved))
        assertEquals(moved, c.state.position)
        assertNull(c.state.accuracyMeters)

        c.stop()
        assertFalse(source.isStarted)
    }

    @Test fun withoutPermissionItDoesNotStartTheSourceAndSaysSo() {
        val source = SimulatedLocationSource()
        val c = controller(source, permission = false)
        c.start()
        assertFalse(c.state.permissionGranted)
        assertFalse(source.isStarted)
        assertNull(c.state.position)
    }

    @Test fun withoutAProviderItDoesNotStartTheSourceEither() {
        val source = SimulatedLocationSource()
        val c = controller(source, provider = false)
        c.start()
        assertFalse(c.state.providerAvailable)
        assertFalse(source.isStarted)
    }

    // --- Screen ---

    private var dials = 0
    private var shares = 0
    private var grants = 0
    private var backs = 0

    private fun show(state: EmergencyState) = rule.setContent {
        MapasTheme(darkTheme = false) {
            EmergencyScreen(state, onDial = { dials++ }, onShare = { shares++ }, onGrantLocation = { grants++ }, onBack = { backs++ })
        }
    }

    @Test fun theScreenShowsTheCoordinatesAndAccuracyAndBothButtonsWork() {
        val state = EmergencyState().also { it.position = madrid; it.accuracyMeters = 8.4f }
        show(state)
        rule.onNodeWithTag("emergency_coords").assertIsDisplayed()
        rule.onNodeWithTag("emergency_coords").assertTextEquals("40.41689, -3.70351")
        rule.onNodeWithTag("emergency_accuracy").assertTextEquals("Accurate to about 8 m")
        rule.onNodeWithTag("emergency_call").assertTextEquals("Call 112")
        rule.onNodeWithTag("emergency_call").performClick()
        rule.onNodeWithTag("emergency_share").assertIsEnabled()
        rule.onNodeWithTag("emergency_share").performClick()
        rule.onNodeWithTag("emergency_back").performClick()
        assertEquals(listOf(1, 1, 1), listOf(dials, shares, backs))
    }

    @Test fun whileWaitingForAFixSharingIsDisabledButCallingIsNot() {
        show(EmergencyState())
        rule.onNodeWithTag("emergency_status").assertIsDisplayed()
        rule.onNodeWithTag("emergency_share").assertIsNotEnabled()
        rule.onNodeWithTag("emergency_call").assertIsEnabled()
    }

    @Test fun withoutPermissionTheScreenOffersToGrantIt() {
        show(EmergencyState().also { it.permissionGranted = false })
        rule.onNodeWithTag("emergency_grant").performClick()
        assertEquals(1, grants)
        rule.onNodeWithTag("emergency_call").assertIsEnabled() // calling never depends on the position
    }

    @Test fun withoutAProviderThereIsNoGrantButton() {
        show(EmergencyState().also { it.providerAvailable = false; it.permissionGranted = false })
        rule.onAllNodesWithTag("emergency_grant").assertCountEquals(0)
        rule.onNodeWithTag("emergency_status").assertIsDisplayed()
    }

    @Test fun aMissingDialerIsReported() {
        show(EmergencyState().also { it.dialFailed = true })
        rule.onNodeWithTag("emergency_dial_failed").assertIsDisplayed()
    }

    @Test fun thePrivacyNoteIsAlwaysThere() {
        show(EmergencyState())
        assertNotNull(rule.onNodeWithTag("emergency_note"))
        rule.onNodeWithTag("emergency_note").assertIsDisplayed()
    }
}
