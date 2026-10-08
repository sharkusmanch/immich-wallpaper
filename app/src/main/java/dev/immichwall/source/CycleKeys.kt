package dev.immichwall.source

import dev.immichwall.settings.SettingsRepository
import java.time.LocalDate

/**
 * The cache partition key of a cycle. This is the ONE definition: the sync stamps it on
 * every photo it prepares, the rotation only shows photos carrying the active cycle's key,
 * and the purge deletes photos whose key is no longer needed. The format is unchanged from
 * upstream's `sourceKey`, so an existing cache keeps matching.
 */
object CycleKeys {

    /**
     * Quality settings are part of the identity: photos ingested under other rules count
     * as a different set. [today] only matters for Memories, whose content is per-day.
     * The source's part is [SavedCycle.keyBase]: the spec's hash as it always was, until the
     * cycle's display names are first brought up to date, and from then on that same value,
     * kept in the cycle. A renamed album therefore keeps its cached photos.
     */
    fun keyFor(cycle: SavedCycle, qualityFilterEnabled: Boolean, today: LocalDate): String {
        val qualityTag = if (qualityFilterEnabled) "q:${cycle.peoplePreference}" else "q:off"
        return "${cycle.keyBase(today.toString())}|$qualityTag"
    }

    /** Key of the active cycle, or null when none is configured yet. */
    fun activeKey(settings: SettingsRepository, today: LocalDate = LocalDate.now()): String? {
        val active = settings.cyclesConsistentWithActiveSpec()
            .firstOrNull { it.id == settings.activeCycleId } ?: return null
        return keyFor(active, settings.qualityFilterEnabled, today)
    }
}
