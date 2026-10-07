package dev.immichwall.schedule

import java.time.LocalDate

/**
 * Everything the app needs to act on the schedule for one day: which cycle should be
 * active, whether a manual override is holding, and which cycles' photos to keep cached.
 */
data class SchedulePlan(
    /** Cycle that should be active today; null = leave the current one alone. */
    val activeCycleId: String?,
    val overrideActive: Boolean,
    /** The schedule's own answer for today (null when the schedule is off or has none). */
    val resolution: Resolution?,
    /** Cycles whose photos stay cached: today's first, then the days ahead. No duplicates. */
    val retainedCycleIds: List<String>,
    /** True when a stored override has expired and should be deleted. */
    val clearOverride: Boolean,
) {
    companion object {
        /** How many days ahead the schedule is consulted for prefetching and retention. */
        const val WINDOW_DAYS = 2

        /**
         * The override to store when the user picks [pickedCycleId] by hand on [date]:
         * null when the schedule is off or already wants that cycle (nothing to hold).
         */
        fun overrideFor(
            schedule: Schedule,
            pickedCycleId: String,
            date: LocalDate,
            knownCycleIds: Set<String>,
        ): ScheduleOverride? {
            if (!schedule.enabled) return null
            val scheduled = ScheduleResolver.resolve(schedule, date, knownCycleIds)?.cycleId ?: ""
            return if (pickedCycleId == scheduled) null else ScheduleOverride(pickedCycleId, scheduled)
        }

        fun compute(
            schedule: Schedule,
            override: ScheduleOverride?,
            currentActiveCycleId: String,
            date: LocalDate,
            knownCycleIds: Set<String>,
            windowDays: Int = WINDOW_DAYS,
        ): SchedulePlan {
            val current = currentActiveCycleId.takeIf { it in knownCycleIds }
            if (!schedule.enabled) {
                return SchedulePlan(current, false, null, listOfNotNull(current), override != null)
            }
            val today = ScheduleResolver.resolve(schedule, date, knownCycleIds)
            val overrideLive = override != null &&
                override.cycleId in knownCycleIds &&
                override.scheduledCycleId == (today?.cycleId ?: "")
            val active = if (overrideLive) override!!.cycleId else today?.cycleId ?: current
            val retained = LinkedHashSet<String>()
            active?.let(retained::add)
            // During a manual pick, keep what the schedule would show today as well, so
            // "resume schedule" has photos ready.
            today?.cycleId?.let(retained::add)
            for (offset in 1..windowDays) {
                ScheduleResolver.resolve(schedule, date.plusDays(offset.toLong()), knownCycleIds)
                    ?.cycleId?.let(retained::add)
            }
            return SchedulePlan(active, overrideLive, today, retained.toList(), override != null && !overrideLive)
        }
    }
}
