package com.qtekfun.mapas.places

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListStyleTest {
    private val fx = PersonalFixture()

    @After fun tearDown() = fx.close()

    @Test fun emojiKeepsOnlyTheFirstSymbol() {
        assertEquals("🚗", ListStyle.normalizeEmoji("🚗🚕"))
        assertEquals("🏖️", ListStyle.normalizeEmoji("  🏖️ "))
        assertEquals("★", ListStyle.normalizeEmoji("★"))
        assertNull(ListStyle.normalizeEmoji(""))
        assertNull(ListStyle.normalizeEmoji("   "))
        assertNull(ListStyle.normalizeEmoji(null))
    }

    @Test fun lettersAndDigitsAreNotEmoji() {
        assertNull(ListStyle.normalizeEmoji("a"))
        assertNull(ListStyle.normalizeEmoji("7"))
        assertNull(ListStyle.normalizeEmoji("star")) // symbolic names written by older versions
        assertNull(ListStyle.displayEmoji("home"))
        assertEquals("🏠", ListStyle.displayEmoji("🏠"))
    }

    @Test fun notesAndNamesAreTrimmedAndCapped() {
        assertNull(ListStyle.normalizeNotes("   "))
        assertEquals("hola", ListStyle.normalizeNotes("  hola "))
        assertEquals(ListStyle.MAX_NOTES, ListStyle.normalizeNotes("x".repeat(ListStyle.MAX_NOTES + 50))!!.length)
        assertEquals("Viaje", ListStyle.normalizeName(" Viaje "))
        assertNull(ListStyle.normalizeName(""))
    }

    @Test fun thePaletteHasDistinctOpaqueColors() {
        assertEquals(ListStyle.PALETTE.size, ListStyle.PALETTE.toSet().size)
        assertTrue(ListStyle.PALETTE.all { (it ushr 24) == 0xFF })
    }

    @Test fun customiseListStoresEmojiColorAndNotes() {
        val id = fx.service.createList("Viaje")!!
        assertTrue(fx.service.customiseList(id, " Verano ", "🏖️🌊", ListStyle.PALETTE[3], "  playas  "))
        val list = fx.service.lists().single { it.id == id }
        assertEquals("Verano", list.name)
        assertEquals("🏖️", list.icon)
        assertEquals(ListStyle.PALETTE[3], list.color)
        assertEquals("playas", list.notes)
    }

    @Test fun customiseListKeepsTheNameWhenBlankAndClearsWhatIsEmpty() {
        val id = fx.service.createList("Viaje")!!
        fx.service.customiseList(id, "Viaje", "🏖️", ListStyle.PALETTE[0], "n")
        assertTrue(fx.service.customiseList(id, "  ", "", null, ""))
        val list = fx.service.lists().single { it.id == id }
        assertEquals("Viaje", list.name)
        assertNull(list.icon); assertNull(list.color); assertNull(list.notes)
    }

    @Test fun customiseListOfAMissingListIsFalse() {
        assertFalse(fx.service.customiseList(9999, "x", null, null, null))
    }

    @Test fun listTitleShowsTheEmojiOnlyWhenThereIsOne() {
        assertEquals("🏖️ Viaje", listTitle("Viaje", "🏖️"))
        assertEquals("Viaje", listTitle("Viaje", null))
        assertEquals("Viaje", listTitle("Viaje", "star"))
    }
}
