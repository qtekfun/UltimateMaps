package com.qtekfun.mapas.core.voice

import com.qtekfun.mapas.core.transit.follow.FollowPrompt
import com.qtekfun.mapas.core.transit.follow.PromptKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitPhrasesTest {
    private fun en(p: FollowPrompt) = TransitPhrases.of(p, VoiceLanguage.EN)
    private fun es(p: FollowPrompt) = TransitPhrases.of(p, VoiceLanguage.ES)

    @Test
    fun `every prompt kind has a sentence in both languages`() {
        for (k in PromptKind.entries) {
            val p = FollowPrompt(k, "27", "Hospital", "Plaza Mayor", 2)
            assertTrue(en(p).isNotBlank(), "$k en")
            assertTrue(es(p).isNotBlank(), "$k es")
            assertTrue(en(p) != es(p), "$k is translated")
        }
    }

    @Test
    fun `boarding names the line and the direction`() {
        val p = FollowPrompt(PromptKind.BOARD_NOW, "27", "Hospital", "Plaza Mayor")
        assertEquals("Board line 27 towards Hospital now", en(p))
        assertEquals("Sube ahora a la línea 27 hacia Hospital", es(p))
    }

    @Test
    fun `getting off names the stop, and an estimate says it is by the timetable`() {
        val sure = FollowPrompt(PromptKind.GET_OFF_NOW, "5", stop = "Sol")
        assertEquals("Get off now at Sol", en(sure))
        assertEquals("Baja ahora en Sol", es(sure))
        val guess = sure.copy(estimated = true)
        assertTrue(en(guess).startsWith("By the timetable"), en(guess))
        assertTrue(es(guess).startsWith("Según el horario"), es(guess))
        val ready = FollowPrompt(PromptKind.GET_READY, "5", stop = "Sol")
        assertEquals("Get ready to get off at Sol, the next stop", en(ready))
        assertEquals("Prepárate para bajar en Sol, la próxima parada", es(ready))
        assertTrue(en(ready.copy(estimated = true)).contains("nearly at Sol"))
    }

    @Test
    fun `missing names are left out and never read as null`() {
        for (k in PromptKind.entries) {
            val p = FollowPrompt(k)
            assertFalse(en(p).contains("null", ignoreCase = true), "$k: ${en(p)}")
            assertFalse(es(p).contains("null", ignoreCase = true), "$k: ${es(p)}")
            assertFalse(en(p).contains("  "), "$k double space")
        }
        assertEquals("Get off now", en(FollowPrompt(PromptKind.GET_OFF_NOW)))
        assertEquals("Change here to line C-4", en(FollowPrompt(PromptKind.CHANGE_HERE, "C-4")))
        assertEquals("Cambia aquí a la línea C-4", es(FollowPrompt(PromptKind.CHANGE_HERE, "C-4", "")))
    }

    @Test
    fun `connection and plan prompts offer planning again without promising anything`() {
        assertEquals("Your connection to line 27 is at risk", en(FollowPrompt(PromptKind.CONNECTION_AT_RISK, "27")))
        assertTrue(en(FollowPrompt(PromptKind.CONNECTION_MISSED, "27")).contains("may have missed"))
        assertTrue(es(FollowPrompt(PromptKind.OFF_PLAN)).contains("Estás fuera del itinerario"))
        assertEquals("You have arrived", en(FollowPrompt(PromptKind.ARRIVED)))
    }

    @Test
    fun `time critical prompts are urgent and the rest normal`() {
        assertEquals(VoicePriority.URGENT, TransitPhrases.priority(PromptKind.BOARD_NOW))
        assertEquals(VoicePriority.URGENT, TransitPhrases.priority(PromptKind.GET_OFF_NOW))
        assertEquals(VoicePriority.URGENT, TransitPhrases.priority(PromptKind.ARRIVED))
        assertEquals(VoicePriority.NORMAL, TransitPhrases.priority(PromptKind.GET_READY))
        assertEquals(PromptKind.entries.size, PromptKind.entries.map { TransitPhrases.key(it) }.toSet().size)
    }
}
