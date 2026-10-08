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
     * Stable identity of this spec: the first part of a cycle's cache partition key
     * ([CycleKeys.keyFor] appends the quality tag). Photos are cached, shown and purged per
     * key, so a changed source (or, for Memories, a new day) is a new, empty partition; the
     * old one keeps showing until the new one has a photo and is purged after that.
     *
     * @param today the current date as `YYYY-MM-DD`; only [Memories] folds it into the key.
     */
    fun stableKey(today: String): String {
        val serialized = ApiJson.json.encodeToString(serializer(), this)
        val material = if (this is Memories) "$serialized|$today" else serialized
        return sha256Hex(material)
    }

    /**
     * This spec with every display name blanked: what it selects, without what it is called.
     * Two specs select the same photos exactly when their identities are equal. Names are
     * copies of the server's and change when an album or a person is renamed there.
     */
    fun identity(): SourceSpec = when (this) {
        is People -> copy(names = emptyList())
        is Album -> copy(albumName = "")
        is SmartQuery -> copy(personNames = emptyList())
        is Custom -> copy(personNames = emptyList(), albumName = "")
        is Location, Favorites, is Memories, EverythingRandom -> this
    }

    /**
     * This spec with each stored display name replaced by the server's current one:
     * [albumNames] and [personNames] map an id to its name now. An id that is not in the
     * map, or whose name there is blank, keeps the name it has, so a partial or empty list
     * never wipes one. People's names are stored in the order of their ids; only names that
     * are stored are replaced (a list shorter than the ids stays that short). So the two
     * kinds differ where a name is missing: an album whose stored name is blank is given
     * the server's, while a person whose id has no stored name at its position is not given
     * one. Returns this same object when nothing changes. What the spec selects
     * ([identity]) never changes.
     */
    fun withNames(albumNames: Map<String, String>, personNames: Map<String, String>): SourceSpec = when (this) {
        is People -> currentNames(names, ids, personNames).let { if (it == names) this else copy(names = it) }
        is Album -> currentName(albumName, albumId, albumNames).let { if (it == albumName) this else copy(albumName = it) }
        is SmartQuery ->
            currentNames(this.personNames, personIds, personNames).let { if (it == this.personNames) this else copy(personNames = it) }
        is Custom -> {
            val people = currentNames(this.personNames, personIds, personNames)
            val album = currentName(albumName, albumId, albumNames)
            if (people == this.personNames && album == albumName) this else copy(personNames = people, albumName = album)
        }
        is Location, Favorites, is Memories, EverythingRandom -> this
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

/** The server's name for [id], or [stored] when it has none to offer (or there is no id to ask about). */
private fun currentName(stored: String, id: String, names: Map<String, String>): String =
    if (id.isBlank()) stored else names[id]?.takeIf { it.isNotBlank() } ?: stored

/** [stored] names, each paired with the id at its position; a name with no id there is kept. */
private fun currentNames(stored: List<String>, ids: List<String>, names: Map<String, String>): List<String> =
    stored.mapIndexed { index, name -> ids.getOrNull(index)?.let { currentName(name, it, names) } ?: name }

private fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
