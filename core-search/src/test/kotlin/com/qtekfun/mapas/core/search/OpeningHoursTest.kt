package com.qtekfun.mapas.core.search

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpeningHoursTest {
    // 2026-10-05 is a Monday (fixed dates: nothing here reads the clock).
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 4 + day, hour, minute) // day 1 = Monday

    private fun state(text: String?, day: Int, hour: Int, minute: Int = 0) = OpeningHours.stateAt(text, at(day, hour, minute))

    @Test
    fun fixtureDatesAreTheExpectedWeekdays() {
        assertEquals(java.time.DayOfWeek.MONDAY, at(1, 0).dayOfWeek)
        assertEquals(java.time.DayOfWeek.SUNDAY, at(7, 0).dayOfWeek)
    }

    @Test
    fun alwaysOpen() {
        for (d in 1..7) assertEquals(OpenState.OPEN, state("24/7", d, 3, 30))
    }

    @Test
    fun weekdayRangeAndOtherDaysClosed() {
        val h = "Mo-Fr 08:00-20:00"
        assertEquals(OpenState.OPEN, state(h, 1, 8, 0))
        assertEquals(OpenState.OPEN, state(h, 5, 19, 59))
        assertEquals(OpenState.CLOSED, state(h, 1, 7, 59))
        assertEquals(OpenState.CLOSED, state(h, 1, 20, 0)) // the end is exclusive
        assertEquals(OpenState.CLOSED, state(h, 6, 12)) // Saturday is not named: closed
        assertEquals(OpenState.CLOSED, state(h, 7, 12))
    }

    @Test
    fun laterRulesReplaceEarlierOnesForTheirDays() {
        val h = "Mo-Su 09:00-21:00; Su off; Sa 10:00-14:00"
        assertEquals(OpenState.OPEN, state(h, 3, 20))
        assertEquals(OpenState.CLOSED, state(h, 7, 12))
        assertEquals(OpenState.OPEN, state(h, 6, 11))
        assertEquals(OpenState.CLOSED, state(h, 6, 15))
    }

    @Test
    fun severalSpansAndDayLists() {
        val h = "Mo,We,Fr 08:00-14:00,17:00-21:00"
        assertEquals(OpenState.OPEN, state(h, 1, 9))
        assertEquals(OpenState.CLOSED, state(h, 1, 15))
        assertEquals(OpenState.OPEN, state(h, 5, 18))
        assertEquals(OpenState.CLOSED, state(h, 2, 9)) // Tuesday not listed
    }

    @Test
    fun additionalRuleAfterAComma() {
        val h = "Mo-Fr 09:00-18:00, Sa 09:00-13:00"
        assertEquals(OpenState.OPEN, state(h, 6, 12))
        assertEquals(OpenState.CLOSED, state(h, 6, 14))
        assertEquals(OpenState.OPEN, state(h, 2, 17))
        assertEquals(OpenState.CLOSED, state(h, 7, 10))
    }

    @Test
    fun spansPastMidnightSpillIntoTheNextDay() {
        val h = "Fr,Sa 22:00-02:00"
        assertEquals(OpenState.OPEN, state(h, 5, 23)) // Friday night
        assertEquals(OpenState.OPEN, state(h, 6, 1, 30)) // after midnight, still Friday's opening
        assertEquals(OpenState.CLOSED, state(h, 6, 2, 0))
        assertEquals(OpenState.OPEN, state(h, 7, 0, 30)) // Saturday's spill into Sunday
        assertEquals(OpenState.CLOSED, state(h, 5, 1, 30)) // Thursday night is not covered
        assertEquals(OpenState.OPEN, state("Mo-Su 18:00-26:00", 3, 1)) // 26:00 notation
    }

    @Test
    fun midnightEndAndWrappingDayRange() {
        assertEquals(OpenState.OPEN, state("Mo-Su 00:00-24:00", 4, 23, 59))
        val h = "Sa-Mo 10:00-12:00"
        assertEquals(OpenState.OPEN, state(h, 7, 11))
        assertEquals(OpenState.OPEN, state(h, 1, 11))
        assertEquals(OpenState.CLOSED, state(h, 3, 11))
    }

    @Test
    fun offAndClosedAreClosed() {
        assertEquals(OpenState.CLOSED, state("Mo-Su off", 2, 12))
        assertEquals(OpenState.CLOSED, state("Mo-Fr 08:00-20:00; We closed", 3, 12))
    }

    @Test
    fun caseSpacingAndQuotes() {
        assertEquals(OpenState.OPEN, state("mo-fr 8:00 - 20:00", 2, 9))
        assertEquals(OpenState.OPEN, state("  \"Mo-Fr 08:00-20:00;\"  ", 2, 9))
        assertEquals(OpenState.OPEN, state("08:00-20:00", 7, 9)) // no day part: every day
    }

    @Test
    fun unsupportedTextIsUnknownNeverGuessed() {
        for (h in listOf(
            "Mo-Fr 08:00-20:00; PH off",
            "Mo-Fr sunrise-sunset",
            "Jan-Mar Mo-Fr 08:00-20:00",
            "Mo-Fr 08:00+",
            "Mo-Fr",
            "Mo-Fr 08:00-20:00 \"by appointment\"",
            "Mon-Fri 08:00-20:00",
            "Mo[1] 08:00-20:00",
            "week 1-10 Mo 08:00-20:00",
            "by appointment",
            "Mo-Fr 25:00-26:00",
            "Mo-Fr 08:60-20:00",
            "Mo-Fr 08:00-08:00",
            "",
            "   ",
        )) {
            assertNull(OpeningHours.parse(h), "should be unsupported: $h")
            assertEquals(OpenState.UNKNOWN, state(h, 2, 12), h)
        }
        assertEquals(OpenState.UNKNOWN, state(null, 2, 12))
        assertNotNull(OpeningHours.parse("Mo-Fr 08:00-20:00"))
    }
}
