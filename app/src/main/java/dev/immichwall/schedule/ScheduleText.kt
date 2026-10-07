package dev.immichwall.schedule

import android.content.Context
import dev.immichwall.R
import dev.immichwall.settings.SettingsRepository
import java.time.format.DateTimeFormatter

/** Human-readable schedule state, shared by the status card and the schedule screen. */
object ScheduleText {

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    /** What the schedule is doing today, what comes next, and whether a manual pick is holding. */
    fun summary(ctx: Context, settings: SettingsRepository): String {
        val schedule = settings.schedule
        if (!schedule.enabled) return ctx.getString(R.string.schedule_summary_off)
        val cycles = settings.cyclesConsistentWithActiveSpec()
        val known = cycles.mapTo(HashSet()) { it.id }
        val names = cycles.associate { it.id to it.name }
        val today = ScheduleApplier.today(settings)
        val plan = ScheduleApplier.plan(settings, today)

        val lines = mutableListOf<String>()
        val now = plan.resolution
        lines += if (now == null) {
            ctx.getString(R.string.schedule_summary_none)
        } else {
            ctx.getString(R.string.schedule_summary_today, entryLabel(ctx, schedule, now), names[now.cycleId].orEmpty())
        }
        ScheduleResolver.nextChange(schedule, today, known)?.let { (date, next) ->
            lines += if (next == null) {
                ctx.getString(R.string.schedule_summary_next_none, date.format(dayFormat))
            } else {
                ctx.getString(R.string.schedule_summary_next, date.format(dayFormat), entryLabel(ctx, schedule, next))
            }
        }
        if (plan.overrideActive) {
            lines += ctx.getString(R.string.schedule_summary_override, names[plan.activeCycleId].orEmpty())
        }
        return lines.joinToString("\n")
    }

    /** The winning entry's name, or "default" when no entry matched. */
    fun entryLabel(ctx: Context, schedule: Schedule, resolution: Resolution): String =
        resolution.entryId?.let { id -> schedule.entries.firstOrNull { it.id == id }?.name }
            ?: ctx.getString(R.string.schedule_default_label)
}
