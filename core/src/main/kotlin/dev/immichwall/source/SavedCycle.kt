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
     * The first part of this cycle's cache partition key (`CycleKeys.keyFor` appends the
     * quality tag): [frozenKey] once there is one, else [SourceSpec.stableKey] exactly as
     * before cycles had a frozen key, so a key that is on a device does not move.
     *
     * A Memories cycle always follows [today]: its photos are per-day and it has no names.
     */
    fun keyBase(today: String): String =
        usableFrozenKey().takeIf { spec !is SourceSpec.Memories } ?: spec.stableKey(today)

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
