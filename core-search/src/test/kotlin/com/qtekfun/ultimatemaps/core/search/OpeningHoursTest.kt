package com.qtekfun.ultimatemaps.core.search

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private typealias K = OpeningHours.Summary.Kind

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

    private fun summary(text: String?, day: Int, hour: Int, minute: Int = 0) = OpeningHours.summary(text, at(day, hour, minute))

    @Test
    fun holidayRulesAreIgnoredAndFlagged() {
        for (h in listOf("Mo-Fr 08:00-20:00; PH off", "Mo-Fr 08:00-20:00; Su,PH off", "Mo-Fr 08:00-20:00, PH off", "Mo-Fr 08:00-20:00; SH off; PH 10:00-12:00")) {
            val schedule = assertNotNull(OpeningHours.parse(h), h)
            assertEquals(true, schedule.holidaysIgnored, h)
            assertEquals(OpenState.OPEN, state(h, 2, 12), h)
            assertEquals(OpenState.CLOSED, state(h, 2, 21), h)
        }
        // "Su,PH off": Sunday is still closed because only PH is dropped from the list.
        assertEquals(OpenState.CLOSED, state("Mo-Sa 09:00-14:00; Su,PH off", 7, 10))
        assertEquals(false, OpeningHours.parse("Mo-Fr 08:00-20:00")!!.holidaysIgnored)
        assertNull(OpeningHours.parse("PH off")) // nothing but holidays: nothing to judge
    }

    @Test
    fun commonRealWorldStrings() {
        val shop = "Mo-Fr 09:00-18:00; Sa 10:00-14:00"
        assertEquals(OpenState.OPEN, state(shop, 6, 13, 59))
        assertEquals(OpenState.CLOSED, state(shop, 6, 14))
        assertEquals(OpenState.CLOSED, state(shop, 7, 11))
        val split = "Mo-Sa 09:00-14:00,17:00-20:00"
        assertEquals(OpenState.CLOSED, state(split, 3, 15))
        assertEquals(OpenState.OPEN, state(split, 3, 17))
        assertEquals(OpenState.CLOSED, state(split, 7, 10))
        assertEquals(OpenState.OPEN, state("Mo-Su 08:00-24:00", 7, 23, 59))
        assertEquals(OpenState.CLOSED, state("Mo-Su 08:00-24:00", 7, 7, 59))
        val bar = "Fr-Sa 22:00-04:00; Su-Th 18:00-02:00"
        assertEquals(OpenState.OPEN, state(bar, 6, 3)) // Saturday 03:00 belongs to Friday night
        assertEquals(OpenState.CLOSED, state(bar, 6, 5))
        assertEquals(OpenState.OPEN, state("Fr-Mo 10:00-12:00", 7, 11)) // wrapping day range, Sunday
        assertEquals(OpenState.CLOSED, state("Fr-Mo 10:00-12:00", 3, 11))
        assertEquals(OpenState.OPEN, state("22:00-02:00", 2, 1)) // no day part: every day, past midnight
    }

    @Test
    fun summaryWhenOpen() {
        val shop = "Mo-Fr 09:00-20:00"
        assertEquals(OpeningHours.Summary(K.CLOSES_AT, 20 * 60), summary(shop, 2, 11))
        assertEquals(OpeningHours.Summary(K.OPEN_ALWAYS), summary("24/7", 2, 11))
        assertEquals(OpeningHours.Summary(K.CLOSES_AT, 0), summary("Mo-Su 08:00-24:00", 2, 11)) // midnight today
        // Overnight: closes tonight after midnight, and the same span seen after midnight.
        assertEquals(OpeningHours.Summary(K.CLOSES_AT, 2 * 60), summary("22:00-02:00", 2, 23))
        assertEquals(OpeningHours.Summary(K.CLOSES_AT, 2 * 60), summary("22:00-02:00", 3, 1))
        // A closing hours away tomorrow names the day.
        assertEquals(OpeningHours.Summary(K.CLOSES_ON, 18 * 60, java.time.DayOfWeek.TUESDAY), summary("Mo 22:00-24:00; Tu 00:00-18:00", 1, 23))
    }

    @Test
    fun summaryWhenClosed() {
        val shop = "Mo-Fr 09:00-18:00; Sa 10:00-14:00"
        assertEquals(OpeningHours.Summary(K.OPENS_AT, 9 * 60), summary(shop, 2, 7))
        assertEquals(OpeningHours.Summary(K.OPENS_TOMORROW, 9 * 60), summary(shop, 2, 19))
        assertEquals(OpeningHours.Summary(K.OPENS_TOMORROW, 10 * 60), summary(shop, 5, 19)) // Friday evening -> Saturday
        assertEquals(OpeningHours.Summary(K.OPENS_ON, 9 * 60, java.time.DayOfWeek.MONDAY), summary(shop, 6, 15)) // Saturday 15:00
        assertEquals(OpeningHours.Summary(K.OPENS_TOMORROW, 9 * 60), summary(shop, 7, 12)) // Sunday: tomorrow is Monday
        assertEquals(OpeningHours.Summary(K.CLOSED_ALWAYS), summary("Mo-Su off", 2, 12))
        assertEquals(OpeningHours.Summary(K.UNKNOWN), summary("by appointment", 2, 12))
        assertEquals(OpeningHours.Summary(K.UNKNOWN), summary(null, 2, 12))
    }

    @Test
    fun summaryCarriesTheHolidayFlag() {
        assertEquals(true, summary("Mo-Fr 09:00-20:00; PH off", 2, 11).holidaysIgnored)
        assertEquals(false, summary("Mo-Fr 09:00-20:00", 2, 11).holidaysIgnored)
    }

    @Test
    fun weekWrapLooksAtNextWeek() {
        // Closed on Sunday evening, next opening is Monday morning (a window that crosses the Sunday/Monday edge).
        assertEquals(OpeningHours.Summary(K.OPENS_TOMORROW, 8 * 60), summary("Mo 08:00-12:00", 7, 20))
        // Only one day a week: from Tuesday 12:00 the next opening is six days away.
        assertEquals(OpeningHours.Summary(K.OPENS_ON, 8 * 60, java.time.DayOfWeek.MONDAY), summary("Mo 08:00-12:00", 2, 12))
    }
}
