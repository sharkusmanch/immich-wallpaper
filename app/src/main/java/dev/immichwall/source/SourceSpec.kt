package dev.immichwall.source

import dev.immichwall.api.ApiJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.security.MessageDigest

/**
 * The user's configured wallpaper source, persisted as JSON in [dev.immichwall.settings.SettingsRepository].
 *
 * Serialized polymorphically with class discriminator `"mode"` (e.g. `{"mode":"people",...}`),
 * so persisted specs stay readable and stable across releases.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("mode")
sealed class SourceSpec {

    /**
     * One or more people. [requireAll] false = ANY semantics (photos of any selected person),
     * true = ALL semantics (photos containing every selected person together).
     */
    @Serializable
    @SerialName("people")
    data class People(
        val ids: List<String>,
        val names: List<String>,
        val requireAll: Boolean = false,
    ) : SourceSpec()

    @Serializable
    @SerialName("album")
    data class Album(
        val albumId: String,
        val albumName: String,
    ) : SourceSpec()

    /** CLIP smart search, optionally narrowed to photos containing the given people. */
    @Serializable
    @SerialName("smart")
    data class SmartQuery(
        val query: String,
        val personIds: List<String> = emptyList(),
        val personNames: List<String> = emptyList(),
    ) : SourceSpec()

    @Serializable
    @SerialName("location")
    data class Location(
        val city: String,
    ) : SourceSpec()

    @Serializable
    @SerialName("favorites")
    data object Favorites : SourceSpec()

    /**
     * On-this-day memories; date-sensitive, so [stableKey] also folds in the current date.
     * [windowDays] widens the day to ±N days (0 = exactly this day). [yearsAgoMin] /
     * [yearsAgoMax] bound how far back the memories may come from, in whole years
     * relative to today (0 = unbounded on that side): all years = 0/0, only last year
     * = 1/1, "long ago" = 2/0. Legacy persisted specs (`{"mode":"memories"}`) decode
     * into the defaults, which match the old behavior closely.
     */
    @Serializable
    @SerialName("memories")
    data class Memories(
        val windowDays: Int = 3,
        val yearsAgoMin: Int = 0,
        val yearsAgoMax: Int = 0,
    ) : SourceSpec()

    @Serializable
    @SerialName("everything")
    data object EverythingRandom : SourceSpec()

    /**
     * Free combination of every filter Immich search supports: any/all of the given
     * people, an album, a CLIP text query, a city, favorites-only, and an absolute
     * taken-date range (YYYY-MM-DD, empty = unbounded). Empty fields are simply unused;
     * at least one must be set for the spec to be buildable.
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val personIds: List<String> = emptyList(),
        val personNames: List<String> = emptyList(),
        val requireAll: Boolean = false,
        val albumId: String = "",
        val albumName: String = "",
        val query: String = "",
        val city: String = "",
        val favoritesOnly: Boolean = false,
        val takenAfter: String = "",
        val takenBefore: String = "",
    ) : SourceSpec()

    /**
     * Stable identity of this spec, stored as `manifest.sourceKey`. A mismatch at refresh time
     * means the source changed (or the Memories date rolled over) and cached photos from the
     * old source should be drained as replacements arrive.
     *
     * @param today the current date as `YYYY-MM-DD`; only [Memories] folds it into the key.
     */
    fun stableKey(today: String): String {
        val serialized = ApiJson.json.encodeToString(serializer(), this)
        val material = if (this is Memories) "$serialized|$today" else serialized
        return sha256Hex(material)
    }

    /** Person ids whose faces should anchor the crop; empty when the mode has no people focus. */
    fun priorityPersonIds(): List<String> = when (this) {
        is People -> ids
        is SmartQuery -> personIds
        is Custom -> personIds
        else -> emptyList()
    }

    /** Short human-readable description for the status screen. */
    fun summaryLabel(): String = when (this) {
        is People -> {
            val who = if (names.isNotEmpty()) names.joinToString(", ") else "${ids.size} people"
            when {
                ids.size <= 1 -> "Person: $who"
                requireAll -> "People (all together): $who"
                else -> "People (any of): $who"
            }
        }
        is Album -> "Album: $albumName"
        is SmartQuery -> {
            val people = when {
                personNames.isNotEmpty() -> " with ${personNames.joinToString(", ")}"
                personIds.isNotEmpty() -> " with ${personIds.size} selected people"
                else -> ""
            }
            "Search: “$query”$people"
        }
        is Location -> "Location: $city"
        Favorites -> "Favorites"
        is Memories -> {
            val window = when {
                windowDays <= 0 -> "this day"
                windowDays <= 3 -> "around this day"
                else -> "this week"
            }
            val years = when {
                yearsAgoMin <= 1 && yearsAgoMax == 1 -> "last year"
                yearsAgoMin >= 2 && yearsAgoMax == 0 -> "$yearsAgoMin+ years ago"
                else -> "over the years"
            }
            "Memories: $window, $years"
        }
        EverythingRandom -> "Entire library"
        is Custom -> {
            val parts = mutableListOf<String>()
            if (personNames.isNotEmpty()) {
                val joiner = if (requireAll) " + " else " / "
                parts += personNames.joinToString(joiner)
            }
            if (query.isNotBlank()) parts += "“$query”"
            if (albumName.isNotBlank()) parts += albumName
            if (city.isNotBlank()) parts += city
            if (favoritesOnly) parts += "favorites"
            when {
                takenAfter.isNotBlank() && takenBefore.isNotBlank() -> parts += "$takenAfter–$takenBefore"
                takenAfter.isNotBlank() -> parts += "after $takenAfter"
                takenBefore.isNotBlank() -> parts += "before $takenBefore"
            }
            "Custom: " + (parts.joinToString(" · ").ifBlank { "everything" })
        }
    }
}

private fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
