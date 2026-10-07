package com.qtekfun.mapas.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The director over fake engine, focus and clock: all timing is virtual, so nothing here depends on real time. */
class SpeechDirectorTest {
    private val sched = ManualScheduler()
    private val focus = FakeFocus()
    private val log = ArrayList<String>()
    private val created = ArrayList<FakeEngine>()
    private var configure: (FakeEngine) -> Unit = {}
    private val config = DirectorConfig()
    private val director = SpeechDirector(
        engines = { pkg -> FakeEngine(pkg, log).also { configure(it); created += it } },
        audio = focus,
        scheduler = sched,
        config = config,
        clock = sched.clock,
    )
    private val engine get() = created.last()

    private fun u(text: String, p: VoicePriority = VoicePriority.NORMAL, key: String? = null, lang: VoiceLanguage = VoiceLanguage.ES) =
        Utterance(text, p, lang, key)

    private fun speak(vararg us: Utterance) { us.forEach(director::speak); sched.idle() }

    // ---- Queue

    @Test fun speaksAndThenReleasesTheFocusAfterAShortHold() {
        speak(u("uno"))
        assertEquals(listOf("uno"), engine.texts)
        assertTrue(focus.held)
        engine.finish()
        sched.idle()
        assertTrue(focus.held, "the focus is kept a moment for a prompt that follows")
        sched.advance(config.focusReleaseDelayMillis + 1)
        assertFalse(focus.held)
    }

    @Test fun backToBackPromptsKeepTheSameFocus() {
        speak(u("uno"))
        engine.finish(); sched.advance(300)
        speak(u("dos"))
        sched.advance(config.focusReleaseDelayMillis * 2)
        assertTrue(focus.held, "dos is still being said")
        assertEquals(0, focus.abandons)
    }

    @Test fun normalPromptsWaitForTheCurrentOne() {
        speak(u("uno"), u("dos"))
        assertEquals(listOf("uno"), engine.texts)
        engine.finish(); sched.idle()
        assertEquals(listOf("uno", "dos"), engine.texts)
    }

    @Test fun urgentInterruptsAndClearsTheQueue() {
        speak(u("uno"), u("dos"), u("tres"))
        speak(u("ahora", VoicePriority.URGENT))
        assertEquals(listOf("uno", "ahora"), engine.texts)
        engine.finish(); sched.idle()
        assertEquals(listOf("uno", "ahora"), engine.texts, "what was waiting is gone: it is stale after an urgent prompt")
    }

    @Test fun theDoneOfAnInterruptedUtteranceIsIgnored() {
        speak(u("uno"))
        val firstId = engine.spoken[0].first
        speak(u("ahora", VoicePriority.URGENT), u("despues"))
        engine.listener!!.onDone(firstId) // late callback of the interrupted one
        sched.idle()
        assertEquals(listOf("uno", "ahora"), engine.texts, "despues must wait for 'ahora' to finish")
    }

    @Test fun farPromptsDoNotPileUp() {
        speak(u("actual"))
        speak(u("lejos 1", VoicePriority.LOW), u("lejos 2", VoicePriority.LOW), u("lejos 3", VoicePriority.LOW))
        engine.finish(); sched.idle()
        assertEquals(listOf("actual", "lejos 3"), engine.texts)
    }

    @Test fun aNormalPromptDiscardsPendingFarOnesAndInterruptsAFarOneBeingSaid() {
        speak(u("lejos", VoicePriority.LOW))
        speak(u("cerca"))
        assertEquals(listOf("lejos", "cerca"), engine.texts, "the far warning is obsolete once the near one arrives")
        speak(u("otro lejos", VoicePriority.LOW))
        engine.finish(); sched.idle()
        assertEquals(listOf("lejos", "cerca", "otro lejos"), engine.texts)
    }

    @Test fun aKeyReplacesThePendingOneWithTheSameKey() {
        speak(u("actual"))
        speak(u("recalculando", key = "r"), u("otra cosa"), u("recalculando de nuevo", key = "r"))
        engine.finish(); sched.idle(); engine.finish(); sched.idle()
        assertEquals(listOf("actual", "otra cosa", "recalculando de nuevo"), engine.texts)
    }

    @Test fun staleUtterancesAreDroppedAtTheirTurn() {
        speak(u("largo"))
        speak(Utterance("viejo", VoicePriority.NORMAL, VoiceLanguage.ES, maxAgeMillis = 2_000))
        sched.advance(5_000)
        engine.finish(); sched.idle()
        assertEquals(listOf("largo"), engine.texts)
    }

    @Test fun theQueueIsBounded() {
        speak(u("actual"))
        repeat(10) { speak(u("n$it")) }
        engine.finish(); sched.idle()
        repeat(10) { engine.finish(); sched.idle() }
        assertEquals(1 + config.maxPending, engine.texts.size)
        assertEquals("n9", engine.texts.last(), "the newest survive")
    }

    @Test fun blankTextIsNotSpoken() {
        speak(u("   "))
        assertTrue(created.isEmpty() || engine.spoken.isEmpty())
    }

    @Test fun stopSilencesAndClears() {
        speak(u("uno"), u("dos"))
        director.stop(); sched.idle()
        assertEquals(1, engine.stops)
        engine.finish(); sched.idle()
        assertEquals(listOf("uno"), engine.texts)
        sched.advance(config.focusReleaseDelayMillis + 1)
        assertFalse(focus.held)
    }

    @Test fun volumeReachesTheEngine() {
        director.setVolume(40)
        speak(u("uno"))
        assertEquals(0.4f, engine.lastVolume)
    }

    @Test fun nothingIsSaidWhileAnotherAppHoldsTheFocus() {
        focus.grant = false
        speak(u("uno"))
        assertTrue(engine.spoken.isEmpty())
        assertEquals(1, director.droppedWithoutFocus)
    }

    @Test fun losingTheFocusForGoodStopsTheSpeech() {
        speak(u("uno"), u("dos"))
        focus.onLost!!.invoke(); sched.idle()
        assertEquals(1, engine.stops)
        engine.finish(); sched.idle()
        assertEquals(listOf("uno"), engine.texts)
    }

    // ---- Start-up, status and languages

    @Test fun utterancesWaitForAnEngineThatIsStillStarting() {
        configure = { it.initOutcome = null }
        speak(u("uno"))
        assertEquals(VoiceStatus.Starting, director.status.value)
        assertTrue(engine.spoken.isEmpty())
        engine.listener!!.onInit(true); sched.idle()
        assertEquals(listOf("uno"), engine.texts)
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
    }

    @Test fun prepareStartsTheEngineAheadAndReportsTheLanguage() {
        director.prepare(VoiceLanguage.EN); sched.idle()
        assertEquals(VoiceStatus.Ready(VoiceLanguage.EN), director.status.value)
        assertEquals(1, created.size)
    }

    @Test fun noEngineInstalledIsReportedAndNothingIsSpoken() {
        configure = { it.initOutcome = false; it.installed = false }
        speak(u("uno"))
        assertEquals(VoiceStatus.NoEngine, director.status.value)
        assertTrue(director.status.value.isProblem)
        speak(u("dos"))
        assertEquals(1, created.size, "no restart loop for something only the user can fix")
        assertTrue(engine.spoken.isEmpty())
        assertTrue(engine.shutDown)
    }

    @Test fun anEngineInstalledLaterIsFoundWhenTheNavigationStartsAgain() {
        configure = { it.initOutcome = false; it.installed = false }
        director.prepare(VoiceLanguage.ES); sched.idle()
        assertEquals(VoiceStatus.NoEngine, director.status.value)
        configure = {}
        director.prepare(VoiceLanguage.ES); sched.idle()
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
    }

    @Test fun missingLanguageDataIsReportedAfterTryingTheOtherEngines() {
        configure = {
            it.languages = mapOf(VoiceLanguage.ES to LanguageSupport.MISSING_DATA, VoiceLanguage.EN to LanguageSupport.AVAILABLE)
            it.engines = listOf("a", "b")
        }
        director.prepare(VoiceLanguage.ES); sched.idle()
        assertEquals(VoiceStatus.LanguageMissing(VoiceLanguage.ES), director.status.value)
        assertEquals(listOf(null, "a", "b"), created.map { it.pkg }, "default, then each other engine once")
        speak(u("uno"))
        assertTrue(engine.spoken.isEmpty())
    }

    @Test fun anotherEngineWithTheLanguageIsUsed() {
        configure = {
            it.engines = listOf("rhvoice")
            if (it.pkg == null) it.languages = mapOf(VoiceLanguage.ES to LanguageSupport.NOT_SUPPORTED)
        }
        director.prepare(VoiceLanguage.ES); sched.idle()
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
        assertEquals("rhvoice", created[1].pkg)
        speak(u("hola"))
        assertEquals(listOf("hola"), engine.texts)
    }

    @Test fun theLanguageIsSwitchedPerUtterance() {
        speak(u("hola", lang = VoiceLanguage.ES))
        engine.finish(); sched.idle()
        speak(u("hello", lang = VoiceLanguage.EN))
        assertEquals(VoiceLanguage.EN, engine.currentLanguage)
        assertEquals(VoiceStatus.Ready(VoiceLanguage.EN), director.status.value)
    }

    @Test fun anUnavailableLanguageDropsTheUtteranceAndSaysSo() {
        configure = { it.languages = mapOf(VoiceLanguage.ES to LanguageSupport.AVAILABLE, VoiceLanguage.EN to LanguageSupport.MISSING_DATA) }
        speak(u("hola"))
        engine.finish(); sched.idle()
        speak(u("hello", lang = VoiceLanguage.EN))
        assertEquals(listOf("hola"), engine.texts)
        assertEquals(VoiceStatus.LanguageMissing(VoiceLanguage.EN), director.status.value)
        speak(u("otra vez", lang = VoiceLanguage.ES))
        assertEquals(listOf("hola", "otra vez"), engine.texts, "going back to a language that works recovers")
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
    }

    // ---- Recovery

    @Test fun anEngineThatNeverStartsIsRestarted() {
        var count = 0
        configure = { if (count++ == 0) it.initOutcome = null }
        speak(u("uno"))
        sched.advance(config.initTimeoutMillis + 1)
        sched.advance(config.restartBackoffMillis + 1)
        assertEquals(2, created.size)
        assertTrue(created[0].shutDown)
        assertEquals(listOf("uno"), engine.texts, "the waiting utterance is not lost")
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
    }

    @Test fun anEngineThatKeepsFailingIsGivenUpUntilRetry() {
        configure = { it.initOutcome = null }
        speak(u("uno"))
        repeat(20) { sched.advance(config.initTimeoutMillis + 20_000) }
        assertEquals(VoiceStatus.Failed(VoiceFailure.ENGINE_UNRESPONSIVE), director.status.value)
        assertTrue(created.size <= config.maxRestarts + 2, "bounded restarts: ${created.size}")
        val count = created.size
        speak(u("dos"))
        assertEquals(count, created.size, "no more attempts after giving up")
        configure = {}
        director.retry(VoiceLanguage.ES); sched.idle()
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
        speak(u("tres"))
        assertEquals(listOf("tres"), engine.texts)
    }

    @Test fun initErrorWithEnginesInstalledRestarts() {
        var count = 0
        configure = { if (count++ == 0) it.initOutcome = false }
        director.prepare(VoiceLanguage.ES); sched.idle()
        sched.advance(config.restartBackoffMillis + 1)
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), director.status.value)
        assertEquals(2, created.size)
    }

    @Test fun aDeadEngineIsRecreatedAndTheInterruptedPromptRetried() {
        speak(u("gira ya", VoicePriority.NORMAL))
        val dead = engine
        dead.fail(fatal = true); sched.idle()
        assertEquals(VoiceStatus.Starting, director.status.value)
        sched.advance(config.restartBackoffMillis + 1)
        assertEquals(2, created.size)
        assertTrue(dead.shutDown)
        assertEquals(listOf("gira ya"), engine.texts)
    }

    @Test fun aStaleInterruptedPromptIsNotRetriedAfterARestart() {
        speak(Utterance("viejo", VoicePriority.NORMAL, VoiceLanguage.ES, maxAgeMillis = 100))
        engine.fail(fatal = true); sched.advance(config.restartBackoffMillis + 1)
        assertTrue(engine.spoken.isEmpty())
    }

    @Test fun aNonFatalErrorSkipsToTheNextPrompt() {
        speak(u("uno"), u("dos"))
        engine.fail(0, fatal = false); sched.idle()
        assertEquals(listOf("uno", "dos"), engine.texts)
        assertEquals(1, created.size)
    }

    @Test fun anEngineThatRefusesToSpeakIsRestarted() {
        var count = 0
        configure = { if (count++ == 0) it.acceptSpeak = false }
        speak(u("uno"))
        sched.advance(config.restartBackoffMillis + 1)
        assertEquals(2, created.size)
        assertEquals(listOf("uno"), engine.texts)
    }

    @Test fun anUtteranceThatNeverFinishesIsAbandonedByTheWatchdog() {
        speak(u("uno"), u("dos"))
        sched.advance(config.speechTimeoutBaseMillis + 4 * config.speechTimeoutPerCharMillis + 1)
        assertEquals(listOf("uno", "dos"), engine.texts, "the queue moves on")
        assertTrue(engine.stops >= 1)
    }

    @Test fun twoStuckUtterancesInARowRestartTheEngine() {
        speak(u("uno"), u("dos"))
        sched.advance(config.maxSpeechMillis + 1)
        sched.advance(config.maxSpeechMillis + 1)
        sched.advance(config.restartBackoffMillis + 1)
        assertEquals(2, created.size)
    }

    @Test fun shutdownReleasesEverythingAndIgnoresLaterCalls() {
        speak(u("uno"))
        director.shutdown(); sched.idle()
        assertTrue(engine.shutDown)
        assertFalse(focus.held)
        assertEquals(VoiceStatus.Idle, director.status.value)
        director.speak(u("tarde")); sched.advance(60_000)
        assertEquals(1, created.size)
        assertEquals(listOf("uno"), engine.texts)
    }

    @Test fun callbacksOfAReplacedEngineAreIgnored() {
        speak(u("uno"))
        val old = engine
        old.fail(fatal = true); sched.advance(config.restartBackoffMillis + 1)
        old.listener!!.onError("u1", true); old.listener!!.onDone("u1"); sched.idle()
        assertEquals(2, created.size, "the stale fatal error must not restart again")
        assertIs<VoiceStatus.Ready>(director.status.value)
    }
}
