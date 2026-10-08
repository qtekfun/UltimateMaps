package com.qtekfun.ultimatemaps.transit

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealTimeTextsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val en = context.resources
    private val es = context.createConfigurationContext(Configuration().apply { setLocale(Locale("es")) }).resources

    @Test fun delayEarlyOnTimeAndCancelled() {
        assertEquals("Delayed 4 min", RealTimeTexts.status(en, LegRealTime(delaySec = 240))!!.text)
        assertEquals(RealTimeTexts.Level.LATE, RealTimeTexts.status(en, LegRealTime(delaySec = 240))!!.level)
        assertEquals("Delayed 1 min", RealTimeTexts.status(en, LegRealTime(delaySec = 60))!!.text)
        assertEquals("2 min early", RealTimeTexts.status(en, LegRealTime(delaySec = -120))!!.text)
        assertEquals("On time", RealTimeTexts.status(en, LegRealTime(delaySec = 45))!!.text)
        assertEquals("On time", RealTimeTexts.status(en, LegRealTime(delaySec = 0))!!.text)
        val cancelled = RealTimeTexts.status(en, LegRealTime(cancelled = true))!!
        assertEquals("Cancelled", cancelled.text)
        assertEquals(RealTimeTexts.Level.CANCELLED, cancelled.level)
    }

    @Test fun nothingKnownSaysNothing() {
        assertNull(RealTimeTexts.status(en, null))
        assertNull(RealTimeTexts.status(en, LegRealTime(alerts = listOf("x"))), "alerts alone are not a train status")
        assertTrue(RealTimeTexts.details(en, null).isEmpty())
    }

    @Test fun alertsAndSkippedStopsAreListedAndLongAlertsCut() {
        val long = "x".repeat(500)
        val d = RealTimeTexts.details(en, LegRealTime(alerts = listOf("Obras en la vía", long), skippedStops = listOf("Sol")))
        assertEquals("Does not stop at Sol", d[0])
        assertEquals("Alert: Obras en la vía", d[1])
        assertTrue(d[2].length < 230 && d[2].endsWith("…"))
    }

    @Test fun theNoteIsRealTimeOnlyWhenTheTrainIsInTheFeed() {
        assertEquals("Real time (Renfe)", RealTimeTexts.note(en, LegRealTime(delaySec = 0), R.string.trip_scheduled_note))
        assertEquals("Real time (Renfe)", RealTimeTexts.note(en, LegRealTime(cancelled = true), R.string.trip_scheduled_note))
        assertEquals("Scheduled times, no real-time data", RealTimeTexts.note(en, null, R.string.trip_scheduled_note))
        assertEquals("Scheduled times, no real-time data", RealTimeTexts.note(en, LegRealTime(alerts = listOf("a")), R.string.trip_scheduled_note))
    }

    @Test fun spanishTexts() {
        assertEquals("Retraso de 4 min", RealTimeTexts.status(es, LegRealTime(delaySec = 240))!!.text)
        assertEquals("Cancelado", RealTimeTexts.status(es, LegRealTime(cancelled = true))!!.text)
        assertEquals("Puntual", RealTimeTexts.status(es, LegRealTime(delaySec = 0))!!.text)
        assertEquals("Aviso: Obras", RealTimeTexts.details(es, LegRealTime(alerts = listOf("Obras")))[0])
        assertEquals("Tiempo real (Renfe)", RealTimeTexts.note(es, LegRealTime(delaySec = 0), R.string.trip_scheduled_note))
    }

    @Test fun theSettingsNoteSaysWhatIsAskedAndWhatIsNotSent() {
        val body = en.getString(R.string.transit_rt_body)
        assertEquals("Asks Renfe's server for train delays while you use public transport. Your position is never sent; Renfe sees your IP address.", body)
        assertTrue(es.getString(R.string.transit_rt_body).contains("posición"))
    }
}
