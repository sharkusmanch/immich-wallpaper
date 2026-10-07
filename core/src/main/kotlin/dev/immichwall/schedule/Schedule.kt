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
