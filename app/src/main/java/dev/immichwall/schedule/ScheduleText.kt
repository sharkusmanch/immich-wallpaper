package dev.immichwall.schedule

import android.content.Context
import dev.immichwall.BuildConfig
import dev.immichwall.R
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.CycleLabels
import java.time.format.DateTimeFormatter

/** Human-readable schedule state, shared by the status card and the schedule screen. */
object ScheduleText {

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    /**
     * What the schedule is doing today, what comes next, and whether a manual pick is holding.
     * A debug build with a pretend date set says so on its own line: that date outlives the
     * test it was set for, and a schedule quietly running on the wrong day looks like a bug.
     */
    fun summary(ctx: Context, settings: SettingsRepository): String {
        val state = stateText(ctx, settings)
        // Release builds ignore the stored date (see ScheduleApplier.today) and show nothing.
        if (!BuildConfig.DEBUG || settings.debugToday.isBlank()) return state
        val debugLine = ctx.getString(R.string.schedule_summary_debug_date, ScheduleApplier.today(settings).toString())
        return "$state\n$debugLine"
    }

    private fun stateText(ctx: Context, settings: SettingsRepository): String {
        val schedule = settings.schedule
        if (!schedule.enabled) return ctx.getString(R.string.schedule_summary_off)
        val cycles = settings.cyclesConsistentWithActiveSpec()
        val known = cycles.mapTo(HashSet()) { it.id }
        // Labels, not bare names: the same ones the cycle list and the schedule screen show.
        val names = CycleLabels.of(cycles).associate { it.cycle.id to it.label }
        val today = ScheduleApplier.today(settings)
        val plan = ScheduleApplier.plan(settings, today)

        val lines = mutableListOf<String>()
        val now = plan.resolution
        lines += if (now == null) {
            ctx.getString(R.string.schedule_summary_none)
        } else {
            ctx.getString(R.string.schedule_summary_today, entryLabel(ctx, schedule, now, names), names[now.cycleId].orEmpty())
        }
        ScheduleResolver.nextChange(schedule, today, known)?.let { (date, next) ->
            lines += if (next == null) {
                ctx.getString(R.string.schedule_summary_next_none, date.format(dayFormat))
            } else {
                ctx.getString(R.string.schedule_summary_next, date.format(dayFormat), entryLabel(ctx, schedule, next, names))
            }
        }
        if (plan.overrideActive) {
            lines += ctx.getString(R.string.schedule_summary_override, names[plan.activeCycleId].orEmpty())
        }
        return lines.joinToString("\n")
    }

    /**
     * What to call the entry that won: its name, or its cycle's name when it was left
     * unnamed (so the label follows the cycle if that is changed later), or "default" when
     * no entry matched. [cycleNames] maps a cycle id to its [CycleLabels] label.
     */
    fun entryLabel(
        ctx: Context,
        schedule: Schedule,
        resolution: Resolution,
        cycleNames: Map<String, String>,
    ): String {
        val entry = resolution.entryId?.let { id -> schedule.entries.firstOrNull { it.id == id } }
            ?: return ctx.getString(R.string.schedule_default_label)
        return entry.name.ifBlank { cycleNames[entry.cycleId].orEmpty() }
    }
}
