package com.qtekfun.ultimatemaps.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/** The engine the user chose is the one started first, and retry ("Test voice" after a change) goes back to it. */
class PreferredEngineTest {
    private val log = ArrayList<String>()
    private val scheduler = ManualScheduler()
    private val factory = EngineFactory { pkg -> FakeEngine(pkg, log) }

    private fun starts() = log.filter { it.startsWith("start:") }

    @Test
    fun `the chosen engine is started instead of the default and retry returns to it`() {
        var chosen: String? = "google.tts"
        val director = SpeechDirector(factory, FakeFocus(), scheduler, preferredEngine = { chosen })
        director.prepare(VoiceLanguage.ES)
        scheduler.idle()
        assertEquals(listOf("start:google.tts"), starts())
        chosen = "vendor.tts"
        director.retry(VoiceLanguage.ES)
        scheduler.idle()
        assertEquals(listOf("start:google.tts", "start:vendor.tts"), starts())
        chosen = null
        director.retry(VoiceLanguage.ES)
        scheduler.idle()
        assertEquals("start:null", starts().last(), "the system default again")
    }

    @Test
    fun `without a choice the system default is used as before`() {
        SpeechDirector(factory, FakeFocus(), scheduler).prepare(VoiceLanguage.EN)
        scheduler.idle()
        assertEquals(listOf("start:null"), starts())
    }
}
