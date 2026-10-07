package com.qtekfun.ultimatemaps.core.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * URGENT interrupts whatever is being said and clears the queue; NORMAL waits its turn; LOW never piles up; ADVISORY is
 * for information that must never get in the way of a driving instruction (camera and incident alerts): it waits
 * behind every instruction, never interrupts or discards one, and is itself dropped or interrupted by them.
 */
enum class VoicePriority { URGENT, NORMAL, LOW, ADVISORY }

/**
 * One thing to say. [key] replaces a pending utterance with the same key (so "recalculating" is never said
 * twice in a row). [maxAgeMillis] is how long it may wait before it is dropped as stale: "turn left" said ten
 * seconds late is worse than silence.
 */
data class Utterance(
    val text: String,
    val priority: VoicePriority,
    val language: VoiceLanguage,
    val key: String? = null,
    val maxAgeMillis: Long = defaultMaxAge(priority),
) {
    companion object {
        fun defaultMaxAge(p: VoicePriority): Long = when (p) {
            VoicePriority.URGENT -> 10_000L
            VoicePriority.NORMAL -> 15_000L
            VoicePriority.LOW -> 8_000L
            VoicePriority.ADVISORY -> 6_000L
        }
    }
}

/** Why the voice cannot work, as far as the user can act on it. */
enum class VoiceFailure {
    /** The engine did not answer in time, or kept dying. */
    ENGINE_UNRESPONSIVE,

    /** The engine reported an error at start-up and no other engine could be used. */
    INIT_FAILED,
}

/** The state of the voice, for a visual notice. Navigation never depends on it: without voice it goes on silent. */
sealed interface VoiceStatus {
    /** Nothing asked yet. */
    data object Idle : VoiceStatus

    /** The engine is starting; utterances wait. */
    data object Starting : VoiceStatus

    /** Ready to speak [language]. */
    data class Ready(val language: VoiceLanguage) : VoiceStatus

    /** No text-to-speech engine is installed (common without Google services). */
    data object NoEngine : VoiceStatus

    /** There is an engine but none has the voice data for [language]. */
    data class LanguageMissing(val language: VoiceLanguage) : VoiceStatus

    /** The engine is broken or was restarted too often; voice is off until [VoiceGuide.retry]. */
    data class Failed(val reason: VoiceFailure) : VoiceStatus
}

/** True when the user should be told that the voice will not speak. */
val VoiceStatus.isProblem: Boolean
    get() = this is VoiceStatus.NoEngine || this is VoiceStatus.LanguageMissing || this is VoiceStatus.Failed

/**
 * The voice of the navigation. Every method is cheap and safe to call from any thread; nothing blocks or throws.
 * Implementations: [SpeechDirector] (queue, audio focus and recovery over a [SpeechEngine]).
 */
interface VoiceGuide {
    val status: StateFlow<VoiceStatus>

    /** Starts the engine ahead of time (it takes a second or more) and checks that [language] is available. */
    fun prepare(language: VoiceLanguage)

    fun speak(utterance: Utterance)

    /** Stops what is being said and forgets what is waiting. */
    fun stop()

    /** Voice volume relative to the stream, 0..100. */
    fun setVolume(percent: Int)

    /** Tries again after a problem (an engine was just installed, the data was downloaded). */
    fun retry(language: VoiceLanguage)

    fun shutdown()
}
