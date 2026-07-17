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
)
