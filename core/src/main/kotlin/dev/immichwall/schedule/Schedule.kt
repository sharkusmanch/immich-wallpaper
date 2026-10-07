package dev.immichwall.schedule

import kotlinx.serialization.Serializable

/**
 * One date-of-year rule: from [start] to [end] (both inclusive, `MM-DD`) the wallpaper
 * draws from the saved cycle [cycleId]. An [end] earlier in the year than [start] wraps
 * the year (`11-26` to `01-01`).
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val name: String,
    val cycleId: String,
    val start: String,
    val end: String,
)

/** Ordered rules (first match wins) plus the cycle used when no rule matches. */
@Serializable
data class Schedule(
    val enabled: Boolean = false,
    val defaultCycleId: String = "",
    val entries: List<ScheduleEntry> = emptyList(),
)

/**
 * A manual cycle pick made while the schedule is on. It holds for as long as the schedule
 * keeps answering [scheduledCycleId] (what it said when the user picked); "" = it had no answer.
 */
@Serializable
data class ScheduleOverride(
    val cycleId: String,
    val scheduledCycleId: String,
)

/** What the schedule says for one date: the cycle, and the entry that won (null = default). */
data class Resolution(val cycleId: String, val entryId: String?)

/**
 * What one schedule row shows: the [title], the [cycleLine] under it (null when the title
 * already is the cycle's name), and whether the dates are [singleDay] rather than a range.
 */
data class ScheduleEntryRow(val title: String, val cycleLine: String?, val singleDay: Boolean) {
    companion object {
        /** [cycleName] is null when the entry's cycle no longer exists; [missingLabel] stands in for it. */
        fun of(entry: ScheduleEntry, cycleName: String?, missingLabel: String): ScheduleEntryRow {
            val cycle = cycleName ?: missingLabel
            val named = entry.name.isNotBlank()
            return ScheduleEntryRow(
                title = if (named) entry.name else cycle,
                cycleLine = if (named) cycle else null,
                singleDay = entry.start == entry.end,
            )
        }
    }
}
