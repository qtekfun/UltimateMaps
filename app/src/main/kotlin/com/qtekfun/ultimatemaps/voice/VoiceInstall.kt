package com.qtekfun.ultimatemaps.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech

/**
 * Helpers for the "no voice" guide: which free engines to suggest, how to open their F-Droid page, and the system
 * screens that choose an engine or install voice data. Nothing here connects to the network: the links are only
 * opened when the user taps them, by the F-Droid client if it is installed (it handles `f-droid.org/packages`
 * links) or by the browser.
 *
 * The suggested engines were checked on their F-Droid pages (2026-10-07): all are free software, all have Spanish
 * and English, and none needs Google services.
 */
object VoiceInstall {
    class Engine(val name: String, val packageId: String)

    /**
     * RHVoice (GPL-3.0+, natural-sounding recorded voices, Spanish and English; F-Droid flags it for non-free
     * voice add-ons), eSpeak NG (GPL-3.0+, robotic but tiny and needs no download), SherpaTTS (GPL-3.0, Piper
     * neural voices, the best sounding; downloads its voice model once from Hugging Face, which F-Droid flags).
     */
    val suggested = listOf(
        Engine("RHVoice", "com.github.olga_yakovleva.rhvoice.android"),
        Engine("eSpeak NG", "com.reecedunn.espeak"),
        Engine("SherpaTTS", "org.woheller69.ttsengine"),
    )

    fun fdroidUrl(packageId: String) = "https://f-droid.org/packages/$packageId/"

    fun fdroidIntent(packageId: String) = Intent(Intent.ACTION_VIEW, Uri.parse(fdroidUrl(packageId)))

    /** The system screen where the preferred engine is chosen (Settings > System > Languages > Text-to-speech). */
    fun ttsSettingsIntent() = Intent("com.android.settings.TTS_SETTINGS")

    /** Asks the current engine to install its voice data for a language. */
    fun installDataIntent() = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    /** Packages that declare a TTS service. Needs the manifest `<queries>` entry (package visibility, Android 11+). */
    fun installedEngines(context: Context): List<String> = try {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** Starts [intent] as a new task; false when nothing on the device can handle it. */
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
