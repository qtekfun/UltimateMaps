package com.qtekfun.mapas.voice

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.core.voice.SpeechDirector
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoicePriority
import com.qtekfun.mapas.core.voice.VoiceStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android layer over Robolectric's `ShadowTextToSpeech` (a fake engine: nothing here proves how a real engine
 * sounds, nor how real audio focus ducks real music; see `docs/phase2/voice.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVoiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun director() = SpeechDirector(
        engines = { pkg -> AndroidSpeechEngine(context, pkg) },
        audio = AndroidAudioFocus(context),
        scheduler = HandlerScheduler(Looper.getMainLooper()),
    )

    private fun shadowTts() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    @Before fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("es"))
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("en"))
    }

    @After fun tearDown() = ShadowTextToSpeech.reset()

    private fun installEngine(pkg: String = "org.example.tts") {
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.Service" } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), info)
    }

    private fun startedDirector(language: VoiceLanguage = VoiceLanguage.ES): SpeechDirector {
        installEngine()
        val d = director()
        d.prepare(language)
        idle()
        shadowTts().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        return d
    }

    @Test fun anEngineThatStartsMakesTheVoiceReady() {
        val d = startedDirector()
        assertEquals(VoiceStatus.Ready(VoiceLanguage.ES), d.status.value)
        assertEquals(Locale.forLanguageTag("es"), shadowTts().currentLanguage)
    }

    @Test fun speakingGoesToTheEngineWithTheVolumeAndTheFocusIsTakenAndReturned() {
        val d = startedDirector()
        d.setVolume(50)
        d.speak(Utterance("En 300 metros, gira a la izquierda", VoicePriority.NORMAL, VoiceLanguage.ES))
        idle()
        assertEquals("En 300 metros, gira a la izquierda", shadowTts().lastSpokenText)
        assertEquals(0.5f as Float?, ShadowTextToSpeech.getLastParams()?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME))

        val am = context.getSystemService(AudioManager::class.java)
        val request = assertNotNull(shadowOf(am).lastAudioFocusRequest).audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)

        shadowTts().utteranceProgressListener.onDone("u1")
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertNotNull(shadowOf(am).lastAbandonedAudioFocusRequest, "the music comes back after the last prompt")
    }

    @Test fun theFocusRequestListensForLossesAndTheVoiceKeepsWorkingAfterwards() {
        val d = startedDirector()
        d.speak(Utterance("hola", VoicePriority.NORMAL, VoiceLanguage.ES))
        idle()
        val am = context.getSystemService(AudioManager::class.java)
        val listener = assertNotNull(shadowOf(am).lastAudioFocusRequest).listener
        assertNotNull(listener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        // (the shadow finishes an utterance at once, so there is nothing left to stop: see SpeechDirectorTest for that)
        d.speak(Utterance("otra", VoicePriority.NORMAL, VoiceLanguage.ES))
        idle()
        assertEquals("otra", shadowTts().lastSpokenText)
    }

    @Test fun anotherAppHoldingTheFocusKeepsUsQuiet() {
        val d = startedDirector()
        val am = context.getSystemService(AudioManager::class.java)
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        d.speak(Utterance("hola", VoicePriority.NORMAL, VoiceLanguage.ES))
        idle()
        assertEquals(null, shadowTts().lastSpokenText)
    }

    @Test fun noEngineAtAllIsReportedAsSuch() {
        // Nothing declares a TTS service: the system gives no engine and the init callback reports an error.
        val d = director()
        d.prepare(VoiceLanguage.ES)
        idle()
        shadowTts().onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(VoiceStatus.NoEngine, d.status.value)
        d.speak(Utterance("hola", VoicePriority.URGENT, VoiceLanguage.ES))
        idle()
        assertEquals(null, shadowTts().lastSpokenText, "silent, without crashing")
    }

    @Test fun anInitErrorWithAnEngineInstalledIsNotTakenForNoEngine() {
        installEngine()
        val d = director()
        d.prepare(VoiceLanguage.ES)
        idle()
        shadowTts().onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(VoiceStatus.Starting, d.status.value, "restarting, not 'no engine'")
    }

    @Test fun aLanguageWithoutDataIsReported() {
        installEngine()
        ShadowTextToSpeech.reset() // no language available at all
        val d = director()
        d.prepare(VoiceLanguage.ES)
        idle()
        shadowTts().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        // The director tries the other installed engine before giving up (here the same fake package again).
        shadowTts().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertIs<VoiceStatus.LanguageMissing>(d.status.value)
    }

    @Test fun theEngineCanBeFoundThroughPackageVisibility() {
        assertTrue(VoiceInstall.installedEngines(context).isEmpty())
        installEngine("com.github.olga_yakovleva.rhvoice.android")
        assertEquals(listOf("com.github.olga_yakovleva.rhvoice.android"), VoiceInstall.installedEngines(context))
    }

    @Test fun shutdownReleasesTheEngine() {
        val d = startedDirector()
        val tts = shadowTts()
        d.shutdown()
        idle()
        assertTrue(tts.isShutdown)
    }

    @Test fun suggestedEnginesLinkOnlyToFDroid() {
        assertTrue(VoiceInstall.suggested.size >= 2)
        for (e in VoiceInstall.suggested) {
            assertTrue(VoiceInstall.fdroidUrl(e.packageId).startsWith("https://f-droid.org/packages/${e.packageId}"), e.name)
            assertFalse("play.google" in VoiceInstall.fdroidUrl(e.packageId))
        }
        assertEquals("android.speech.tts.engine.INSTALL_TTS_DATA", VoiceInstall.installDataIntent().action)
    }

    @Test fun theManifestAsksForTtsPackageVisibility() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("android.intent.action.TTS_SERVICE" in manifest && "<queries>" in manifest)
    }
}
