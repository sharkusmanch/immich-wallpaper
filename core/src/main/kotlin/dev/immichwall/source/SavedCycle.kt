package dev.immichwall.source

import kotlinx.serialization.Serializable

/**
 * A saved wallpaper configuration ("cycle"): a named [SourceSpec] the user can keep
 * around and activate with one tap. Exactly one cycle is active at a time (its spec is
 * mirrored into [dev.immichwall.settings.SettingsRepository.sourceSpec], which the sync
 * pipeline reads); the others wait, untouched, so the user can build and experiment
 * without dropping the cycle that's currently running.
 */
@Serializable
data class SavedCycle(
    val id: String,
    val name: String,
    val spec: SourceSpec,
    /** Per-cycle face-presence weighting: "off" | "prefer" | "require" (PhotoScorer.PEOPLE_*). */
    val peoplePreference: String = "prefer",
    /**
     * What [SourceSpec.stableKey] gave this cycle before the display names in [spec] were
     * first brought up to date; empty until then. [SourceSpec.stableKey] hashes those names
     * too, so without this a renamed album would look like a new, empty source and its
     * cached photos would be purged and fetched again. Opaque: it only has to stay the same
     * for as long as the cycle selects the same photos ([frozenKeyAfterEdit]).
     */
    val frozenKey: String = "",
) {
    /**
     * The first part of this cycle's cache partition key ([cacheKey] appends the quality
     * tag): [frozenKey] once there is one, else [SourceSpec.stableKey] exactly as
     * before cycles had a frozen key, so a key that is on a device does not move.
     *
     * A Memories cycle always follows [today]: its photos are per-day and it has no names.
     */
    fun keyBase(today: String): String =
        usableFrozenKey().takeIf { spec !is SourceSpec.Memories } ?: spec.stableKey(today)

    /**
     * This cycle's whole cache partition key: [keyBase], then `|q:` and [peoplePreference],
     * or `|q:off` when the quality filter is off. Quality settings are part of the identity:
     * photos ingested under other rules count as a different set.
     *
     * The string is on devices, stamped on every cached photo, and photos are shown, kept
     * and purged by comparing it. It is composed here and nowhere else (`CycleKeys.keyFor`
     * calls this), exactly as v1.1.0 composed it, and `CacheKeyPinTest` holds it to that.
     */
    fun cacheKey(qualityFilterEnabled: Boolean, today: String): String {
        val qualityTag = if (qualityFilterEnabled) "q:$peoplePreference" else "q:off"
        return "${keyBase(today)}|$qualityTag"
    }

    /**
     * [frozenKey] if it has the shape [SourceSpec.stableKey] writes (64 lowercase hex
     * digits), else null. The field also arrives from backup files, which can carry
     * anything, and a cache key ends up in log lines and preference names.
     */
    internal fun usableFrozenKey(): String? =
        frozenKey.takeIf { key -> key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } }
}

/**
 * The [SavedCycle.frozenKey] to save a cycle with when the wizard stores [spec] over
 * [previous] (null = a new cycle, which has none).
 *
 * While the edit selects the same photos ([SourceSpec.identity]) the cycle keeps the key
 * its cached photos carry: the frozen one, or, when it has none yet and [spec] only brings
 * other names (the pickers show the server's current ones, which can be ahead of the last
 * sync), the key the cycle had until now. An edit that selects other photos drops it, and
 * the cycle is keyed by its new spec like any new source.
 */
fun frozenKeyAfterEdit(previous: SavedCycle?, spec: SourceSpec, today: String): String = when {
    previous == null || previous.spec.identity() != spec.identity() -> ""
    previous.spec == spec -> previous.usableFrozenKey().orEmpty()
    else -> previous.usableFrozenKey() ?: previous.spec.stableKey(today)
}

/**
 * What the wizard's save stores: the [cycle] it saved and the whole list ([cycles]) with
 * that cycle in it. Worked out in one place, from one list, so that whoever stores it can
 * read the list, apply this and write the result without letting go of its lock.
 */
data class CycleSave(val cycle: SavedCycle, val cycles: List<SavedCycle>) {
    companion object {
        /**
         * Saves [spec] with [peoplePreference] into [cycles]: over the cycle [editingId]
         * when the wizard was editing one, else as a new cycle with the id [newId]. The
         * cycle is named after its spec ([SourceSpec.summaryLabel]).
         *
         * Its [SavedCycle.frozenKey] is [frozenKeyAfterEdit] against the cycle with that id
         * in [cycles], not against a copy read earlier: a sync can freeze a cycle's key at
         * any moment, and an edit that still selects the same photos must keep the key they
         * are cached under.
         *
         * A cycle that is replaced keeps its place: the stored order decides which of two
         * cycles with the same name is listed first ([CycleLabels]), and an edit must not
         * swap their labels. A new cycle goes to the end, and so does one whose [editingId]
         * is no longer in the list (it was deleted while the wizard was open); that one
         * keeps the id it was edited under and, like any new cycle, has no frozen key.
         *
         * @param today `YYYY-MM-DD`; as for [SourceSpec.stableKey].
         */
        fun of(
            cycles: List<SavedCycle>,
            editingId: String?,
            newId: String,
            spec: SourceSpec,
            peoplePreference: String,
            today: String,
        ): CycleSave {
            val previous = editingId?.let { id -> cycles.firstOrNull { it.id == id } }
            val cycle = SavedCycle(
                id = editingId ?: newId,
                name = spec.summaryLabel(),
                spec = spec,
                peoplePreference = peoplePreference,
                frozenKey = frozenKeyAfterEdit(previous, spec, today),
            )
            val saved =
                if (cycles.any { it.id == cycle.id }) cycles.map { if (it.id == cycle.id) cycle else it }
                else cycles + cycle
            return CycleSave(cycle, saved)
        }
    }
}
