package com.qtekfun.ultimatemaps.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanguageSupport { AVAILABLE, MISSING_DATA, NOT_SUPPORTED }

/** A text-to-speech engine, as little as the director needs (Android's `TextToSpeech` in the app, a fake in tests). */
interface SpeechEngine {
    /** Callbacks may arrive on any thread; the director hops to its own. */
    interface Listener {
        fun onInit(success: Boolean)
        fun onDone(id: String)

        /** [fatal]: the engine process died or the connection broke; the engine has to be recreated. */
        fun onError(id: String, fatal: Boolean)
    }

    /** Starts the engine; [Listener.onInit] says how it went (it may never be called if the engine hangs). */
    fun start(listener: Listener)

    /** Whether any engine is installed on the device at all (nothing to do about the voice if not). */
    fun hasInstalledEngine(): Boolean

    /** Packages of the installed engines, to try another one when the default lacks the language. */
    fun installedEngines(): List<String>

    fun setLanguage(language: VoiceLanguage): LanguageSupport

    /** Speaks [text] replacing whatever is playing; [volume] is 0..1. False when the engine refused. */
    fun speak(id: String, text: String, volume: Float): Boolean

    fun stop()
    fun shutdown()
}

/** Creates an engine; null means the system default, otherwise the package of a specific engine. */
fun interface EngineFactory {
    fun create(enginePackage: String?): SpeechEngine
}

/** Audio focus for speech: others duck while we speak. */
interface AudioFocus {
    /** True when granted. [onLost] is called if another app takes the focus away for good (a call, for example). */
    fun request(onLost: () -> Unit): Boolean

    fun abandon()
}

fun interface Cancelable {
    fun cancel()
}

/** Runs everything of one [SpeechDirector] on a single thread (the main looper on Android). */
interface Scheduler {
    fun post(task: Runnable)
    fun postDelayed(delayMillis: Long, task: Runnable): Cancelable
}

data class DirectorConfig(
    /** How long the engine may take to start before it is considered hung. */
    val initTimeoutMillis: Long = 10_000L,
    /** Restarts allowed within [restartWindowMillis] before voice is given up (until [VoiceGuide.retry]). */
    val maxRestarts: Int = 3,
    val restartWindowMillis: Long = 60_000L,
    val restartBackoffMillis: Long = 500L,
    /** Keeps the audio focus a moment after the last word, so back-to-back prompts do not make the music jump. */
    val focusReleaseDelayMillis: Long = 700L,
    val maxPending: Int = 4,
    /** An utterance that never reports "done" is abandoned after `base + perChar * length`, at most [maxSpeechMillis]. */
    val speechTimeoutBaseMillis: Long = 5_000L,
    val speechTimeoutPerCharMillis: Long = 100L,
    val maxSpeechMillis: Long = 30_000L,
)

/**
 * The queue, the audio focus and the recovery of the voice, over a [SpeechEngine]. All state lives on the
 * [scheduler] thread; the public methods and the engine callbacks only post to it, so they can be called from
 * anywhere.
 *
 * Queue rules (the reason prompts never pile up while the driver is already past the junction):
 * - URGENT ("now", arrival) drops everything waiting, interrupts what is being said and speaks at once.
 * - NORMAL waits for the current one, replaces a pending one with the same key, and discards pending LOW ones;
 *   it interrupts a LOW one that is being said (the far warning is obsolete once the near one is due).
 * - LOW keeps at most one place in the queue: a newer one replaces the older.
 * - ADVISORY (camera and incident alerts) is spoken only when no instruction waits, never interrupts or discards
 *   anything, is interrupted by a NORMAL or URGENT one, and is the first to be dropped when the queue is full.
 * - Anything waiting longer than its max age is dropped; at most [DirectorConfig.maxPending] wait.
 *
 * Recovery: an engine that does not start in time, reports a fatal error, refuses to speak or never finishes an
 * utterance is shut down and recreated (with a growing delay); the interrupted utterance is retried if it is still
 * fresh. After [DirectorConfig.maxRestarts] restarts in a minute the status becomes [VoiceStatus.Failed] and the
 * guide stays quiet until [retry]. If the default engine lacks the language, the other installed engines are tried
 * before reporting [VoiceStatus.LanguageMissing].
 */
class SpeechDirector(
    private val engines: EngineFactory,
    private val audio: AudioFocus,
    private val scheduler: Scheduler,
    private val config: DirectorConfig = DirectorConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceGuide {
    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private class Queued(val utterance: Utterance, val at: Long)
    private class Playing(val id: String, val queued: Queued)

    // Everything below is touched only on the scheduler thread.
    private var engine: SpeechEngine? = null
    private var generation = 0
    private var engineUp = false
    private var engineLanguage: VoiceLanguage? = null
    private var enginePackage: String? = null
    private val triedEngines = HashSet<String?>()
    private var wanted = VoiceLanguage.ES
    private val pending = ArrayDeque<Queued>()
    private var current: Playing? = null
    private var counter = 0
    private var volume = 1f
    private val restarts = ArrayDeque<Long>()
    private var stuckInARow = 0
    private var watchdog: Cancelable? = null
    private var initTimer: Cancelable? = null
    private var restartTimer: Cancelable? = null
    private var releaseTimer: Cancelable? = null
    private var dead = false

    /** Utterances dropped because another app (a call) held the audio focus. For tests and diagnostics. */
    @Volatile var droppedWithoutFocus = 0
        private set

    override fun prepare(language: VoiceLanguage) = onThread {
        wanted = language
        if (engine == null && restartTimer == null && _status.value !is VoiceStatus.Failed) startEngine(enginePackage)
        else if (engineUp) checkLanguage(language)
    }

    override fun speak(utterance: Utterance) = onThread { enqueue(utterance) }

    override fun stop() = onThread {
        pending.clear()
        interruptCurrent()
        scheduleFocusRelease()
    }

    override fun setVolume(percent: Int) = onThread { volume = (percent.coerceIn(0, 100) / 100f) }

    override fun retry(language: VoiceLanguage) = onThread {
        restartTimer?.cancel(); restartTimer = null
        restarts.clear()
        triedEngines.clear()
        enginePackage = null
        discardEngine()
        wanted = language
        startEngine(null)
    }

    override fun shutdown() = onThread {
        dead = true
        cancelTimers()
        pending.clear()
        current = null
        discardEngine()
        audio.abandon()
        _status.value = VoiceStatus.Idle
    }

    private inline fun onThread(crossinline block: () -> Unit) {
        scheduler.post { if (!dead) block() }
    }

    // ------------------------------------------------------------------ Starting

    private fun startEngine(pkg: String?) {
        discardEngine()
        enginePackage = pkg
        triedEngines += pkg
        _status.value = VoiceStatus.Starting
        val gen = ++generation
        val e = try {
            engines.create(pkg)
        } catch (_: Exception) {
            engineFailed(VoiceFailure.INIT_FAILED)
            return
        }
        engine = e
        initTimer = scheduler.postDelayed(config.initTimeoutMillis) { if (!dead && gen == generation) engineFailed(VoiceFailure.ENGINE_UNRESPONSIVE) }
        try {
            e.start(object : SpeechEngine.Listener {
                override fun onInit(success: Boolean) { scheduler.post { if (!dead && gen == generation) handleInit(success) } }
                override fun onDone(id: String) { scheduler.post { if (!dead && gen == generation) handleDone(id) } }
                override fun onError(id: String, fatal: Boolean) { scheduler.post { if (!dead && gen == generation) handleError(id, fatal) } }
            })
        } catch (_: Exception) {
            engineFailed(VoiceFailure.INIT_FAILED)
        }
    }

    private fun handleInit(success: Boolean) {
        initTimer?.cancel(); initTimer = null
        val e = engine ?: return
        if (!success) {
            if (!safe { e.hasInstalledEngine() }) {
                discardEngine()
                pending.clear()
                _status.value = VoiceStatus.NoEngine
            } else engineFailed(VoiceFailure.INIT_FAILED)
            return
        }
        engineUp = true
        engineLanguage = null
        checkLanguage(wanted, tryOthers = true)
        pump()
    }

    /** Sets [language] on the engine and publishes the outcome; may switch to another engine that has it. */
    private fun checkLanguage(language: VoiceLanguage, tryOthers: Boolean = false) {
        val e = engine ?: return
        val support = try { e.setLanguage(language) } catch (_: Exception) { LanguageSupport.NOT_SUPPORTED }
        if (support == LanguageSupport.AVAILABLE) {
            engineLanguage = language
            _status.value = VoiceStatus.Ready(language)
            return
        }
        engineLanguage = null
        if (tryOthers) {
            val next = safeList { e.installedEngines() }.firstOrNull { it !in triedEngines }
            if (next != null) {
                startEngine(next)
                return
            }
        }
        _status.value = VoiceStatus.LanguageMissing(language)
    }

    // ------------------------------------------------------------------ Queue

    private fun enqueue(u: Utterance) {
        when (_status.value) {
            VoiceStatus.NoEngine, is VoiceStatus.Failed -> return // visual notice only
            else -> Unit
        }
        if (u.text.isBlank()) return
        if (engine == null && restartTimer == null) {
            wanted = u.language
            startEngine(enginePackage)
        }
        val q = Queued(u, clock())
        when (u.priority) {
            VoicePriority.URGENT -> {
                pending.clear()
                interruptCurrent()
                pending.addFirst(q)
            }
            VoicePriority.NORMAL -> {
                pending.removeAll { it.utterance.priority == VoicePriority.LOW || (u.key != null && it.utterance.key == u.key) }
                if (current?.queued?.utterance?.priority.let { it == VoicePriority.LOW || it == VoicePriority.ADVISORY }) interruptCurrent()
                pending.addLast(q)
            }
            VoicePriority.ADVISORY -> {
                pending.removeAll { u.key != null && it.utterance.key == u.key }
                pending.addLast(q)
            }
            VoicePriority.LOW -> {
                pending.removeAll { it.utterance.priority == VoicePriority.LOW || (u.key != null && it.utterance.key == u.key) }
                pending.addLast(q)
            }
        }
        while (pending.size > config.maxPending) {
            val drop = pending.firstOrNull { it.utterance.priority == VoicePriority.ADVISORY }
                ?: pending.firstOrNull { it.utterance.priority == VoicePriority.LOW } ?: pending.first()
            pending.remove(drop)
        }
        pump()
    }

    private fun pump() {
        while (engineUp && current == null) {
            val q = (pending.firstOrNull { it.utterance.priority != VoicePriority.ADVISORY } ?: pending.firstOrNull())
                ?.also { pending.remove(it) } ?: run { scheduleFocusRelease(); return }
            if (clock() - q.at > q.utterance.maxAgeMillis) continue
            if (!prepareToSpeak(q.utterance)) continue
            releaseTimer?.cancel(); releaseTimer = null
            if (!audio.request { onThread { stop() } }) {
                droppedWithoutFocus++
                continue
            }
            val id = "u${++counter}"
            val e = engine ?: return
            current = Playing(id, q)
            val accepted = try { e.speak(id, q.utterance.text, volume) } catch (_: Exception) { false }
            if (!accepted) {
                engineFailed(VoiceFailure.ENGINE_UNRESPONSIVE)
                return
            }
            val limit = (config.speechTimeoutBaseMillis + config.speechTimeoutPerCharMillis * q.utterance.text.length).coerceAtMost(config.maxSpeechMillis)
            watchdog = scheduler.postDelayed(limit) { if (!dead && current?.id == id) onStuck() }
            return
        }
    }

    /** Makes the engine speak the utterance's language; false (and the status says why) when it cannot. */
    private fun prepareToSpeak(u: Utterance): Boolean {
        if (engineLanguage == u.language) return true
        checkLanguage(u.language)
        return engineLanguage == u.language
    }

    private fun interruptCurrent() {
        watchdog?.cancel(); watchdog = null
        if (current != null) {
            current = null
            safe { engine?.stop() }
        }
    }

    private fun handleDone(id: String) {
        if (current?.id != id) return
        watchdog?.cancel(); watchdog = null
        current = null
        stuckInARow = 0
        pump()
    }

    private fun handleError(id: String, fatal: Boolean) {
        if (fatal) {
            engineFailed(VoiceFailure.ENGINE_UNRESPONSIVE)
            return
        }
        if (current?.id != id) return
        watchdog?.cancel(); watchdog = null
        current = null
        pump()
    }

    private fun onStuck() {
        safe { engine?.stop() }
        current = null
        watchdog = null
        if (++stuckInARow >= 2) engineFailed(VoiceFailure.ENGINE_UNRESPONSIVE) else pump()
    }

    private fun scheduleFocusRelease() {
        if (current != null || pending.isNotEmpty() || releaseTimer != null) return
        releaseTimer = scheduler.postDelayed(config.focusReleaseDelayMillis) {
            releaseTimer = null
            if (!dead && current == null && pending.isEmpty()) audio.abandon()
        }
    }

    // ------------------------------------------------------------------ Recovery

    /** The engine is unusable: throw it away and start another after a growing delay, or give up. */
    private fun engineFailed(reason: VoiceFailure) {
        val interrupted = current?.queued
        current = null
        stuckInARow = 0
        discardEngine()
        val now = clock()
        restarts.addLast(now)
        while (restarts.isNotEmpty() && now - restarts.first() > config.restartWindowMillis) restarts.removeFirst()
        if (restarts.size > config.maxRestarts) {
            pending.clear()
            _status.value = VoiceStatus.Failed(reason)
            audio.abandon()
            return
        }
        if (interrupted != null && now - interrupted.at <= interrupted.utterance.maxAgeMillis) pending.addFirst(interrupted)
        _status.value = VoiceStatus.Starting
        val delay = config.restartBackoffMillis shl (restarts.size - 1).coerceAtMost(4)
        restartTimer = scheduler.postDelayed(delay) {
            restartTimer = null
            if (!dead) startEngine(enginePackage)
        }
    }

    private fun discardEngine() {
        generation++ // callbacks of the old engine are ignored from now on
        cancelTimers(keepRestart = true)
        val e = engine
        engine = null
        engineUp = false
        engineLanguage = null
        current = null
        if (e != null) safe { e.shutdown() }
    }

    private fun cancelTimers(keepRestart: Boolean = false) {
        watchdog?.cancel(); watchdog = null
        initTimer?.cancel(); initTimer = null
        releaseTimer?.cancel(); releaseTimer = null
        if (!keepRestart) { restartTimer?.cancel(); restartTimer = null }
    }

    private inline fun <T> safe(block: () -> T): Boolean where T : Any? = try { block() != false } catch (_: Exception) { false }

    private inline fun safeList(block: () -> List<String>): List<String> = try { block() } catch (_: Exception) { emptyList() }
}
