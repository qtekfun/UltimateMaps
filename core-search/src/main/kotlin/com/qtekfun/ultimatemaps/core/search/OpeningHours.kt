package com.qtekfun.ultimatemaps.core.search

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Whether a place is open at a moment; [UNKNOWN] when the text uses anything the small parser does not cover. */
enum class OpenState { OPEN, CLOSED, UNKNOWN }

/**
 * A small parser for the common, simple patterns of the OSM `opening_hours` tag; it is NOT the full specification
 * (https://wiki.openstreetmap.org/wiki/Key:opening_hours). Supported:
 *
 * - `24/7`
 * - rules separated by `;` such as `Mo-Fr 08:00-20:00; Sa 09:00-14:00; Su off`, where a later rule replaces the
 *   earlier ones for the days it names
 * - day lists and ranges (`Mo,We,Fr`, `Mo-Fr`, `Sa-Mo`), several time spans (`08:00-14:00,17:00-21:00`), spans that
 *   pass midnight (`22:00-02:00`, `22:00-26:00`), `24:00`, no day part (every day), `off` / `closed`, and the
 *   additional rules after a comma (`Mo-Fr 09:00-18:00, Sa 09:00-13:00`)
 *
 * Public and school holiday rules (`PH off`, `Su,PH off`, `PH 10:00-14:00`) are IGNORED, not understood: the days are
 * judged by their weekday only and [Schedule.holidaysIgnored] tells the UI to say so. We have no holiday calendar (the
 * region, the year and the local fiestas all matter), and guessing "open" is less bad than showing nothing for the many
 * places that only add `PH off`.
 *
 * Anything else (months, weeks, dates, `sunrise`/`sunset`, `+`, comments, wrapped
 * conditions, ...) makes the whole text [OpenState.UNKNOWN]: it is never guessed. Days not named by any rule are closed,
 * as in the specification. The time is passed in (local time of the place, which the app takes to be the phone's) so the
 * evaluation is deterministic in tests.
 */
object OpeningHours {
    /** Opening spans per weekday (0 = Monday), as minute ranges from midnight; an end above 1440 spills into the next day. */
    class Schedule internal constructor(private val days: List<List<IntRange>>, val holidaysIgnored: Boolean = false) {
        fun stateAt(at: LocalDateTime): OpenState {
            val today = at.dayOfWeek.index()
            val yesterday = (today + 6) % 7
            val minute = at.hour * 60 + at.minute
            if (days[today].any { minute >= it.first && minute < it.last }) return OpenState.OPEN
            if (days[yesterday].any { it.last > DAY && minute + DAY >= it.first && minute + DAY < it.last }) return OpenState.OPEN
            return OpenState.CLOSED
        }

        /**
         * The state at [at] and the next moment it changes within [HORIZON_DAYS] days (null when it never changes in that
         * window: always open, or never open). Spans that touch (`Mo 08:00-24:00` then `Tu 00:00-06:00`) are one span.
         */
        fun statusAt(at: LocalDateTime): OpeningStatus {
            val today = at.dayOfWeek.index()
            val now = at.hour * 60 + at.minute
            val spans = ArrayList<IntRange>()
            for (k in -1..HORIZON_DAYS) {
                for (r in days[((today + k) % 7 + 7) % 7]) spans += (k * DAY + r.first)..(k * DAY + r.last)
            }
            spans.sortBy { it.first }
            val merged = ArrayList<IntRange>()
            for (r in spans) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last) {
                    if (r.last > last.last) merged[merged.lastIndex] = last.first..r.last
                } else {
                    merged += r
                }
            }
            val open = merged.firstOrNull { now >= it.first && now < it.last }
            val midnight = at.toLocalDate().atStartOfDay()
            if (open != null) {
                val change = if (open.last >= (HORIZON_DAYS + 1) * DAY) null else midnight.plusMinutes(open.last.toLong())
                return OpeningStatus(OpenState.OPEN, change, holidaysIgnored)
            }
            val next = merged.firstOrNull { it.first > now }
            return OpeningStatus(OpenState.CLOSED, next?.let { midnight.plusMinutes(it.first.toLong()) }, holidaysIgnored)
        }
    }

    /** Result of [Schedule.statusAt]; [changeAt] is when it closes (if open) or opens (if closed). */
    data class OpeningStatus(val state: OpenState, val changeAt: LocalDateTime?, val holidaysIgnored: Boolean)

    /** What the place card says; the UI maps each kind to a translated sentence. [minutes] is the minute of the day of the change. */
    data class Summary(val kind: Kind, val minutes: Int = 0, val day: DayOfWeek? = null, val holidaysIgnored: Boolean = false) {
        enum class Kind { UNKNOWN, OPEN_ALWAYS, CLOSES_AT, CLOSES_ON, CLOSED_ALWAYS, OPENS_AT, OPENS_TOMORROW, OPENS_ON }
    }

    /** "Open now" / "Closes at 20:00" / "Opens tomorrow at 09:00" / "Closed" / unknown, for [text] at the local time [at]. */
    fun summary(text: String?, at: LocalDateTime): Summary {
        val schedule = parse(text) ?: return Summary(Summary.Kind.UNKNOWN)
        val st = schedule.statusAt(at)
        val change = st.changeAt
        val h = st.holidaysIgnored
        if (change == null) {
            return Summary(if (st.state == OpenState.OPEN) Summary.Kind.OPEN_ALWAYS else Summary.Kind.CLOSED_ALWAYS, holidaysIgnored = h)
        }
        val offset = ChronoUnit.DAYS.between(at.toLocalDate(), change.toLocalDate()).toInt()
        val minutes = change.hour * 60 + change.minute
        val kind = if (st.state == OpenState.OPEN) {
            // Tonight's closing after midnight ("until 02:00") still reads as today's.
            if (offset == 0 || (offset == 1 && minutes <= LATE_CLOSE)) Summary.Kind.CLOSES_AT else Summary.Kind.CLOSES_ON
        } else {
            when (offset) {
                0 -> Summary.Kind.OPENS_AT
                1 -> Summary.Kind.OPENS_TOMORROW
                else -> Summary.Kind.OPENS_ON
            }
        }
        val named = kind == Summary.Kind.CLOSES_ON || kind == Summary.Kind.OPENS_ON
        return Summary(kind, minutes, if (named) change.dayOfWeek else null, h)
    }

    /** Null when [text] is empty or not covered by the supported subset. */
    fun parse(text: String?): Schedule? {
        val t = text?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.equals("24/7", ignoreCase = true)) return Schedule(List(7) { listOf(0..DAY) })
        val days = MutableList(7) { emptyList<IntRange>() }
        var any = false
        var holidays = false
        for (rawRule in t.split(';')) {
            val rule = rawRule.trim()
            if (rule.isEmpty()) continue
            val perDay = MutableList(7) { mutableListOf<IntRange>() }
            val touched = BooleanArray(7)
            for (sub in rule.split(ADDITIONAL)) {
                val parsed = parseSubRule(sub.trim()) ?: return null
                if (parsed.holidays) holidays = true
                for (d in parsed.days) {
                    touched[d] = true
                    perDay[d] += parsed.spans
                }
            }
            for (d in 0 until 7) if (touched[d]) days[d] = perDay[d].toList()
            if (touched.any { it }) any = true
        }
        return if (any) Schedule(days, holidays) else null
    }

    /** [OpenState.UNKNOWN] when the text is absent or unsupported. */
    fun stateAt(text: String?, at: LocalDateTime): OpenState = parse(text)?.stateAt(at) ?: OpenState.UNKNOWN

    private class SubRule(val days: List<Int>, val spans: List<IntRange>, val holidays: Boolean = false)

    private fun parseSubRule(s: String): SubRule? {
        if (s.isEmpty()) return null
        val dayMatch = DAY_SPEC.matchAt(s, 0)
        val expanded = if (dayMatch != null) expandDays(dayMatch.value) ?: return null else Expanded((0 until 7).toList(), false)
        val days = expanded.days
        val rest = (if (dayMatch != null) s.substring(dayMatch.value.length) else s).trim()
        if (rest.equals("off", ignoreCase = true) || rest.equals("closed", ignoreCase = true)) return SubRule(days, emptyList(), expanded.holidays)
        if (rest.isEmpty()) return null // "Mo-Fr" alone: not covered
        val spans = mutableListOf<IntRange>()
        for (part in rest.split(',')) {
            val m = SPAN.matchEntire(part.trim()) ?: return null
            val start = toMinutes(m.groupValues[1], m.groupValues[2], isEnd = false) ?: return null
            var end = toMinutes(m.groupValues[3], m.groupValues[4], isEnd = true) ?: return null
            if (end == start) return null
            if (end < start) end += DAY // passes midnight
            spans += start..end
        }
        return SubRule(days, spans, expanded.holidays)
    }

    private fun toMinutes(h: String, m: String, isEnd: Boolean): Int? {
        val hours = h.toInt()
        val minutes = m.toInt()
        if (minutes > 59) return null
        val limit = if (isEnd) 48 else 24
        if (hours > limit || (hours == limit && minutes > 0) || (!isEnd && hours == 24)) return null
        return hours * 60 + minutes
    }

    private class Expanded(val days: List<Int>, val holidays: Boolean)

    /** The weekdays of a day part; `PH` and `SH` items are dropped and reported through [Expanded.holidays]. */
    private fun expandDays(spec: String): Expanded? {
        val out = sortedSetOf<Int>()
        var holidays = false
        for (item in spec.split(',')) {
            if (item.trim().equals("PH", true) || item.trim().equals("SH", true)) { holidays = true; continue }
            val ends = item.trim().split('-').map { dayIndex(it.trim()) ?: return null }
            when (ends.size) {
                1 -> out += ends[0]
                2 -> {
                    var d = ends[0]
                    out += d
                    while (d != ends[1]) { d = (d + 1) % 7; out += d }
                }
                else -> return null
            }
        }
        return Expanded(out.toList(), holidays)
    }

    private fun dayIndex(s: String): Int? = NAMES.indexOf(s.lowercase()).takeIf { it >= 0 }

    private fun DayOfWeek.index(): Int = ordinal // MONDAY is 0

    private const val DAY = 24 * 60
    private const val HORIZON_DAYS = 8
    private const val LATE_CLOSE = 6 * 60
    private val NAMES = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private const val D = "(?:Mo|Tu|We|Th|Fr|Sa|Su)"
    private const val ITEM = "(?:$D(?:\\s*-\\s*$D)?|PH|SH)"
    private val DAY_SPEC = Regex("$ITEM(?:\\s*,\\s*$ITEM)*(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val SPAN = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")

    /** A comma after a time that is followed by a letter starts an additional rule ("08:00-12:00, Sa 09:00-12:00"). */
    private val ADDITIONAL = Regex("(?<=\\d)\\s*,\\s*(?=[A-Za-z])")
}
