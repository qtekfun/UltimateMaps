package com.qtekfun.ultimatemaps.core.transit

/** What [projectCalendar] did to a feed. */
class CalendarProjection(
    /** Last day of the calendar before the projection (epoch day). */
    val originalLastDay: Int,
    /** Last day of the calendar after the projection (epoch day). */
    val newLastDay: Int,
    /** Services whose end date was extended. */
    val extendedServices: Int,
    /** Past `calendar_dates` exceptions that were dropped. */
    val droppedExceptions: Int,
)

/** Services whose calendar ends more than this many days before the feed's last day are special ones (not projected). */
private const val NORMAL_SERVICE_SLACK_DAYS = 14

/**
 * Projects the calendar of an EXPIRED [feed] forward so that it covers every day up to [horizonDay] (epoch days),
 * for a feed that is no longer published but whose weekly pattern is regular (the Madrid Metro). Does nothing, and
 * returns null, when the feed's calendar has not ended before [today] (a valid feed is never touched) or declares no
 * bounded calendar; so calling it twice changes nothing the second time.
 *
 * - The "normal" services (those whose end date is within [NORMAL_SERVICE_SLACK_DAYS] of the feed's last day) get that
 *   end date extended to [horizonDay]; their weekday pattern is kept. A service that ended long before the others
 *   (a special timetable) stays as it was and so stays expired.
 * - `calendar_dates` exceptions before [today] are dropped: last year's holidays do not repeat on the same dates, so
 *   national holidays then run like ordinary days of the week.
 *
 * Mutates `feed.services` (and `feed.feedEndDay` when `feed_info` bounded it) in place.
 */
fun projectCalendar(feed: GtfsFeed, today: Int, horizonDay: Int): CalendarProjection? {
    val window = calendarWindow(feed) ?: return null
    if (window.last >= today) return null
    var last = Int.MIN_VALUE
    for (s in feed.services) if (s.mask != 0 && s.endDay != Int.MAX_VALUE) last = maxOf(last, s.endDay)
    if (last == Int.MIN_VALUE) return null
    var extended = 0
    var dropped = 0
    for (i in feed.services.indices) {
        val s = feed.services[i]
        val normal = s.mask != 0 && s.endDay != Int.MAX_VALUE && s.endDay >= last - NORMAL_SERVICE_SLACK_DAYS
        val added = s.added.filter { it >= today }.toIntArray()
        val removed = s.removed.filter { it >= today }.toIntArray()
        dropped += (s.added.size - added.size) + (s.removed.size - removed.size)
        feed.services[i] = ServiceDef(s.mask, s.startDay, if (normal) maxOf(s.endDay, horizonDay) else s.endDay, added, removed)
        if (normal) extended++
    }
    if (feed.feedEndDay != Int.MAX_VALUE) feed.feedEndDay = maxOf(feed.feedEndDay, horizonDay)
    return CalendarProjection(window.last, horizonDay, extended, dropped)
}
