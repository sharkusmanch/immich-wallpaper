package dev.immichwall.schedule

import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeParseException

/**
 * Date → cycle. A Kotlin port of immich-kiosk-scheduler's `internal/scheduler`: entries are
 * evaluated in order, the first match wins, ranges are inclusive and may wrap the year, and
 * the default applies when nothing matches. Pure: no clock, no Android.
 */
object ScheduleResolver {

    /** Parses strict zero-padded `MM-DD`; null for anything else (including `02-30`). */
    fun parseMonthDay(s: String): MonthDay? =
        try {
            MonthDay.parse("--$s")
        } catch (_: DateTimeParseException) {
            null
        }

    /** True when [date] falls inside the entry's range. Unparseable bounds never match. */
    fun matches(entry: ScheduleEntry, date: LocalDate): Boolean {
        val start = parseMonthDay(entry.start) ?: return false
        val end = parseMonthDay(entry.end) ?: return false
        val day = MonthDay.from(date)
        return if (end < start) day >= start || day <= end else day >= start && day <= end
    }

    /**
     * The schedule's answer for [date], ignoring [Schedule.enabled] (callers gate on that).
     * Entries whose cycle is not in [knownCycleIds] (a deleted cycle) are skipped. Null
     * when nothing matches and the default cycle is unset or unknown.
     */
    fun resolve(schedule: Schedule, date: LocalDate, knownCycleIds: Set<String>): Resolution? {
        for (entry in schedule.entries) {
            if (entry.cycleId in knownCycleIds && matches(entry, date)) {
                return Resolution(entry.cycleId, entry.id)
            }
        }
        return if (schedule.defaultCycleId in knownCycleIds) Resolution(schedule.defaultCycleId, null) else null
    }

    /** The first date after [from] whose answer differs from [from]'s, with that answer. */
    fun nextChange(
        schedule: Schedule,
        from: LocalDate,
        knownCycleIds: Set<String>,
    ): Pair<LocalDate, Resolution?>? {
        val now = resolve(schedule, from, knownCycleIds)
        for (offset in 1L..366L) {
            val date = from.plusDays(offset)
            val then = resolve(schedule, date, knownCycleIds)
            if (then != now) return date to then
        }
        return null
    }

    /** Ids of usable entries that never win on any day of the year (an earlier entry covers them). */
    fun shadowedEntryIds(schedule: Schedule, knownCycleIds: Set<String>): Set<String> {
        val winners = HashSet<String>()
        var date = LocalDate.of(2024, 1, 1) // leap year, so Feb 29 is scanned too
        repeat(366) {
            resolve(schedule, date, knownCycleIds)?.entryId?.let(winners::add)
            date = date.plusDays(1)
        }
        return schedule.entries
            .filter { it.cycleId in knownCycleIds && it.id !in winners }
            .mapTo(HashSet()) { it.id }
    }
}
