package com.qtekfun.ultimatemaps.transit

import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Text of the transit UI that does not depend on resources. 24-hour times: that is how the schedules are written. */
object TransitFormat {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun time(epochSeconds: Long, zone: ZoneId): String = clockFormat.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    fun time(local: java.time.LocalDateTime): String = clockFormat.format(local)

    fun date(date: LocalDate, locale: Locale): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /** Whole minutes of the trip, at least one. */
    fun minutes(itinerary: Itinerary): Int = ((itinerary.durationSec + 30) / 60).toInt().coerceAtLeast(1)

    /** Walking minutes of one leg, at least one. */
    fun walkMinutes(leg: ItineraryLeg.Walk): Int = ((leg.arriveAt - leg.departAt + 30) / 60).toInt().coerceAtLeast(1)

    private val url = Regex("https?://[^\\s)]+")

    /** Web addresses found in an attribution line, trailing punctuation removed. */
    fun links(attribution: String): List<String> = url.findAll(attribution).map { it.value.trimEnd('.', ',', ';') }.toList()
}
