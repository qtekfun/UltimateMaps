package com.qtekfun.mapas.voice

import android.content.Context
import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.core.voice.NavSettingsStore
import com.qtekfun.mapas.core.voice.SpeechDirector
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceNavigationController
import com.qtekfun.mapas.settings.PrefsNavSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one place that builds the voice (it needs no change to `MapasApp`): the navigation settings, the voice guide
 * over the Android TTS engine, and the controller that connects the navigation to it.
 *
 * Wiring from the navigation (see `docs/phase2/voice.md`):
 * ```
 * VoiceModule.attach(app)   // when a navigation starts or resumes (NavigationService.onStartCommand, after start/resume)
 * VoiceModule.detach()      // when it ends (NavigationService.shutDown / onDestroy)
 * ```
 * and in the navigation screen: `VoiceProblemBanner(VoiceModule.guide(ctx).status.collectAsState().value, ...)`.
 */
object VoiceModule {
    private var settingsStore: NavSettingsStore? = null
    private var voiceGuide: VoiceGuide? = null
    private var controller: VoiceNavigationController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun settings(context: Context): NavSettingsStore =
        settingsStore ?: PrefsNavSettingsStore(context.applicationContext).also { settingsStore = it }

    @Synchronized
    fun guide(context: Context): VoiceGuide = voiceGuide ?: run {
        val app = context.applicationContext
        SpeechDirector(
            engines = { pkg -> AndroidSpeechEngine(app, pkg) },
            audio = AndroidAudioFocus(app),
            scheduler = HandlerScheduler(),
        ).also { voiceGuide = it }
    }

    /** Starts speaking the prompts of [MapasApp.navigation]. Idempotent. */
    @Synchronized
    fun attach(app: MapasApp) {
        if (controller != null) return
        controller = VoiceNavigationController.of(app.navigation, scope, guide(app), settings(app).settings).also { it.start() }
    }

    /** Stops listening and silences the voice (the engine stays warm for the next trip). */
    @Synchronized
    fun detach() {
        controller?.close()
        controller = null
    }
}
