package com.qtekfun.mapas.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.qtekfun.mapas.core.cameras.AlertSoundPlayer
import com.qtekfun.mapas.core.cameras.ChimeKind
import com.qtekfun.mapas.core.cameras.ChimeSynth
import com.qtekfun.mapas.core.voice.AudioFocus

/**
 * Plays the alert chimes ([ChimeSynth], generated in code: no audio asset) with an `AudioTrack` in `MODE_STATIC`, as
 * navigation guidance / sonification. Like the voice it asks for transient may-duck audio focus ([AndroidAudioFocus]), so
 * music is lowered for the second the chime lasts and comes back afterwards. If the focus is refused (a call is going on)
 * nothing is played. `volumePercent` scales the track, the same setting as the voice volume. Never throws; the PCM
 * is generated once per kind. Everything runs on the main thread (alerts are delivered from a background thread and hop).
 */
class AndroidAlertChimePlayer internal constructor(
    private val focus: AudioFocus,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AlertSoundPlayer {
    constructor(context: Context) : this(AndroidAudioFocus(context.applicationContext))

    private val pcm = HashMap<ChimeKind, ShortArray>()
    private var track: AudioTrack? = null
    private val finish = Runnable { release() }

    override fun play(kind: ChimeKind, volumePercent: Int) {
        handler.post { runCatching { playNow(kind, volumePercent) }.onFailure { release() } }
    }

    private fun playNow(kind: ChimeKind, volumePercent: Int) {
        release()
        if (!focus.request(onLost = { release() })) return
        val samples = pcm.getOrPut(kind) { ChimeSynth.pcm(kind) }
        val bytes = samples.size * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ChimeSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track = t
        t.write(samples, 0, samples.size)
        t.setVolume(volumePercent.coerceIn(0, 100) / 100f)
        t.play()
        handler.postDelayed(finish, ChimeSynth.durationMillis() + TAIL_MILLIS)
    }

    private fun release() {
        handler.removeCallbacks(finish)
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
        focus.abandon()
    }

    private companion object {
        /** Slack after the nominal length so the last samples are not cut off by the release. */
        const val TAIL_MILLIS = 150L
    }
}
