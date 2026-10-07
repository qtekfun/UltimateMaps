package com.qtekfun.mapas.transit.follow

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.core.transit.follow.ConnectionStatus
import com.qtekfun.mapas.core.transit.follow.FollowBasis
import com.qtekfun.mapas.core.transit.follow.FollowPhase
import com.qtekfun.mapas.core.transit.follow.PlanStatus
import com.qtekfun.mapas.core.voice.DistanceUnits
import com.qtekfun.mapas.transit.follow.TripTestSupport.T0
import com.qtekfun.mapas.transit.follow.TripTestSupport.follow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitTripTextsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val en = context.resources
    private val es = context.createConfigurationContext(Configuration().apply { setLocale(Locale("es")) }).resources
    private val zone = ZoneId.of(TripTestSupport.ZONE)
    private fun text(s: com.qtekfun.mapas.core.transit.follow.FollowState, r: android.content.res.Resources = en) =
        TransitTripTexts.instruction(r, s, zone, if (r === es) Locale("es") else Locale.ENGLISH)

    @Test fun walkingToTheFirstStopSaysWhereHowFarAndWhatComesNext() {
        val t = text(follow(FollowPhase.BEFORE_START) {
            copy(targetName = "Alpha", walkMeters = 520, walkSeconds = 416, line = TripTestSupport.metro, boardAt = T0 + 600)
        })
        assertEquals("Walk to Alpha", t.title)
        assertTrue(t.detail!!.startsWith("520 m · 7 min"), t.detail)
        assertTrue(t.detail!!.contains("Then line L5 at"), t.detail)
    }

    @Test fun waitingNamesTheLineTheDirectionAndWhenItLeaves() {
        val s = follow(FollowPhase.WAITING) { copy(line = TripTestSupport.metro, headsign = "Westbound", boardAt = T0 + 600, secondsToBoard = 240) }
        val t = text(s)
        assertEquals("Board line L5 towards Westbound", t.title)
        assertTrue(t.detail!!.contains("in 4 min"), t.detail)
        assertTrue(text(s.copy(secondsToBoard = 10)).detail!!.endsWith("now"))
        assertTrue(text(s.copy(secondsToBoard = -120)).detail!!.startsWith("Was due"))
        assertEquals("Board line L5", text(s.copy(headsign = null)).title)
    }

    @Test fun ridingSaysTheNextStopAndHowManyAreLeft() {
        val s = follow(FollowPhase.ON_BOARD) { copy(nextStopName = "Bravo", stopsRemaining = 4, alightName = "Echo", line = TripTestSupport.metro) }
        val t = text(s)
        assertEquals("Next stop: Bravo", t.title)
        assertEquals("Get off at Echo in 4 stops", t.detail)
        assertEquals("Get off at Echo in 1 stop", text(s.copy(stopsRemaining = 1)).detail)
    }

    @Test fun theLastStopSaysGetOffAtTheNextStop() {
        val t = text(follow(FollowPhase.ALIGHT_NEXT) { copy(alightName = "Echo", alightAt = T0 + 1170, stopsRemaining = 1) })
        assertEquals("Get off at the next stop: Echo", t.title)
        assertTrue(t.detail!!.startsWith("Due at "))
    }

    @Test fun aTransferNamesTheNextLineWalkAndDeparture() {
        val s = follow(FollowPhase.TRANSFER) {
            copy(line = TripTestSupport.bus, headsign = "Hospital", targetName = "Foxtrot", walkMeters = 280, walkSeconds = 180, boardAt = T0 + 1500)
        }
        val t = text(s)
        assertEquals("Change here: line 27 towards Hospital", t.title)
        assertTrue(t.detail!!.startsWith("Walk to Foxtrot · 280 m · 3 min · departs "), t.detail)
        val same = text(s.copy(walkMeters = 20))
        assertTrue(same.detail!!.startsWith("Foxtrot · departs"), same.detail)
    }

    @Test fun offPlanAndArrivalHaveTheirOwnWords() {
        assertEquals("You are off the plan", text(follow(FollowPhase.OFF_PLAN)).title)
        assertEquals("Plan a new trip from where you are", text(follow(FollowPhase.OFF_PLAN)).detail)
        assertEquals("You have arrived", text(follow(FollowPhase.ARRIVED)).title)
        assertEquals("Walk to your destination", text(follow(FollowPhase.FINAL_WALK) { copy(walkMeters = 130, walkSeconds = 100) }).title)
    }

    @Test fun spanishTextsAreUsedInSpanish() {
        val s = follow(FollowPhase.ON_BOARD) { copy(nextStopName = "Bravo", stopsRemaining = 3, alightName = "Echo") }
        assertEquals("Próxima parada: Bravo", text(s, es).title)
        assertEquals("Baja en Echo dentro de 3 paradas", text(s, es).detail)
        assertEquals("Estás fuera del itinerario", text(follow(FollowPhase.OFF_PLAN), es).title)
    }

    @Test fun thePlanChipSaysOnPlanBehindOrAheadWithMinutes() {
        assertNull(TransitTripTexts.planChip(en, follow(FollowPhase.ON_BOARD)), "nothing to compare yet")
        assertEquals("On plan", TransitTripTexts.planChip(en, follow(FollowPhase.ON_BOARD) { copy(planOffsetSec = 20) }))
        assertEquals("About 3 min behind plan", TransitTripTexts.planChip(en, follow(FollowPhase.ON_BOARD) { copy(planOffsetSec = 200, plan = PlanStatus.BEHIND, planMinutes = 3) }))
        assertEquals("About 2 min ahead of plan", TransitTripTexts.planChip(en, follow(FollowPhase.ON_BOARD) { copy(planOffsetSec = -120, plan = PlanStatus.AHEAD, planMinutes = 2) }))
        assertEquals("On plan (estimate)", TransitTripTexts.planChip(en, follow(FollowPhase.ON_BOARD) { copy(basis = FollowBasis.ESTIMATED, planOffsetSec = null) }))
        assertEquals("Unos 3 min de retraso sobre el plan", TransitTripTexts.planChip(es, follow(FollowPhase.ON_BOARD) { copy(planOffsetSec = 200, plan = PlanStatus.BEHIND, planMinutes = 3) }))
    }

    @Test fun signalAndConnectionLines() {
        assertNull(TransitTripTexts.signal(en, follow(FollowPhase.ON_BOARD)))
        assertEquals("No GPS: position estimated from the timetable", TransitTripTexts.signal(en, follow(FollowPhase.ON_BOARD) { copy(basis = FollowBasis.ESTIMATED) }))
        assertEquals("Waiting for GPS signal", TransitTripTexts.signal(en, follow(FollowPhase.BEFORE_START) { copy(basis = FollowBasis.NO_SIGNAL) }))
        val risky = follow(FollowPhase.ON_BOARD) { copy(connection = ConnectionStatus.AT_RISK, connectionLine = "27") }
        assertEquals("Connection to line 27 at risk", TransitTripTexts.connection(en, risky))
        assertEquals("Line 27 may be missed", TransitTripTexts.connection(en, risky.copy(connection = ConnectionStatus.MISSED)))
        assertNull(TransitTripTexts.connection(en, risky.copy(connection = ConnectionStatus.OK)))
        assertNull(TransitTripTexts.connection(en, follow(FollowPhase.WAITING) { copy(connection = ConnectionStatus.AT_RISK, connectionLine = "27") }))
    }

    @Test fun theChipFitsAboutSevenCharactersAndNeverHoldsAPosition() {
        val units = DistanceUnits.METRIC
        fun chip(s: com.qtekfun.mapas.core.transit.follow.FollowState, now: Long = T0) = TransitTripTexts.chip(en, s, Locale.ENGLISH, units, now)
        assertEquals("2 stops", chip(follow(FollowPhase.ON_BOARD) { copy(stopsRemaining = 2) }))
        assertEquals("1 stop", chip(follow(FollowPhase.ON_BOARD) { copy(stopsRemaining = 1) }))
        assertEquals("Get off", chip(follow(FollowPhase.ALIGHT_NEXT)))
        assertEquals("4 min", chip(follow(FollowPhase.WAITING) { copy(boardAt = T0 + 240) }))
        assertEquals("520 m", chip(follow(FollowPhase.BEFORE_START) { copy(walkMeters = 520) }))
        assertEquals("Re-plan", chip(follow(FollowPhase.OFF_PLAN)))
        assertNull(chip(follow(FollowPhase.ARRIVED)))
        for (s in listOf("2 stops", "Get off", "4 min", "520 m", "Re-plan")) assertTrue(s.length <= 8)
    }

    @Test fun theNotificationHoldsHeadlineDetailAndPlanButNoPosition() {
        val s = follow(FollowPhase.ON_BOARD) {
            copy(nextStopName = "Bravo", stopsRemaining = 4, alightName = "Echo", planOffsetSec = 200, plan = PlanStatus.BEHIND, planMinutes = 3)
        }
        val c = TransitTripNotificationTexts.of(en, TripTestSupport.trip(s), Locale.ENGLISH)
        assertEquals("Next stop: Bravo", c.title)
        assertEquals("Get off at Echo in 4 stops · About 3 min behind plan", c.text)
        assertTrue(TransitTripNotificationTexts.of(en, null, Locale.ENGLISH).text.isNotBlank())
        assertTrue(TransitTripNotificationTexts.key(TripTestSupport.trip(s)) != TransitTripNotificationTexts.key(TripTestSupport.trip(s.copy(nextStopIndex = 2))))
    }

    @Test fun theLiveUpdateChipOnlyExistsOnAndroid16WithTheSwitchOnAndATripInProgress() {
        val trip = TripTestSupport.trip(follow(FollowPhase.ON_BOARD) { copy(stopsRemaining = 3) })
        fun plan(enabled: Boolean = true, sdk: Int = 36, t: com.qtekfun.mapas.core.transit.follow.TransitTripState? = trip, now: Long = T0 + 900) =
            TransitTripLiveUpdate.plan(enabled, sdk, t, en, DistanceUnits.METRIC, Locale.ENGLISH, now)
        assertEquals("3 stops", plan()!!.chipText)
        assertTrue(kotlin.math.abs(plan()!!.progressPercent - 47) <= 1, "900 of 1920 scheduled seconds")
        assertNull(plan(enabled = false))
        assertNull(plan(sdk = 35))
        assertNull(plan(t = null))
        assertNull(plan(t = TripTestSupport.trip(follow(FollowPhase.ARRIVED))))
        assertEquals(0, plan(now = T0 - 500)!!.progressPercent)
        assertEquals(100, plan(now = T0 + 99_999)!!.progressPercent)
    }

    @Test fun stringsExistInBothLanguagesWithTheSameFormatArguments() {
        fun entries(f: String) = Regex("<(string|plurals) name=\"([^\"]+)\"").findAll(File(f).readText()).map { it.groupValues[2] }.toSet()
        assertEquals(entries("src/main/res/values/strings_transit_follow.xml"), entries("src/main/res/values-es/strings_transit_follow.xml"))
        fun args(f: String) = Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(File(f).readText())
            .associate { it.groupValues[1] to Regex("%\\d\\$[sd]").findAll(it.groupValues[2]).map { m -> m.value }.toSortedSet() }
        assertEquals(args("src/main/res/values/strings_transit_follow.xml"), args("src/main/res/values-es/strings_transit_follow.xml"))
    }
}
