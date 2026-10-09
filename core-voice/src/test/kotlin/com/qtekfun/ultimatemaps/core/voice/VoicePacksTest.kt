package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.nav.Announcement
import com.qtekfun.ultimatemaps.core.nav.AnnouncementKind
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPrompt
import com.qtekfun.ultimatemaps.core.transit.follow.PromptKind
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spoken guidance in the languages that are [VoicePacks]: every language has a pack, no sentence leaves a `{slot}` unfilled
 * or says "null", and a few sentences per language are checked word for word (they are the ones a reviewer should read).
 */
class VoicePacksTest {
    private val packLanguages = VoiceLanguage.entries.filter { it != VoiceLanguage.ES && it != VoiceLanguage.EN }

    private fun say(
        type: TurnType,
        kind: AnnouncementKind = AnnouncementKind.FAR,
        meters: Int = 300,
        street: String? = null,
        exit: Int? = null,
        lanes: List<Lane> = emptyList(),
        ref: String? = null,
        road: String? = null,
        lang: VoiceLanguage,
        units: DistanceUnits = DistanceUnits.METRIC,
    ) = InstructionText.of(Announcement(Maneuver(0, type, street, exit, lanes, exitRef = ref, towardRef = road), kind, meters), units, lang)

    private fun lanes(vararg recommended: Boolean) = recommended.map { Lane(setOf(LaneDirection.THROUGH), it) }

    @Test
    fun `every language except Spanish and English has a pack and those two have none`() {
        for (l in packLanguages) assertNotNull(VoicePacks.of(l), "no pack for $l")
        assertNull(VoicePacks.of(VoiceLanguage.ES))
        assertNull(VoicePacks.of(VoiceLanguage.EN))
    }

    @Test
    fun `the packs are complete`() {
        for (l in packLanguages) {
            val p = VoicePacks.of(l)!!.instructions
            assertEquals(10, p.exitOrdinals.size, "$l exit ordinals")
            assertEquals(4, p.laneOrdinals.size, "$l lane ordinals")
            assertTrue("{n}" in p.distanceLead && "{u}" in p.distanceLead, "$l distance lead")
            assertTrue("{n}" in p.exitNumber, "$l exit number")
            assertTrue("{x}" in p.roundaboutTake, "$l roundabout")
            assertTrue("{s}" in p.toward, "$l toward")
            assertTrue("{ref}" in p.exitRefLeft && "{ref}" in p.exitRefRight && "{ref}" in p.exitRefStraight, "$l exit with number")
            assertTrue("{side}" in p.arriveNowSide && "{side}" in p.arriveSoonSide && "{lead}" in p.arriveSoonSide && "{lead}" in p.arriveSoon, "$l arrival")
            assertTrue("{n}" in p.laneLeftMany && "{n}" in p.laneRightMany && "{ord}" in p.laneFromLeft, "$l lanes")
            for (street in listOf(p.depart, p.straight, p.slightRight, p.right, p.sharpRight, p.slightLeft, p.left, p.sharpLeft, p.roundaboutLeave, p.merge)) {
                assertTrue("{s}" in street.withStreet && "{s}" !in street.plain, "$l a street phrase: ${street.plain}")
            }
            val t = VoicePacks.of(l)!!.transit
            assertTrue("{line}" in t.lineNamed && "{line}" in t.lineTowards && "{to}" in t.lineTowards, "$l transit line")
            assertTrue("{x}" in t.boardNow && "{x}" in t.changeHere, "$l transit board/change")
            assertTrue("{stop}" in t.getReadyEstimated && "{stop}" in t.getReadyStop && "{stop}" in t.getOffEstimated && "{stop}" in t.getOffStop, "$l transit stop")
            assertTrue("{line}" in t.connectionAtRisk && "{line}" in t.connectionMissed, "$l transit connection")
            val a = VoicePacks.of(l)!!.alerts
            assertTrue("{n}" in a.limit, "$l alert limit")
            assertTrue("{lead}" in VoicePacks.of(l)!!.zbeAhead, "$l zbe")
        }
    }

    @Test
    fun `no maneuver sentence has an open slot or a null in any language`() {
        for (l in packLanguages) {
            for (type in TurnType.entries) for (street in listOf(null, "Calle Sol")) for (kind in AnnouncementKind.entries) {
                for (exit in listOf(null, 1, 10, 11)) {
                    val text = say(type, kind, 300, street, exit, lanes(true, false), lang = l)
                    assertTrue('{' !in text && '}' !in text, "$l $type: $text")
                    assertFalse(text.contains("null", ignoreCase = true), "$l $type: $text")
                    assertTrue(text.isNotBlank() && text.first().isUpperCase(), "$l $type starts with a capital: $text")
                }
            }
            for (m in VoiceMessage.entries) assertTrue(InstructionText.of(m, l).isNotBlank(), "$l $m")
            assertTrue(InstructionText.test(DistanceUnits.METRIC, l).isNotBlank())
        }
    }

    @Test
    fun `an immediate prompt starts with the word for now, except the roundabout, the departure and the arrival`() {
        for (l in packLanguages) {
            val now = VoicePacks.of(l)!!.instructions.now.replaceFirstChar { it.titlecase(l.locale) }
            for (type in TurnType.entries) {
                val text = say(type, AnnouncementKind.NOW, 10, "X", exit = 1, lang = l)
                val noNow = type == TurnType.DEPART || type == TurnType.ROUNDABOUT_ENTER || type.name.startsWith("ARRIVE")
                assertEquals(!noNow, text.startsWith("$now, "), "$l $type: $text")
            }
        }
    }

    @Test
    fun `every language says something different from English for the same maneuver`() {
        val english = say(TurnType.LEFT, street = "Calle Sol", lang = VoiceLanguage.EN)
        for (l in packLanguages) assertTrue(say(TurnType.LEFT, street = "Calle Sol", lang = l) != english, "$l")
    }

    @Test
    fun `Catalan sentences`() {
        val l = VoiceLanguage.CA
        assertEquals("D'aquí a 300 metres, gira a l'esquerra per Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("A la rotonda, pren la segona sortida cap a Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("D'aquí a 300 metres, la destinació serà a l'esquerra", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Has arribat a la destinació", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("D'aquí a 800 metres, pren la sortida 23 a la dreta cap a A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        assertEquals("D'aquí a 1,5 quilòmetres, continua recte", say(TurnType.STRAIGHT, meters = 1500, lang = l))
        assertEquals("D'aquí a 100 metres, pren la sortida de la dreta. Mantén-te al carril de l'esquerra", say(TurnType.EXIT_RIGHT, AnnouncementKind.NEAR, 100, lanes = lanes(true, false), lang = l))
    }

    @Test
    fun `Galician sentences`() {
        val l = VoiceLanguage.GL
        assertEquals("En 300 metros, xira á esquerda en Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("Na rotonda, colle a segunda saída cara a Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("En 300 metros, o teu destino estará á esquerda", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Chegaches ao teu destino", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("En 800 metros, colle a saída 23 á dereita cara a A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        assertEquals("En 1,5 quilómetros, segue recto", say(TurnType.STRAIGHT, meters = 1500, lang = l))
    }

    @Test
    fun `French sentences`() {
        val l = VoiceLanguage.FR
        assertEquals("Dans 300 mètres, tournez à gauche sur Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("Au rond-point, prenez la deuxième sortie en direction de Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("Dans 300 mètres, votre destination sera à gauche", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Vous êtes arrivé à destination", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("Dans 800 mètres, prenez la sortie 23 à droite en direction de A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        // French keeps the singular below two: "1,5 kilomètre".
        assertEquals("Dans 1,5 kilomètre, continuez tout droit", say(TurnType.STRAIGHT, meters = 1500, lang = l))
        assertEquals("Dans 3 kilomètres, continuez tout droit", say(TurnType.STRAIGHT, meters = 3000, lang = l))
    }

    @Test
    fun `German sentences`() {
        val l = VoiceLanguage.DE
        assertEquals("In 300 Metern, links abbiegen auf Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("Im Kreisverkehr die zweite Ausfahrt nehmen Richtung Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("In 300 Metern, liegt Ihr Ziel links", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Sie haben Ihr Ziel erreicht", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("In 800 Metern, Ausfahrt 23 rechts nehmen Richtung A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        assertEquals("In 1,5 Kilometern, geradeaus weiterfahren", say(TurnType.STRAIGHT, meters = 1500, lang = l))
        assertEquals("In 1 Kilometer, geradeaus weiterfahren", say(TurnType.STRAIGHT, meters = 1000, lang = l))
    }

    @Test
    fun `Portuguese sentences`() {
        val l = VoiceLanguage.PT
        assertEquals("Em 300 metros, vire à esquerda para Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("Na rotunda, saia pela segunda saída em direção a Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("Em 300 metros, o seu destino ficará à esquerda", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Chegou ao seu destino", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("Em 800 metros, apanhe a saída 23 à direita em direção a A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        assertEquals("Em 1,5 quilómetros, siga em frente", say(TurnType.STRAIGHT, meters = 1500, lang = l))
    }

    @Test
    fun `Italian sentences`() {
        val l = VoiceLanguage.IT
        assertEquals("Tra 300 metri, svolta a sinistra in Calle Sol", say(TurnType.LEFT, street = "Calle Sol", lang = l))
        assertEquals("Alla rotatoria, prendi la seconda uscita verso Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", 2, lang = l))
        assertEquals("Tra 300 metri, la tua destinazione sarà a sinistra", say(TurnType.ARRIVE_LEFT, lang = l))
        assertEquals("Sei arrivato a destinazione", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = l))
        assertEquals("Tra 800 metri, prendi l'uscita 23 a destra verso A 2", say(TurnType.EXIT_RIGHT, meters = 800, ref = "23", road = "A-2", lang = l))
        assertEquals("Tra 1,5 chilometri, prosegui dritto", say(TurnType.STRAIGHT, meters = 1500, lang = l))
    }

    @Test
    fun `imperial distances use the feet and miles of the language`() {
        assertEquals("Dans 500 pieds, tournez à droite", say(TurnType.RIGHT, meters = 152, lang = VoiceLanguage.FR, units = DistanceUnits.IMPERIAL))
        assertEquals("In 0,5 Meilen, rechts abbiegen", say(TurnType.RIGHT, meters = 800, lang = VoiceLanguage.DE, units = DistanceUnits.IMPERIAL))
    }

    @Test
    fun `the exit past the tenth is read as a number`() {
        assertEquals("Au rond-point, prenez la sortie numéro 11", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, exit = 11, lang = VoiceLanguage.FR))
    }

    // ---- public transport prompts

    private fun transit(kind: PromptKind, l: VoiceLanguage, line: String? = "27", towards: String? = "Hospital", stop: String? = "Sol", estimated: Boolean = false) =
        TransitPhrases.of(FollowPrompt(kind, line, towards, stop, estimated = estimated), l)

    @Test
    fun `transit prompts exist for every kind and language and never leave a slot open`() {
        for (l in packLanguages) for (k in PromptKind.entries) for (line in listOf(null, "27")) for (stop in listOf(null, "Sol")) for (est in listOf(false, true)) {
            val text = transit(k, l, line, if (line == null) null else "Hospital", stop, est)
            assertTrue(text.isNotBlank(), "$l $k")
            assertTrue('{' !in text && '}' !in text, "$l $k: $text")
            assertFalse(text.contains("null", ignoreCase = true), "$l $k: $text")
        }
    }

    @Test
    fun `transit sentences`() {
        assertEquals("Agafa ara la línia 27 direcció Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.CA))
        assertEquals("Colle agora a liña 27 dirección Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.GL))
        assertEquals("Montez maintenant dans la ligne 27 en direction de Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.FR))
        assertEquals("Jetzt einsteigen: Linie 27 Richtung Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.DE))
        assertEquals("Apanhe agora a linha 27 em direção a Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.PT))
        assertEquals("Sali ora: la linea 27 in direzione Hospital", transit(PromptKind.BOARD_NOW, VoiceLanguage.IT))
        assertEquals("Descendez maintenant à Sol", transit(PromptKind.GET_OFF_NOW, VoiceLanguage.FR))
        assertTrue(transit(PromptKind.GET_OFF_NOW, VoiceLanguage.FR, estimated = true).startsWith("D'après l'horaire"))
        assertEquals("Prepara't per baixar a la propera parada", transit(PromptKind.GET_READY, VoiceLanguage.CA, stop = null))
    }

    @Test
    fun `boarding and arriving stay urgent whatever the language`() {
        for (k in PromptKind.entries) assertEquals(TransitPhrases.priority(k), TransitPhrases.priority(k))
        assertEquals(VoicePriority.URGENT, TransitPhrases.priority(PromptKind.BOARD_NOW))
    }

    // ---- language choice

    @Test
    fun `the voice follows the language of the app when there is guidance in it`() {
        val expected = mapOf("es" to VoiceLanguage.ES, "en" to VoiceLanguage.EN, "ca" to VoiceLanguage.CA, "gl" to VoiceLanguage.GL, "fr" to VoiceLanguage.FR, "de" to VoiceLanguage.DE, "pt" to VoiceLanguage.PT, "it" to VoiceLanguage.IT)
        for ((tag, language) in expected) assertEquals(language, VoiceLanguagePref.AUTO.resolve(Locale.forLanguageTag(tag)), tag)
        assertEquals(VoiceLanguage.PT, VoiceLanguagePref.AUTO.resolve(Locale.forLanguageTag("pt-BR")))
    }

    @Test
    fun `a language without spoken guidance falls back to English`() {
        // Basque and Dutch have no pack: English is spoken, and the user can still pick Spanish by hand.
        assertEquals(VoiceLanguage.EN, VoiceLanguagePref.AUTO.resolve(Locale.forLanguageTag("eu")))
        assertEquals(VoiceLanguage.EN, VoiceLanguagePref.AUTO.resolve(Locale.forLanguageTag("nl-NL")))
        assertEquals(VoiceLanguage.EN, VoiceLanguagePref.AUTO.resolve(Locale.ROOT))
    }

    @Test
    fun `a chosen language wins over the language of the app`() {
        assertEquals(VoiceLanguage.IT, VoiceLanguagePref.IT.resolve(Locale.forLanguageTag("es")))
        assertEquals(VoiceLanguage.ES, VoiceLanguagePref.ES.resolve(Locale.forLanguageTag("fr")))
    }

    @Test
    fun `every voice language can be chosen and the choices keep their saved names`() {
        assertEquals(VoiceLanguage.entries.map { it.name }, VoiceLanguagePref.entries.filter { it != VoiceLanguagePref.AUTO }.map { it.name })
        // The saved value of the first three choices is their name: settings written before the other languages must still load.
        assertEquals(listOf("AUTO", "ES", "EN"), VoiceLanguagePref.entries.take(3).map { it.name })
    }

    @Test
    fun `the text to speech engine is asked for the region of the voice first`() {
        assertEquals(listOf(Locale("ca", "ES"), Locale("ca")), VoiceLocale.candidates(VoiceLanguage.CA, Locale.FRANCE))
        assertEquals(listOf(Locale("pt", "PT"), Locale("pt")), VoiceLocale.candidates(VoiceLanguage.PT, Locale.FRANCE))
        assertEquals(listOf(Locale("pt", "BR"), Locale("pt", "PT"), Locale("pt")), VoiceLocale.candidates(VoiceLanguage.PT, Locale("pt", "BR")))
        assertEquals(listOf(Locale("fr", "CA"), Locale("fr")), VoiceLocale.candidates(VoiceLanguage.FR, Locale.CANADA_FRENCH))
        assertEquals(listOf(Locale("de")), VoiceLocale.candidates(VoiceLanguage.DE, Locale.ITALY))
    }
}
