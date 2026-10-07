package com.qtekfun.mapas.core.cameras

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How one category of alerts reaches the driver (the visual chip or banner shows in every mode).
 * [SOUND]: a short chime, like other navigation apps (default). [VOICE]: the spoken sentence. [SILENT]: nothing audible.
 */
enum class AlertSoundMode { SOUND, VOICE, SILENT }

/** What to do with one alert, decided by [AlertDeliveryPolicy]. */
enum class AlertDelivery { CHIME, SPEAK, NONE }

/**
 * The pure decision "chime / speak / nothing". Order of precedence: the navigation voice switch (the navigation Mute)
 * silences everything; the alerts' quick mute silences both categories without touching their modes; a maneuver that is
 * being announced or about to be wins over any alert (the visual alert still shows, nothing is queued for later).
 */
object AlertDeliveryPolicy {
    fun decide(mode: AlertSoundMode, alertsMuted: Boolean, navigationVoiceOn: Boolean, maneuverImminent: Boolean): AlertDelivery = when {
        !navigationVoiceOn -> AlertDelivery.NONE
        alertsMuted -> AlertDelivery.NONE
        maneuverImminent -> AlertDelivery.NONE
        mode == AlertSoundMode.SOUND -> AlertDelivery.CHIME
        mode == AlertSoundMode.VOICE -> AlertDelivery.SPEAK
        else -> AlertDelivery.NONE
    }
}

/**
 * Which chime: cameras and incidents have different pitch patterns so they can be told apart without looking; the public-transport
 * trip prompts have a third one (lower than the camera chime, rising, so it is not mistaken for either).
 */
enum class ChimeKind { CAMERA, INCIDENT, TRANSIT }

val AlertCategory.chimeKind: ChimeKind get() = if (isCamera) ChimeKind.CAMERA else ChimeKind.INCIDENT

/**
 * Plays a chime. Implementations never block and never throw; [volumePercent] is the navigation voice volume setting.
 * The Android one (`AndroidAlertChimePlayer`) uses AudioTrack with transient may-duck audio focus.
 */
fun interface AlertSoundPlayer {
    fun play(kind: ChimeKind, volumePercent: Int)
}

/**
 * Generates the chimes as 16-bit mono PCM: no audio asset, so no licence to track. Each chime is two sine tones with a
 * short silent gap and a linear fade at the edges of every tone (no clicks). The pitches are design choices that have NOT
 * been tuned on a device: cameras go up (a "heads up" figure), incidents go down and are lower, so the two differ in both
 * contour and register.
 */
object ChimeSynth {
    const val SAMPLE_RATE = 44_100
    const val TONE_MILLIS = 120
    const val GAP_MILLIS = 40
    const val FADE_MILLIS = 10

    /** Peak amplitude as a fraction of full scale; leaves headroom, loudness comes from the volume setting. */
    const val AMPLITUDE = 0.6

    /** The two tone frequencies in Hz, in playing order. */
    fun tonesHz(kind: ChimeKind): Pair<Int, Int> = when (kind) {
        ChimeKind.CAMERA -> 988 to 1319
        ChimeKind.INCIDENT -> 659 to 494
        ChimeKind.TRANSIT -> 784 to 1047
    }

    fun samplesPerTone(): Int = SAMPLE_RATE * TONE_MILLIS / 1000
    fun samplesPerGap(): Int = SAMPLE_RATE * GAP_MILLIS / 1000

    /** Total length in samples: tone, gap, tone. */
    fun length(): Int = 2 * samplesPerTone() + samplesPerGap()

    fun durationMillis(): Int = 2 * TONE_MILLIS + GAP_MILLIS

    fun pcm(kind: ChimeKind): ShortArray {
        val (a, b) = tonesHz(kind)
        val out = ShortArray(length())
        writeTone(out, 0, a)
        writeTone(out, samplesPerTone() + samplesPerGap(), b)
        return out
    }

    private fun writeTone(out: ShortArray, start: Int, hz: Int) {
        val n = samplesPerTone()
        val fade = SAMPLE_RATE * FADE_MILLIS / 1000
        for (i in 0 until n) {
            val edge = minOf(i, n - 1 - i)
            val gain = if (edge < fade) (edge + 1).toDouble() / (fade + 1) else 1.0
            val v = sin(2.0 * PI * hz * i / SAMPLE_RATE) * AMPLITUDE * gain
            out[start + i] = (v * Short.MAX_VALUE).roundToInt().toShort()
        }
    }
}
