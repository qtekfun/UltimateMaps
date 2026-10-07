package com.qtekfun.mapas.core.voice

import com.qtekfun.mapas.core.nav.Announcement
import com.qtekfun.mapas.core.nav.AnnouncementKind
import com.qtekfun.mapas.core.nav.NavEvent
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.nav.NavigationController
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Connects what the follower announces to the voice. It owns no thread and no engine: it collects three flows in
 * [scope] and calls [guide]. What it says, and when:
 *
 * - every [Announcement] (FAR -> LOW, NEAR -> NORMAL, NOW -> URGENT priority) as an instruction sentence, unless
 *   voice is off, or "only important prompts" is on and the prompt is not important ([isImportant]);
 * - "You have left the route" when the status becomes OFF_ROUTE and "Recalculating" when it becomes REROUTING, at
 *   most once every [problemGapMillis] (a failing reroute cycles OFF_ROUTE/REROUTING every few seconds);
 * - "Stop reached" for an intermediate stop;
 * - "You have arrived" when the status becomes ARRIVED, unless the arrival maneuver was just announced (so it is
 *   not said twice).
 *
 * Turning the voice off, or ending the navigation (also [close]), stops what is being said, but not the arrival
 * message: the service ends the navigation right after arriving.
 *
 * Wiring (see `docs/phase2/voice.md`): `VoiceNavigationController.of(app.navigation, scope, guide, settings)`
 * and [start] when a navigation starts or resumes; [close] when it ends or the service is destroyed.
 */
class VoiceNavigationController(
    private val scope: CoroutineScope,
    private val announcements: Flow<Announcement>,
    private val events: Flow<NavEvent>,
    private val state: Flow<NavState?>,
    private val guide: VoiceGuide,
    private val settings: StateFlow<NavSettings>,
    private val locale: () -> Locale = Locale::getDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val problemGapMillis: Long = 20_000L,
) : AutoCloseable {
    private var jobs: List<Job> = emptyList()
    private var lastProblemAt: Long? = null
    private var arrivalAnnounced = false
    private var lastStatus: NavStatus? = null

    /** Idempotent. Warms the engine up so the first prompt does not wait for it. */
    fun start() {
        if (jobs.isNotEmpty()) return
        arrivalAnnounced = false
        recalculatingDue = false
        lastProblemAt = null
        lastStatus = null
        if (settings.value.voiceEnabled) guide.prepare(language())
        guide.setVolume(settings.value.volumePercent)
        jobs = listOf(
            scope.launch { announcements.collect(::onAnnouncement) },
            scope.launch { events.collect(::onEvent) },
            scope.launch { state.map { it?.status }.distinctUntilChanged().collect(::onStatus) },
            scope.launch {
                settings.distinctUntilChanged { a, b -> a.voiceEnabled == b.voiceEnabled && a.volumePercent == b.volumePercent }.drop(1).collect {
                    guide.setVolume(it.volumePercent)
                    if (!it.voiceEnabled) guide.stop() else guide.prepare(language())
                }
            },
        )
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        // After arriving the service shuts everything down at once: let "You have arrived" finish.
        if (lastStatus != NavStatus.ARRIVED) guide.stop()
    }

    private fun language() = settings.value.voiceLanguage.resolve(locale())

    private fun units() = settings.value.units.resolve(locale())

    private fun onAnnouncement(a: Announcement) {
        val s = settings.value
        if (!s.voiceEnabled) return
        if (s.importantOnly && !a.isImportant()) return
        val lang = language()
        val priority = when (a.kind) {
            AnnouncementKind.NOW -> VoicePriority.URGENT
            AnnouncementKind.NEAR -> VoicePriority.NORMAL
            AnnouncementKind.FAR -> VoicePriority.LOW
        }
        val arrival = a.maneuver.type.let { it == TurnType.ARRIVE || it == TurnType.ARRIVE_LEFT || it == TurnType.ARRIVE_RIGHT }
        if (arrival && a.kind == AnnouncementKind.NOW) arrivalAnnounced = true
        guide.speak(Utterance(InstructionText.of(a, units(), lang), priority, lang))
    }

    private fun onEvent(e: NavEvent) {
        if (!settings.value.voiceEnabled) return
        if (e is NavEvent.StopReached) say(VoiceMessage.STOP_REACHED, VoicePriority.NORMAL, "stop")
    }

    private fun onStatus(status: NavStatus?) {
        val previous = lastStatus
        lastStatus = status
        if (!settings.value.voiceEnabled) return
        when (status) {
            NavStatus.OFF_ROUTE -> sayProblem(VoiceMessage.OFF_ROUTE)
            NavStatus.REROUTING -> sayProblem(VoiceMessage.RECALCULATING)
            NavStatus.ARRIVED -> if (!arrivalAnnounced) say(VoiceMessage.ARRIVED, VoicePriority.URGENT, "arrived")
            null -> if (previous != null && previous != NavStatus.ARRIVED) guide.stop()
            else -> Unit
        }
    }

    /** "Left the route" then, right after, "Recalculating"; nothing again until [problemGapMillis] has passed. */
    private fun sayProblem(m: VoiceMessage) {
        val now = clock()
        val inCycle = lastProblemAt?.let { now - it < problemGapMillis } ?: false
        if (m == VoiceMessage.OFF_ROUTE) {
            if (inCycle) return
            lastProblemAt = now
            recalculatingDue = true
        } else {
            if (!recalculatingDue && inCycle) return
            if (!recalculatingDue) lastProblemAt = now
            recalculatingDue = false
        }
        say(m, VoicePriority.NORMAL, "route-problem")
    }

    private var recalculatingDue = false

    private fun say(m: VoiceMessage, priority: VoicePriority, key: String) {
        val lang = language()
        guide.speak(Utterance(InstructionText.of(m, lang), priority, lang, key))
    }

    companion object {
        /** The usual wiring: everything comes from the application's [NavigationController]. */
        fun of(
            controller: NavigationController,
            scope: CoroutineScope,
            guide: VoiceGuide,
            settings: StateFlow<NavSettings>,
            locale: () -> Locale = Locale::getDefault,
        ) = VoiceNavigationController(scope, controller.announcements, controller.events, controller.state, guide, settings, locale)
    }
}
