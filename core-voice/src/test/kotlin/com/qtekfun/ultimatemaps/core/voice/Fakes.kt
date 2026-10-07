package com.qtekfun.ultimatemaps.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Virtual time: nothing runs until [advance] or [idle]; delayed tasks run in order of due time. No real waiting. */
class ManualScheduler : Scheduler {
    var now = 0L
        private set
    private class Task(val at: Long, val seq: Int, val run: Runnable) { var cancelled = false }
    private val tasks = ArrayList<Task>()
    private var seq = 0

    override fun post(task: Runnable) { tasks += Task(now, seq++, task) }

    override fun postDelayed(delayMillis: Long, task: Runnable): Cancelable {
        val t = Task(now + delayMillis, seq++, task)
        tasks += t
        return Cancelable { t.cancelled = true }
    }

    /** Runs what is due now (including tasks those tasks post). */
    fun idle() = advance(0)

    fun advance(millis: Long) {
        val end = now + millis
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = maxOf(now, next.at)
            next.run.run()
        }
        now = end
        tasks.removeAll { it.cancelled }
    }

    val clock: () -> Long = { now }
}

class FakeEngine(val pkg: String?, private val log: MutableList<String>) : SpeechEngine {
    var listener: SpeechEngine.Listener? = null
    var initOutcome: Boolean? = true // null: never answers
    var installed = true
    var engines: List<String> = listOf("default")
    var languages: Map<VoiceLanguage, LanguageSupport> = VoiceLanguage.entries.associateWith { LanguageSupport.AVAILABLE }
    var acceptSpeak = true
    var shutDown = false
    var currentLanguage: VoiceLanguage? = null
    val spoken = ArrayList<Pair<String, String>>() // id to text
    var stops = 0

    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        log += "start:$pkg"
        initOutcome?.let(listener::onInit)
    }

    override fun hasInstalledEngine() = installed
    override fun installedEngines() = engines
    override fun setLanguage(language: VoiceLanguage): LanguageSupport {
        val s = languages[language] ?: LanguageSupport.NOT_SUPPORTED
        if (s == LanguageSupport.AVAILABLE) currentLanguage = language
        return s
    }

    override fun speak(id: String, text: String, volume: Float): Boolean {
        if (!acceptSpeak) return false
        spoken += id to text
        lastVolume = volume
        return true
    }

    var lastVolume = -1f
    override fun stop() { stops++ }
    override fun shutdown() { shutDown = true; log += "shutdown:$pkg" }

    fun finish(index: Int = spoken.lastIndex) = listener!!.onDone(spoken[index].first)
    fun fail(index: Int = spoken.lastIndex, fatal: Boolean) = listener!!.onError(spoken[index].first, fatal)
    val texts get() = spoken.map { it.second }
}

class FakeFocus(var grant: Boolean = true) : AudioFocus {
    var held = false
    var requests = 0
    var abandons = 0
    var onLost: (() -> Unit)? = null
    override fun request(onLost: () -> Unit): Boolean {
        requests++
        this.onLost = onLost
        if (grant) held = true
        return grant
    }

    override fun abandon() { held = false; abandons++ }
}

class RecordingGuide : VoiceGuide {
    override val status: StateFlow<VoiceStatus> = MutableStateFlow(VoiceStatus.Idle)
    val spoken = ArrayList<Utterance>()
    var stops = 0
    var prepared = ArrayList<VoiceLanguage>()
    var lastVolumeSet = -1
    override fun prepare(language: VoiceLanguage) { prepared += language }
    override fun speak(utterance: Utterance) { spoken += utterance }
    override fun stop() { stops++ }
    override fun setVolume(percent: Int) { lastVolumeSet = percent }
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
    val texts get() = spoken.map { it.text }
}
