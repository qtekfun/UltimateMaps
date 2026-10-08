package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.nav.Announcement
import com.qtekfun.ultimatemaps.core.nav.AnnouncementKind
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Motorway exit sentences: exit number and what is signposted, with each piece optional (never invented). */
class InstructionTextExitTest {
    private val es = VoiceLanguage.ES
    private val en = VoiceLanguage.EN

    private fun say(
        type: TurnType = TurnType.EXIT_RIGHT,
        ref: String? = null,
        road: String? = null,
        place: String? = null,
        street: String? = null,
        kind: AnnouncementKind = AnnouncementKind.FAR,
        meters: Int = 800,
        lang: VoiceLanguage,
    ) = InstructionText.of(Announcement(Maneuver(0, type, street, exitRef = ref, towardRef = road, towardName = place), kind, meters), DistanceUnits.METRIC, lang)

    @Test fun `the sentence of the task in English`() {
        assertEquals("In 800 meters, take exit 23 on the right toward A 2", say(ref = "23", road = "A-2", lang = en))
        assertEquals("In 800 meters, take exit 23 on the left toward A 2, Alcalá de Henares", say(TurnType.EXIT_LEFT, "23", "A-2", "Alcalá de Henares", lang = en))
    }

    @Test fun `the sentence of the task in Spanish`() {
        assertEquals("En 800 metros, toma la salida 23 a la derecha hacia A 2", say(ref = "23", road = "A-2", lang = es))
        assertEquals("En 800 metros, toma la salida 23 a la izquierda hacia A 2, Alcalá de Henares", say(TurnType.EXIT_LEFT, "23", "A-2", "Alcalá de Henares", lang = es))
    }

    @Test fun `exit numbers with a letter are read as number and letter`() {
        assertEquals("In 800 meters, take exit 12 A on the right", say(ref = "12A", lang = en))
        assertEquals("En 800 metros, toma la salida 12 A a la derecha", say(ref = "12A", lang = es))
    }

    @Test fun `without a toward the street of the maneuver is used as before`() {
        assertEquals("In 800 meters, take exit 23 on the right toward Calle Sol", say(ref = "23", street = "Calle Sol", lang = en))
        assertEquals("En 800 metros, toma la salida 23 a la derecha hacia Calle Sol", say(ref = "23", street = "Calle Sol", lang = es))
    }

    @Test fun `a toward without an exit number keeps the old sentence shape`() {
        assertEquals("In 800 meters, take the exit on the right toward A 2", say(road = "A-2", lang = en))
        assertEquals("En 800 metros, toma la salida de la derecha hacia A 2", say(road = "A-2", lang = es))
        assertEquals("En 800 metros, toma la salida de la derecha hacia Alcalá de Henares", say(place = "Alcalá de Henares", lang = es))
    }

    @Test fun `with no exit data at all the old sentences are unchanged`() {
        assertEquals("In 800 meters, take the exit on the right", say(lang = en))
        assertEquals("En 800 metros, toma la salida de la izquierda hacia Calle Sol", say(TurnType.EXIT_LEFT, street = "Calle Sol", lang = es))
    }

    @Test fun `two roads and two places are joined with and`() {
        assertEquals("In 800 meters, take exit 5 on the right toward M 40 and A 2, Torrejón and Alcalá", say(ref = "5", road = "M-40;A-2;R-3", place = "Torrejón; Alcalá; Guadalajara", lang = en))
        assertEquals("En 800 metros, toma la salida 5 a la derecha hacia M 40 y A 2", say(ref = "5", road = "M-40;A-2", lang = es))
    }

    @Test fun `a ramp the core called a slight turn is an exit when the map gives it a number`() {
        assertEquals("In 800 meters, take exit 23 on the right toward A 2", say(TurnType.SLIGHT_RIGHT, "23", "A-2", lang = en))
        assertEquals("En 800 metros, toma la salida 23 a la izquierda hacia A 2", say(TurnType.SLIGHT_LEFT, "23", "A-2", lang = es))
        assertEquals("In 800 meters, take exit 23 toward A 2", say(TurnType.STRAIGHT, "23", "A-2", lang = en))
    }

    @Test fun `without an exit number a slight turn keeps its old sentence even if it has a toward`() {
        assertEquals("In 800 meters, bear slightly right onto Calle Sol", say(TurnType.SLIGHT_RIGHT, road = "A-2", street = "Calle Sol", lang = en))
    }

    @Test fun `exit data never leaks into roundabouts, merges or the arrival`() {
        assertEquals("In 800 meters, merge onto Calle Sol", say(TurnType.MERGE, "23", "A-2", street = "Calle Sol", lang = en))
        assertEquals("In 800 meters, enter the roundabout", say(TurnType.ROUNDABOUT_ENTER, "23", "A-2", lang = en))
        assertEquals("In 800 meters, you will arrive at your destination", say(TurnType.ARRIVE, "23", "A-2", lang = en))
    }

    @Test fun `near and now prompts say the exit too`() {
        assertEquals("Now, take exit 23 on the right toward A 2", say(ref = "23", road = "A-2", kind = AnnouncementKind.NOW, meters = 10, lang = en))
        assertEquals("Ahora, toma la salida 23 a la derecha hacia A 2", say(ref = "23", road = "A-2", kind = AnnouncementKind.NOW, meters = 10, lang = es))
    }

    @Test fun `only important prompts keeps the exit prompts, far ones included`() {
        val exit = Maneuver(0, TurnType.EXIT_RIGHT, exitRef = "23", towardRef = "A-2")
        for (k in AnnouncementKind.entries) assertTrue(Announcement(exit, k, 800).isImportant(), "$k")
        val ramp = Maneuver(0, TurnType.SLIGHT_RIGHT, exitRef = "23")
        for (k in AnnouncementKind.entries) assertTrue(Announcement(ramp, k, 800).isImportant(), "ramp $k")
        // A slight bend with no exit number is still dropped.
        assertFalse(Announcement(Maneuver(0, TurnType.SLIGHT_RIGHT, towardRef = "A-2"), AnnouncementKind.NEAR, 300).isImportant())
    }
}
