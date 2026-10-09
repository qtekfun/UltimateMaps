package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.nav.Announcement
import com.qtekfun.ultimatemaps.core.nav.AnnouncementKind
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstructionTextTest {
    private val m = DistanceUnits.METRIC
    private val es = VoiceLanguage.ES
    private val en = VoiceLanguage.EN

    private fun say(
        type: TurnType,
        kind: AnnouncementKind = AnnouncementKind.FAR,
        meters: Int = 300,
        street: String? = null,
        exit: Int? = null,
        lanes: List<Lane> = emptyList(),
        units: DistanceUnits = m,
        lang: VoiceLanguage = es,
    ) = InstructionText.of(Announcement(Maneuver(0, type, street, exit, lanes), kind, meters), units, lang)

    // ---- The sentences of the task, word for word

    @Test fun spanishExamples() {
        assertEquals("En 300 metros, gira a la izquierda en Calle de Alcalá", say(TurnType.LEFT, street = "Calle de Alcalá"))
        assertEquals("Ahora, gira a la derecha", say(TurnType.RIGHT, AnnouncementKind.NOW, 10))
        assertEquals(
            "En la rotonda, toma la segunda salida hacia Avenida de la Ciudad de Barcelona",
            say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Avenida de la Ciudad de Barcelona", exit = 2),
        )
        assertEquals("Has llegado a tu destino", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5))
        assertEquals("Parada alcanzada", InstructionText.of(VoiceMessage.STOP_REACHED, es))
        assertEquals("Recalculando", InstructionText.of(VoiceMessage.RECALCULATING, es))
        assertEquals("Has salido de la ruta", InstructionText.of(VoiceMessage.OFF_ROUTE, es))
        assertEquals("Has llegado a tu destino", InstructionText.of(VoiceMessage.ARRIVED, es))
    }

    @Test fun englishExamples() {
        assertEquals("In 300 meters, turn left onto Calle de Alcalá", say(TurnType.LEFT, street = "Calle de Alcalá", lang = en))
        assertEquals("Now, turn right", say(TurnType.RIGHT, AnnouncementKind.NOW, 10, lang = en))
        assertEquals("At the roundabout, take the second exit toward Gran Via", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 15, "Gran Via", exit = 2, lang = en))
        assertEquals("You have arrived at your destination", say(TurnType.ARRIVE, AnnouncementKind.NOW, 5, lang = en))
        assertEquals("Stop reached", InstructionText.of(VoiceMessage.STOP_REACHED, en))
        assertEquals("Recalculating", InstructionText.of(VoiceMessage.RECALCULATING, en))
        assertEquals("You have left the route", InstructionText.of(VoiceMessage.OFF_ROUTE, en))
        assertEquals("You have arrived at your destination", InstructionText.of(VoiceMessage.ARRIVED, en))
    }

    // ---- Every turn type, with and without a street, in both languages

    @Test fun everyTurnTypeInSpanish() {
        val expected = mapOf(
            TurnType.DEPART to ("Comienza la ruta" to "Comienza la ruta por Calle Sol"),
            TurnType.STRAIGHT to ("En 300 metros, sigue recto" to "En 300 metros, sigue recto por Calle Sol"),
            TurnType.SLIGHT_RIGHT to ("En 300 metros, gira ligeramente a la derecha" to "En 300 metros, gira ligeramente a la derecha en Calle Sol"),
            TurnType.RIGHT to ("En 300 metros, gira a la derecha" to "En 300 metros, gira a la derecha en Calle Sol"),
            TurnType.SHARP_RIGHT to ("En 300 metros, gira cerrado a la derecha" to "En 300 metros, gira cerrado a la derecha en Calle Sol"),
            TurnType.SLIGHT_LEFT to ("En 300 metros, gira ligeramente a la izquierda" to "En 300 metros, gira ligeramente a la izquierda en Calle Sol"),
            TurnType.LEFT to ("En 300 metros, gira a la izquierda" to "En 300 metros, gira a la izquierda en Calle Sol"),
            TurnType.SHARP_LEFT to ("En 300 metros, gira cerrado a la izquierda" to "En 300 metros, gira cerrado a la izquierda en Calle Sol"),
            TurnType.U_TURN_LEFT to ("En 300 metros, haz un cambio de sentido a la izquierda" to "En 300 metros, haz un cambio de sentido a la izquierda"),
            TurnType.U_TURN_RIGHT to ("En 300 metros, haz un cambio de sentido a la derecha" to "En 300 metros, haz un cambio de sentido a la derecha"),
            TurnType.ROUNDABOUT_ENTER to ("En 300 metros, entra en la rotonda" to "En 300 metros, entra en la rotonda"),
            TurnType.ROUNDABOUT_LEAVE to ("En 300 metros, sal de la rotonda" to "En 300 metros, sal de la rotonda hacia Calle Sol"),
            TurnType.EXIT_LEFT to ("En 300 metros, toma la salida de la izquierda" to "En 300 metros, toma la salida de la izquierda hacia Calle Sol"),
            TurnType.EXIT_RIGHT to ("En 300 metros, toma la salida de la derecha" to "En 300 metros, toma la salida de la derecha hacia Calle Sol"),
            TurnType.MERGE to ("En 300 metros, incorpórate a la vía" to "En 300 metros, incorpórate a Calle Sol"),
            TurnType.ARRIVE to ("En 300 metros, llegarás a tu destino" to "En 300 metros, llegarás a tu destino"),
            TurnType.ARRIVE_LEFT to ("En 300 metros, tu destino estará a la izquierda" to "En 300 metros, tu destino estará a la izquierda"),
            TurnType.ARRIVE_RIGHT to ("En 300 metros, tu destino estará a la derecha" to "En 300 metros, tu destino estará a la derecha"),
        )
        assertEquals(TurnType.entries.toSet(), expected.keys, "a TurnType has no case")
        for ((type, pair) in expected) {
            assertEquals(pair.first, say(type, lang = es), "$type without street")
            assertEquals(pair.second, say(type, street = "Calle Sol", lang = es), "$type with street")
        }
    }

    @Test fun everyTurnTypeInEnglish() {
        val expected = mapOf(
            TurnType.DEPART to ("Start the route" to "Start the route on Oak St"),
            TurnType.STRAIGHT to ("In 300 meters, continue straight" to "In 300 meters, continue straight on Oak St"),
            TurnType.SLIGHT_RIGHT to ("In 300 meters, bear slightly right" to "In 300 meters, bear slightly right onto Oak St"),
            TurnType.RIGHT to ("In 300 meters, turn right" to "In 300 meters, turn right onto Oak St"),
            TurnType.SHARP_RIGHT to ("In 300 meters, make a sharp right" to "In 300 meters, make a sharp right onto Oak St"),
            TurnType.SLIGHT_LEFT to ("In 300 meters, bear slightly left" to "In 300 meters, bear slightly left onto Oak St"),
            TurnType.LEFT to ("In 300 meters, turn left" to "In 300 meters, turn left onto Oak St"),
            TurnType.SHARP_LEFT to ("In 300 meters, make a sharp left" to "In 300 meters, make a sharp left onto Oak St"),
            TurnType.U_TURN_LEFT to ("In 300 meters, make a U-turn to the left" to "In 300 meters, make a U-turn to the left"),
            TurnType.U_TURN_RIGHT to ("In 300 meters, make a U-turn to the right" to "In 300 meters, make a U-turn to the right"),
            TurnType.ROUNDABOUT_ENTER to ("In 300 meters, enter the roundabout" to "In 300 meters, enter the roundabout"),
            TurnType.ROUNDABOUT_LEAVE to ("In 300 meters, exit the roundabout" to "In 300 meters, exit the roundabout onto Oak St"),
            TurnType.EXIT_LEFT to ("In 300 meters, take the exit on the left" to "In 300 meters, take the exit on the left toward Oak St"),
            TurnType.EXIT_RIGHT to ("In 300 meters, take the exit on the right" to "In 300 meters, take the exit on the right toward Oak St"),
            TurnType.MERGE to ("In 300 meters, merge" to "In 300 meters, merge onto Oak St"),
            TurnType.ARRIVE to ("In 300 meters, you will arrive at your destination" to "In 300 meters, you will arrive at your destination"),
            TurnType.ARRIVE_LEFT to ("In 300 meters, your destination will be on the left" to "In 300 meters, your destination will be on the left"),
            TurnType.ARRIVE_RIGHT to ("In 300 meters, your destination will be on the right" to "In 300 meters, your destination will be on the right"),
        )
        assertEquals(TurnType.entries.toSet(), expected.keys)
        for ((type, pair) in expected) {
            assertEquals(pair.first, say(type, lang = en), "$type without street")
            assertEquals(pair.second, say(type, street = "Oak St", lang = en), "$type with street")
        }
    }

    @Test fun nowPromptsOfEveryTurnTypeStartWithNowExceptRoundaboutsAndDeparture() {
        for (lang in listOf(es, en)) for (type in TurnType.entries) { // the other languages: VoicePacksTest
            val text = say(type, AnnouncementKind.NOW, 10, "X", exit = 1, lang = lang)
            val now = if (lang == es) "Ahora, " else "Now, "
            val noNow = type == TurnType.DEPART || type == TurnType.ROUNDABOUT_ENTER || type.name.startsWith("ARRIVE")
            assertEquals(!noNow, text.startsWith(now), "$lang $type: $text")
            assertFalse("null" in text.lowercase().split(" "), text)
        }
    }

    @Test fun arrivalsAtTheDestinationNow() {
        assertEquals("Has llegado a tu destino, a la izquierda", say(TurnType.ARRIVE_LEFT, AnnouncementKind.NOW, 5))
        assertEquals("Has llegado a tu destino, a la derecha", say(TurnType.ARRIVE_RIGHT, AnnouncementKind.NOW, 5))
        assertEquals("You have arrived at your destination, on the left", say(TurnType.ARRIVE_LEFT, AnnouncementKind.NOW, 5, lang = en))
        assertEquals("You have arrived at your destination, on the right", say(TurnType.ARRIVE_RIGHT, AnnouncementKind.NOW, 5, lang = en))
    }

    // ---- Street names that are not there

    @Test fun absentStreetNamesAreNeverSpoken() {
        for (name in listOf(null, "", "   ", "null", "NULL", " Null ", "none")) {
            assertEquals("En 300 metros, gira a la izquierda", say(TurnType.LEFT, street = name), "[$name]")
            assertEquals("In 300 meters, turn left", say(TurnType.LEFT, street = name, lang = en), "[$name]")
            assertEquals("En la rotonda, toma la segunda salida", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, name, exit = 2))
        }
        // A street that merely contains "null" is a street.
        assertEquals("Ahora, gira a la izquierda en Nullarbor Road", say(TurnType.LEFT, AnnouncementKind.NOW, 10, "Nullarbor Road"))
    }

    @Test fun streetNamesAreTrimmed() {
        assertEquals("Ahora, gira a la derecha en Gran Vía", say(TurnType.RIGHT, AnnouncementKind.NOW, 10, "  Gran Vía "))
    }

    // ---- Roundabout exits

    @Test fun roundaboutExitsInSpanish() {
        val ordinals = listOf("primera", "segunda", "tercera", "cuarta", "quinta", "sexta", "séptima", "octava", "novena", "décima")
        ordinals.forEachIndexed { i, word ->
            assertEquals("En la rotonda, toma la $word salida", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = i + 1))
        }
        assertEquals("En la rotonda, toma la salida número 11", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = 11))
        assertEquals("En 200 metros, en la rotonda, toma la tercera salida hacia Ronda Norte", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.FAR, 200, "Ronda Norte", exit = 3))
    }

    @Test fun roundaboutExitsInEnglish() {
        val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
        ordinals.forEachIndexed { i, word ->
            assertEquals("At the roundabout, take the $word exit", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = i + 1, lang = en))
        }
        assertEquals("At the roundabout, take exit number 12", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = 12, lang = en))
    }

    @Test fun aRoundaboutWithoutExitOrWithABogusOneSaysJustEnter() {
        for (exit in listOf(null, 0, -1)) {
            assertEquals("Entra en la rotonda", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = exit))
            assertEquals("Enter the roundabout", say(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.NOW, 10, exit = exit, lang = en))
        }
    }

    // ---- Distances

    private fun spoken(meters: Int, units: DistanceUnits = m, lang: VoiceLanguage = es) =
        say(TurnType.LEFT, AnnouncementKind.FAR, meters, units = units, lang = lang).substringBefore(", ")

    @Test fun metricDistancesInSpanish() {
        val cases = mapOf(
            2000 to "En 2 kilómetros", 1990 to "En 2 kilómetros", 1750 to "En 2 kilómetros", 1500 to "En 1,5 kilómetros", 1300 to "En 1,5 kilómetros",
            1249 to "En 1 kilómetro", 1000 to "En 1 kilómetro", 950 to "En 1 kilómetro", 949 to "En 900 metros", 900 to "En 900 metros",
            500 to "En 500 metros", 480 to "En 500 metros", 449 to "En 400 metros", 300 to "En 300 metros", 287 to "En 300 metros", 250 to "En 300 metros",
            200 to "En 200 metros", 160 to "En 200 metros", 150 to "En 200 metros", 149 to "En 100 metros", 100 to "En 100 metros", 80 to "En 100 metros",
            75 to "En 100 metros", 74 to "En 50 metros", 50 to "En 50 metros", 35 to "En 50 metros", 34 to "En 30 metros", 25 to "En 30 metros", 20 to "En 20 metros", 19 to "Ahora",
            12 to "Ahora", 0 to "Ahora", 9500 to "En 9,5 kilómetros", 12400 to "En 12 kilómetros", 25600 to "En 26 kilómetros",
        )
        for ((meters, text) in cases) assertEquals(text, spoken(meters), "$meters m")
    }

    @Test fun metricDistancesInEnglish() {
        val cases = mapOf(
            2000 to "In 2 kilometers", 1500 to "In 1.5 kilometers", 1000 to "In 1 kilometer", 300 to "In 300 meters", 50 to "In 50 meters", 30 to "In 30 meters",
            10 to "Now", 12400 to "In 12 kilometers",
        )
        for ((meters, text) in cases) assertEquals(text, spoken(meters, lang = en), "$meters m")
    }

    @Test fun imperialDistances() {
        // metres -> spoken: 3218 m = 2 mi, 2400 m = 1.5 mi, 1609 m = 1 mi, 805 m = 0.5 mi, 400 m = 0.2 mi (1312 ft), 300 m = 984 ft -> 1000 ft is 0.2 mi
        val cases = mapOf(
            3218 to "In 2 miles", 2400 to "In 1.5 miles", 1609 to "In 1 mile", 1550 to "In 1 mile", 805 to "In 0.5 miles", 1100 to "In 0.7 miles", 400 to "In 0.2 miles",
            300 to "In 0.2 miles", 250 to "In 800 feet", 152 to "In 500 feet", 91 to "In 300 feet", 61 to "In 200 feet", 40 to "In 100 feet", 20 to "In 50 feet",
            12 to "Now", 16093 to "In 10 miles", 40000 to "In 25 miles",
        )
        for ((meters, text) in cases) assertEquals(text, spoken(meters, DistanceUnits.IMPERIAL, en), "$meters m")
    }

    @Test fun imperialDistancesInSpanish() {
        assertEquals("En 0,5 millas", spoken(805, DistanceUnits.IMPERIAL))
        assertEquals("En 1 milla", spoken(1609, DistanceUnits.IMPERIAL))
        assertEquals("En 2 millas", spoken(3218, DistanceUnits.IMPERIAL))
        assertEquals("En 500 pies", spoken(152, DistanceUnits.IMPERIAL))
    }

    @Test fun negativeAndHugeDistancesDoNotBreak() {
        assertEquals("Ahora", spoken(-5))
        assertEquals("En 500 kilómetros", spoken(500_000))
    }

    @Test fun nearPromptsUseTheDistanceToo() {
        assertEquals("En 100 metros, gira a la derecha", say(TurnType.RIGHT, AnnouncementKind.NEAR, 98))
        assertEquals("In 100 meters, turn right", say(TurnType.RIGHT, AnnouncementKind.NEAR, 98, lang = en))
    }

    @Test fun aNearPromptThatIsAlmostThereSaysNow() {
        assertEquals("Ahora, gira a la derecha", say(TurnType.RIGHT, AnnouncementKind.NEAR, 12))
        assertEquals("Now, turn right", say(TurnType.RIGHT, AnnouncementKind.FAR, 5, lang = en))
        assertEquals("Has llegado a tu destino", say(TurnType.ARRIVE, AnnouncementKind.NEAR, 8))
    }

    // ---- Lanes

    private fun lanes(vararg recommended: Boolean) = recommended.map { Lane(setOf(LaneDirection.THROUGH), it) }

    @Test fun laneAdviceInSpanish() {
        fun near(vararg l: Boolean) = say(TurnType.EXIT_RIGHT, AnnouncementKind.NEAR, 100, lanes = lanes(*l))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en el carril de la izquierda", near(true, false, false))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en el carril de la derecha", near(false, false, true))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en los dos carriles de la izquierda", near(true, true, false))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en los tres carriles de la derecha", near(false, true, true, true))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en el carril central", near(false, true, false))
        assertEquals("En 100 metros, toma la salida de la derecha. Mantente en el segundo carril por la izquierda", near(false, true, false, false))
    }

    @Test fun laneAdviceInEnglish() {
        fun near(vararg l: Boolean) = say(TurnType.LEFT, AnnouncementKind.NEAR, 100, lanes = lanes(*l), lang = en)
        assertEquals("In 100 meters, turn left. Keep in the left lane", near(true, false))
        assertEquals("In 100 meters, turn left. Keep in the right lane", near(false, true))
        assertEquals("In 100 meters, turn left. Keep in the left 2 lanes", near(true, true, false))
        assertEquals("In 100 meters, turn left. Keep in the middle lane", near(false, true, false))
    }

    @Test fun laneAdviceOnlyWhenItSaysSomething() {
        val plain = "En 100 metros, gira a la izquierda"
        fun near(l: List<Lane>) = say(TurnType.LEFT, AnnouncementKind.NEAR, 100, lanes = l)
        assertEquals(plain, near(emptyList()))
        assertEquals(plain, near(lanes(true)), "one lane: nothing to choose")
        assertEquals(plain, near(lanes(true, true, true)), "every lane is fine")
        assertEquals(plain, near(lanes(false, false)), "no lane is recommended")
        assertEquals(plain, near(lanes(true, false, true)), "not contiguous")
        assertEquals(plain, near(lanes(false, false, false, false, false, false, true, false)), "too far into the road to describe")
    }

    @Test fun laneAdviceIsNotGivenFarAwayOrAtTheLastMoment() {
        val l = lanes(true, false)
        assertEquals("En 500 metros, gira a la izquierda", say(TurnType.LEFT, AnnouncementKind.FAR, 500, lanes = l))
        assertEquals("Ahora, gira a la izquierda", say(TurnType.LEFT, AnnouncementKind.NOW, 10, lanes = l))
        assertEquals("En 100 metros, llegarás a tu destino", say(TurnType.ARRIVE, AnnouncementKind.NEAR, 100, lanes = l))
    }

    // ---- Settings resolution

    @Test fun voiceLanguageFollowsTheAppUnlessChosen() {
        val spain = java.util.Locale.forLanguageTag("es-ES")
        val french = java.util.Locale.FRANCE
        assertEquals(es, VoiceLanguagePref.AUTO.resolve(spain))
        assertEquals(en, VoiceLanguagePref.AUTO.resolve(java.util.Locale.US))
        assertEquals(VoiceLanguage.FR, VoiceLanguagePref.AUTO.resolve(french))
        assertEquals(en, VoiceLanguagePref.AUTO.resolve(java.util.Locale.forLanguageTag("nl-NL")), "languages without voice prompts fall back to English")
        assertEquals(en, VoiceLanguagePref.EN.resolve(spain))
        assertEquals(es, VoiceLanguagePref.ES.resolve(french))
    }

    @Test fun unitsFollowTheRegionUnlessChosen() {
        assertEquals(DistanceUnits.METRIC, UnitsPref.AUTO.resolve(java.util.Locale.forLanguageTag("es-ES")))
        assertEquals(DistanceUnits.METRIC, UnitsPref.AUTO.resolve(java.util.Locale.forLanguageTag("es")), "no region: metric")
        assertEquals(DistanceUnits.IMPERIAL, UnitsPref.AUTO.resolve(java.util.Locale.US))
        assertEquals(DistanceUnits.IMPERIAL, UnitsPref.AUTO.resolve(java.util.Locale.UK))
        assertEquals(DistanceUnits.IMPERIAL, UnitsPref.IMPERIAL.resolve(java.util.Locale.FRANCE))
        assertEquals(DistanceUnits.METRIC, UnitsPref.METRIC.resolve(java.util.Locale.US))
    }

    @Test fun testPhraseUsesTheChosenUnitsAndLanguage() {
        assertEquals("Voz de navegación activada. En 300 metros, gira a la izquierda", InstructionText.test(m, es))
        assertEquals("Navigation voice is on. In 0.2 miles, turn left", InstructionText.test(DistanceUnits.IMPERIAL, en))
    }

    // ---- Importance

    private fun ann(type: TurnType, kind: AnnouncementKind) = Announcement(Maneuver(0, type), kind, 100)

    @Test fun onlyImportantPromptsKeepTheTurnsThatMatter() {
        for (t in listOf(TurnType.LEFT, TurnType.RIGHT, TurnType.SHARP_LEFT, TurnType.SHARP_RIGHT, TurnType.U_TURN_LEFT, TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT, TurnType.ROUNDABOUT_ENTER, TurnType.EXIT_LEFT, TurnType.EXIT_RIGHT)) {
            assertTrue(ann(t, AnnouncementKind.NEAR).isImportant(), "$t near")
            assertTrue(ann(t, AnnouncementKind.NOW).isImportant(), "$t now")
        }
        for (t in listOf(TurnType.STRAIGHT, TurnType.SLIGHT_LEFT, TurnType.SLIGHT_RIGHT, TurnType.MERGE, TurnType.ROUNDABOUT_LEAVE, TurnType.DEPART)) {
            for (k in AnnouncementKind.entries) assertFalse(ann(t, k).isImportant(), "$t $k")
        }
        assertFalse(ann(TurnType.LEFT, AnnouncementKind.FAR).isImportant(), "far warnings are dropped...")
        assertTrue(ann(TurnType.EXIT_RIGHT, AnnouncementKind.FAR).isImportant(), "...except exits")
        assertTrue(ann(TurnType.ROUNDABOUT_ENTER, AnnouncementKind.FAR).isImportant(), "...and roundabouts")
    }

    @Test fun settingsAreNormalizedAndMapToRouteOptions() {
        assertEquals(10, NavSettings(volumePercent = -5).normalized().volumePercent)
        assertEquals(100, NavSettings(volumePercent = 500).normalized().volumePercent)
        val o = NavSettings(avoidTolls = true, avoidUnpaved = true).routeOptions()
        assertTrue(o.avoidTolls && o.avoidUnpaved && !o.avoidMotorways && !o.avoidFerries)
        val d = NavSettings()
        assertTrue(d.voiceEnabled && !d.importantOnly && d.volumePercent == 100 && d.units == UnitsPref.AUTO && d.voiceLanguage == VoiceLanguagePref.AUTO)
        assertFalse(d.avoidMotorways || d.avoidTolls || d.avoidFerries || d.avoidUnpaved)
    }
}
