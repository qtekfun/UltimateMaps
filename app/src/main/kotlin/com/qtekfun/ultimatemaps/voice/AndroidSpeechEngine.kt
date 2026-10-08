package com.qtekfun.ultimatemaps.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.qtekfun.ultimatemaps.core.voice.AudioFocus
import com.qtekfun.ultimatemaps.core.voice.Cancelable
import com.qtekfun.ultimatemaps.core.voice.LanguageSupport
import com.qtekfun.ultimatemaps.core.voice.Scheduler
import com.qtekfun.ultimatemaps.core.voice.SpeechEngine
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceLocale
import java.util.Locale

/** Audio attributes of the voice: navigation guidance, spoken content (the system never ducks speech of this kind). */
internal fun guidanceAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * [SpeechEngine] over the platform `TextToSpeech` (no Google Play Services: it talks to whatever engine the system
 * has, the default one or [enginePackage]). Must be created and used on the main thread; the progress callbacks
 * arrive on binder threads and the director hops back.
 *
 * `TextToSpeech` needs the manifest `<queries>` entry for `TTS_SERVICE`: from Android 11 on, without it the app
 * cannot see any engine and reports "no engine" even when there are several.
 */
class AndroidSpeechEngine(private val context: Context, private val enginePackage: String?) : SpeechEngine {
    private var tts: TextToSpeech? = null

    override fun start(listener: SpeechEngine.Listener) {
        val init = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) configure(listener)
            listener.onInit(ok)
        }
        // The init callback may run inside the constructor when there is no engine at all, so `tts` is read lazily.
        tts = if (enginePackage == null) TextToSpeech(context, init) else TextToSpeech(context, init, enginePackage)
    }

    private fun configure(listener: SpeechEngine.Listener) {
        val t = tts ?: return
        t.setAudioAttributes(guidanceAudioAttributes())
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let(listener::onDone) }

            @Deprecated("Replaced by onError(String, Int)")
            override fun onError(utteranceId: String?) { utteranceId?.let { listener.onError(it, false) } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                // ERROR_SERVICE: the engine process is gone or the connection broke; only recreating helps.
                utteranceId?.let { listener.onError(it, errorCode == TextToSpeech.ERROR_SERVICE) }
            }

            // Stopped by us (a newer prompt, or stop()): the director already moved on and ignores the stale id.
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { listener.onError(it, false) } }
        })
    }

    override fun hasInstalledEngine(): Boolean = VoiceInstall.installedEngines(context).isNotEmpty() || !tts?.defaultEngine.isNullOrEmpty()

    override fun installedEngines(): List<String> {
        val tried = setOfNotNull(enginePackage, tts?.defaultEngine)
        return VoiceInstall.installedEngines(context).filter { it !in tried }
    }

    override fun setLanguage(language: VoiceLanguage): LanguageSupport {
        val t = tts ?: return LanguageSupport.NOT_SUPPORTED
        // Region first (the phone's own, or Spain), the bare language last: asking only for "es" gives the engine's default,
        // usually Latin American Spanish. See VoiceLocale.
        var worst = LanguageSupport.NOT_SUPPORTED
        for (candidate in VoiceLocale.candidates(language, Locale.getDefault())) {
            when (t.setLanguage(candidate)) {
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                    chooseVoice(t, candidate)
                    return LanguageSupport.AVAILABLE
                }
                TextToSpeech.LANG_MISSING_DATA -> worst = LanguageSupport.MISSING_DATA
                else -> Unit
            }
        }
        return worst
    }

    /** Picks the best installed offline voice of the wanted region; keeps the engine's own choice when none fits. */
    private fun chooseVoice(t: TextToSpeech, wanted: Locale) {
        val all = runCatching { t.voices }.getOrNull().orEmpty()
        val options = all.map {
            VoiceLocale.VoiceOption(
                it.name, it.locale, it.quality, it.isNetworkConnectionRequired,
                installed = !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        }
        val best = VoiceLocale.pickVoice(options, wanted) ?: return
        all.firstOrNull { it.name == best.name }?.let { runCatching { t.voice = it } }
    }

    override fun speak(id: String, text: String, volume: Float): Boolean {
        val t = tts ?: return false
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        return t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
    }
}

/**
 * Audio focus for the voice: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with navigation-guidance attributes, so the
 * music of other apps is lowered while we speak and comes back when [abandon] is called. If another app takes the
 * focus away (a phone call, an alarm) [request]'s `onLost` runs and the director stops talking.
 */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var onLost: (() -> Unit)? = null

    override fun request(onLost: () -> Unit): Boolean {
        val am = audio ?: return true // no audio service: nothing to negotiate
        this.onLost = onLost
        val r = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(guidanceAudioAttributes())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) this.onLost?.invoke()
            }
            .build()
            .also { request = it }
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val r = request ?: return
        audio?.abandonAudioFocusRequest(r)
        request = null
    }
}

/** Runs the director on [looper] (the main one). */
class HandlerScheduler(looper: Looper = Looper.getMainLooper()) : Scheduler {
    private val handler = Handler(looper)

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(delayMillis: Long, task: Runnable): Cancelable {
        handler.postDelayed(task, delayMillis)
        return Cancelable { handler.removeCallbacks(task) }
    }
}
