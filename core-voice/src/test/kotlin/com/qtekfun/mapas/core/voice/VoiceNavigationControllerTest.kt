package com.qtekfun.mapas.core.voice

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.nav.Announcement
import com.qtekfun.mapas.core.nav.AnnouncementKind
import com.qtekfun.mapas.core.nav.NavEvent
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNavigationControllerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 16)
    private val events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
    private val state = MutableStateFlow<NavState?>(null)
    private val settings = InMemoryNavSettingsStore()
    private val guide = RecordingGuide()
    private var now = 1_000_000L
    private var locale = Locale.forLanguageTag("es-ES")
    private val controller = VoiceNavigationController(scope, announcements, events, state, guide, settings.settings, { locale }, { now })

    private fun navState(status: NavStatus) = NavState(
        status, LatLon(0.0, 0.0), 0f, 0.0, 100.0, 10.0, null, null, null, false, emptyList(), false, 0.0, 0.0, 0,
    )

    private fun ann(type: TurnType, kind: AnnouncementKind, meters: Int, street: String? = null) =
        Announcement(Maneuver(0, type, street), kind, meters)

    private fun emit(a: Announcement) { announcements.tryEmit(a); scope.runCurrent() }
    private fun status(s: NavStatus?) { state.value = s?.let(::navState); scope.runCurrent() }

    @Test fun announcementsBecomeSentencesWithTheRightPriority() {
        controller.start(); scope.runCurrent()
        emit(ann(TurnType.LEFT, AnnouncementKind.FAR, 500, "Calle Mayor"))
        emit(ann(TurnType.LEFT, AnnouncementKind.NEAR, 100, "Calle Mayor"))
        emit(ann(TurnType.LEFT, AnnouncementKind.NOW, 10, "Calle Mayor"))
        assertEquals(
            listOf("En 500 metros, gira a la izquierda en Calle Mayor", "En 100 metros, gira a la izquierda en Calle Mayor", "Ahora, gira a la izquierda en Calle Mayor"),
            guide.texts,
        )
        assertEquals(listOf(VoicePriority.LOW, VoicePriority.NORMAL, VoicePriority.URGENT), guide.spoken.map { it.priority })
        assertTrue(guide.spoken.all { it.language == VoiceLanguage.ES })
    }

    @Test fun unitsAndLanguageComeFromTheSettingsAndTheLocale() {
        locale = Locale.US
        controller.start(); scope.runCurrent()
        emit(ann(TurnType.RIGHT, AnnouncementKind.FAR, 805))
        assertEquals(listOf("In 0.5 miles, turn right"), guide.texts)
        settings.update { it.copy(units = UnitsPref.METRIC, voiceLanguage = VoiceLanguagePref.ES) }
        scope.runCurrent()
        emit(ann(TurnType.RIGHT, AnnouncementKind.FAR, 805))
        assertEquals("En 800 metros, gira a la derecha", guide.texts.last())
    }

    @Test fun startWarmsTheEngineUpAndSetsTheVolume() {
        settings.update { it.copy(volumePercent = 50) }
        controller.start(); scope.runCurrent()
        assertEquals(listOf(VoiceLanguage.ES), guide.prepared)
        assertEquals(50, guide.lastVolumeSet)
        settings.update { it.copy(volumePercent = 75) }; scope.runCurrent()
        assertEquals(75, guide.lastVolumeSet)
    }

    @Test fun voiceOffSaysNothingAndSilencesWhatWasBeingSaid() {
        controller.start(); scope.runCurrent()
        val before = guide.stops
        settings.update { it.copy(voiceEnabled = false) }; scope.runCurrent()
        assertEquals(before + 1, guide.stops)
        emit(ann(TurnType.LEFT, AnnouncementKind.NOW, 10))
        events.tryEmit(NavEvent.StopReached(0, LatLon(0.0, 0.0))); scope.runCurrent()
        status(NavStatus.OFF_ROUTE)
        assertTrue(guide.spoken.isEmpty())
    }

    @Test fun onlyImportantPromptsFilter() {
        settings.update { it.copy(importantOnly = true) }
        controller.start(); scope.runCurrent()
        emit(ann(TurnType.STRAIGHT, AnnouncementKind.NEAR, 100))
        emit(ann(TurnType.LEFT, AnnouncementKind.FAR, 500))
        emit(ann(TurnType.LEFT, AnnouncementKind.NEAR, 100))
        assertEquals(listOf("En 100 metros, gira a la izquierda"), guide.texts)
    }

    @Test fun leavingTheRouteThenRecalculating() {
        controller.start(); scope.runCurrent()
        status(NavStatus.ON_ROUTE)
        status(NavStatus.OFF_ROUTE)
        status(NavStatus.REROUTING)
        assertEquals(listOf("Has salido de la ruta", "Recalculando"), guide.texts)
        assertTrue(guide.spoken.all { it.priority == VoicePriority.NORMAL && it.key == "route-problem" })
    }

    @Test fun aFailingRerouteCycleIsNotRepeatedEverySecond() {
        controller.start(); scope.runCurrent()
        status(NavStatus.OFF_ROUTE); status(NavStatus.REROUTING)
        now += 8_000
        status(NavStatus.OFF_ROUTE); status(NavStatus.REROUTING) // cooldown ended, tried again
        assertEquals(2, guide.spoken.size)
        now += 30_000
        status(NavStatus.ON_ROUTE)
        status(NavStatus.OFF_ROUTE); status(NavStatus.REROUTING)
        assertEquals(listOf("Has salido de la ruta", "Recalculando", "Has salido de la ruta", "Recalculando"), guide.texts)
    }

    @Test fun anIntermediateStopIsAnnounced() {
        controller.start(); scope.runCurrent()
        events.tryEmit(NavEvent.StopReached(0, LatLon(1.0, 1.0)))
        events.tryEmit(NavEvent.StopSkipped(1, LatLon(2.0, 2.0)))
        scope.runCurrent()
        assertEquals(listOf("Parada alcanzada"), guide.texts)
    }

    @Test fun arrivalIsSaidOnceWhetherItComesFromTheManeuverOrTheStatus() {
        controller.start(); scope.runCurrent()
        emit(ann(TurnType.ARRIVE, AnnouncementKind.NOW, 5))
        status(NavStatus.ARRIVED)
        assertEquals(listOf("Has llegado a tu destino"), guide.texts)
    }

    @Test fun arrivalFromTheStatusAloneIsSaid() {
        controller.start(); scope.runCurrent()
        status(NavStatus.ON_ROUTE)
        status(NavStatus.ARRIVED)
        assertEquals(listOf("Has llegado a tu destino"), guide.texts)
        assertEquals(VoicePriority.URGENT, guide.spoken.single().priority)
        val stops = guide.stops
        status(null) // the service ends the navigation right after arriving: do not cut the message
        assertEquals(stops, guide.stops)
    }

    @Test fun endingTheNavigationInTheMiddleSilencesTheVoice() {
        controller.start(); scope.runCurrent()
        status(NavStatus.ON_ROUTE)
        val stops = guide.stops
        status(null)
        assertEquals(stops + 1, guide.stops)
    }

    @Test fun closeStopsListeningAndSilences() {
        controller.start(); scope.runCurrent()
        controller.close()
        val stops = guide.stops
        emit(ann(TurnType.LEFT, AnnouncementKind.NOW, 10))
        assertTrue(guide.spoken.isEmpty())
        assertTrue(stops >= 1)
    }

    @Test fun startTwiceDoesNotSpeakEverythingTwice() {
        controller.start(); controller.start(); scope.runCurrent()
        emit(ann(TurnType.LEFT, AnnouncementKind.NOW, 10))
        assertEquals(1, guide.spoken.size)
    }
}
