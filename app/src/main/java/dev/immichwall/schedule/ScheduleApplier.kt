package dev.immichwall.schedule

import android.content.Context
import dev.immichwall.BuildConfig
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.SavedCycle
import dev.immichwall.util.Logg
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Turns the schedule's answer into the app's existing "activate a cycle" operation. All the
 * decisions are [SchedulePlan]'s; this only reads settings and writes the outcome back.
 * Callers start the sync or redraw that suits their context (wake path, worker, UI).
 */
object ScheduleApplier {

    private const val TAG = "ScheduleApplier"

    /** The date the schedule runs on: the device's local date, or the debug override. */
    fun today(settings: SettingsRepository): LocalDate {
        if (BuildConfig.DEBUG) {
            val raw = settings.debugToday
            if (raw.isNotBlank()) {
                try {
                    return LocalDate.parse(raw)
                } catch (_: DateTimeParseException) {
                    // fall through to the real date
                }
            }
        }
        return LocalDate.now()
    }

    fun plan(settings: SettingsRepository, date: LocalDate = today(settings)): SchedulePlan {
        val known = settings.cyclesConsistentWithActiveSpec().mapTo(HashSet()) { it.id }
        return SchedulePlan.compute(
            settings.schedule, settings.scheduleOverride, settings.activeCycleId, date, known,
        )
    }

    /**
     * Makes the cycle the schedule calls for the active one. Returns true when the active
     * cycle changed. Cheap when nothing is due; safe from any thread.
     */
    @Synchronized
    fun applyIfDue(ctx: Context): Boolean {
        val settings = SettingsRepository.get(ctx)
        val plan = plan(settings)
        if (plan.clearOverride) settings.scheduleOverride = null
        val target = plan.activeCycleId ?: return false
        if (target == settings.activeCycleId) return false
        val cycle = settings.activateCycle(target) ?: return false
        Logg.d(TAG, "schedule: switched to '${cycle.name}'")
        return true
    }

    /**
     * The user picked [cycleId] by hand. With the schedule on, that pick holds until the
     * schedule's own answer next changes; with it off this is a plain activation.
     */
    @Synchronized
    fun activateManually(ctx: Context, cycleId: String): SavedCycle? {
        val settings = SettingsRepository.get(ctx)
        val known = settings.savedCycles.mapTo(HashSet()) { it.id }
        settings.scheduleOverride =
            SchedulePlan.overrideFor(settings.schedule, cycleId, today(settings), known)
        return settings.activateCycle(cycleId)
    }

    /**
     * Runs [block] so that it cannot interleave with [applyIfDue] or [activateManually]:
     * for replacing the cycles and the schedule in one go (restoring a backup), where an
     * [applyIfDue] that had already planned against the old ones would otherwise activate
     * an old cycle afterwards. [block] may call [applyIfDue] itself.
     */
    @Synchronized
    fun <T> exclusively(block: () -> T): T = block()

    /** Drops a manual pick and returns to the schedule. True when the active cycle changed. */
    fun resumeSchedule(ctx: Context): Boolean {
        SettingsRepository.get(ctx).scheduleOverride = null
        return applyIfDue(ctx)
    }
}
