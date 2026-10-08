package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.voice.VoiceLocale.VoiceOption
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceLocaleTest {
    private fun tags(l: List<Locale>) = l.map { it.toLanguageTag() }

    @Test fun `a phone set to Spain asks for Spain first and the bare language last`() {
        assertEquals(listOf("es-ES", "es"), tags(VoiceLocale.candidates(VoiceLanguage.ES, Locale("es", "ES"))))
    }

    @Test fun `a phone set to Mexico keeps its own region before Spain`() {
        assertEquals(listOf("es-MX", "es-ES", "es"), tags(VoiceLocale.candidates(VoiceLanguage.ES, Locale("es", "MX"))))
    }

    @Test fun `a Spanish app on an English phone still gets Spain's voice`() {
        assertEquals(listOf("es-ES", "es"), tags(VoiceLocale.candidates(VoiceLanguage.ES, Locale("en", "US"))))
    }

    @Test fun `English follows the phone's region and ends with the bare language`() {
        assertEquals(listOf("en-GB", "en"), tags(VoiceLocale.candidates(VoiceLanguage.EN, Locale("en", "GB"))))
        assertEquals(listOf("en"), tags(VoiceLocale.candidates(VoiceLanguage.EN, Locale("es", "ES"))))
    }

    private fun v(name: String, tag: String, quality: Int = 400, network: Boolean = false, installed: Boolean = true) =
        VoiceOption(name, Locale.forLanguageTag(tag), quality, network, installed)

    @Test fun `picks the Spain voice over the Latin American one`() {
        val voices = listOf(v("es-us-a", "es-US", quality = 500), v("es-es-a", "es-ES", quality = 300), v("es-mx-a", "es-MX"))
        assertEquals("es-es-a", VoiceLocale.pickVoice(voices, Locale("es", "ES"))?.name)
    }

    @Test fun `prefers installed offline voices, then quality`() {
        val voices = listOf(
            v("net", "es-ES", quality = 500, network = true),
            v("missing", "es-ES", quality = 500, installed = false),
            v("low", "es-ES", quality = 200),
            v("high", "es-ES", quality = 400),
        )
        assertEquals("high", VoiceLocale.pickVoice(voices, Locale("es", "ES"))?.name)
    }

    @Test fun `a bare language accepts any region and nothing matching gives null`() {
        assertEquals("es-mx-a", VoiceLocale.pickVoice(listOf(v("es-mx-a", "es-MX")), Locale("es"))?.name)
        assertNull(VoiceLocale.pickVoice(listOf(v("en", "en-US")), Locale("es", "ES")))
    }
}
