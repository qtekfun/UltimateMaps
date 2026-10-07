package com.qtekfun.ultimatemaps.core.search

import java.time.DayOfWeek
import java.time.LocalDateTime

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
 * Anything else (public or school holidays, months, weeks, dates, `sunrise`/`sunset`, `+`, comments, wrapped
 * conditions, ...) makes the whole text [OpenState.UNKNOWN]: it is never guessed. Days not named by any rule are closed,
 * as in the specification. The time is passed in (local time of the place, which the app takes to be the phone's) so the
 * evaluation is deterministic in tests.
 */
object OpeningHours {
    /** Opening spans per weekday (0 = Monday), as minute ranges from midnight; an end above 1440 spills into the next day. */
    class Schedule internal constructor(private val days: List<List<IntRange>>) {
        fun stateAt(at: LocalDateTime): OpenState {
            val today = at.dayOfWeek.index()
            val yesterday = (today + 6) % 7
            val minute = at.hour * 60 + at.minute
            if (days[today].any { minute >= it.first && minute < it.last }) return OpenState.OPEN
            if (days[yesterday].any { it.last > DAY && minute + DAY >= it.first && minute + DAY < it.last }) return OpenState.OPEN
            return OpenState.CLOSED
        }
    }

    /** Null when [text] is empty or not covered by the supported subset. */
    fun parse(text: String?): Schedule? {
        val t = text?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.equals("24/7", ignoreCase = true)) return Schedule(List(7) { listOf(0..DAY) })
        val days = MutableList(7) { emptyList<IntRange>() }
        var any = false
        for (rawRule in t.split(';')) {
            val rule = rawRule.trim()
            if (rule.isEmpty()) continue
            val perDay = MutableList(7) { mutableListOf<IntRange>() }
            val touched = BooleanArray(7)
            for (sub in rule.split(ADDITIONAL)) {
                val parsed = parseSubRule(sub.trim()) ?: return null
                for (d in parsed.days) {
                    touched[d] = true
                    perDay[d] += parsed.spans
                }
            }
            for (d in 0 until 7) if (touched[d]) days[d] = perDay[d].toList()
            any = true
        }
        return if (any) Schedule(days) else null
    }

    /** [OpenState.UNKNOWN] when the text is absent or unsupported. */
    fun stateAt(text: String?, at: LocalDateTime): OpenState = parse(text)?.stateAt(at) ?: OpenState.UNKNOWN

    private class SubRule(val days: List<Int>, val spans: List<IntRange>)

    private fun parseSubRule(s: String): SubRule? {
        if (s.isEmpty()) return null
        val dayMatch = DAY_SPEC.matchAt(s, 0)
        val days = if (dayMatch != null) expandDays(dayMatch.value) ?: return null else (0 until 7).toList()
        val rest = (if (dayMatch != null) s.substring(dayMatch.value.length) else s).trim()
        if (rest.equals("off", ignoreCase = true) || rest.equals("closed", ignoreCase = true)) return SubRule(days, emptyList())
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
        return SubRule(days, spans)
    }

    private fun toMinutes(h: String, m: String, isEnd: Boolean): Int? {
        val hours = h.toInt()
        val minutes = m.toInt()
        if (minutes > 59) return null
        val limit = if (isEnd) 48 else 24
        if (hours > limit || (hours == limit && minutes > 0) || (!isEnd && hours == 24)) return null
        return hours * 60 + minutes
    }

    private fun expandDays(spec: String): List<Int>? {
        val out = sortedSetOf<Int>()
        for (item in spec.split(',')) {
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
        return out.toList()
    }

    private fun dayIndex(s: String): Int? = NAMES.indexOf(s.lowercase()).takeIf { it >= 0 }

    private fun DayOfWeek.index(): Int = ordinal // MONDAY is 0

    private const val DAY = 24 * 60
    private val NAMES = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private const val D = "(?:Mo|Tu|We|Th|Fr|Sa|Su)"
    private val DAY_SPEC = Regex("$D(?:\\s*-\\s*$D)?(?:\\s*,\\s*$D(?:\\s*-\\s*$D)?)*(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val SPAN = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")

    /** A comma after a time that is followed by a letter starts an additional rule ("08:00-12:00, Sa 09:00-12:00"). */
    private val ADDITIONAL = Regex("(?<=\\d)\\s*,\\s*(?=[A-Za-z])")
}
