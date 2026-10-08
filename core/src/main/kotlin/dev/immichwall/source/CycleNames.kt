package dev.immichwall.source

import dev.immichwall.api.ApiException
import dev.immichwall.api.ImmichApiClient
import java.io.IOException

/**
 * Keeps the display names stored in saved cycles in step with the server. A [SourceSpec]
 * holds the album and people names it was built with; nothing else asks for them again,
 * so after a rename in Immich the app would go on showing the old name for good. Each sync
 * fetches the current names ([needed] says which lists, [fetch] reads them) and applies
 * them with [refreshed].
 */
object CycleNames {

    /** Which of the server's name lists a refresh could use. */
    data class Needed(val albums: Boolean, val people: Boolean)

    /**
     * Whether any of [cycles] stores an album name and whether any stores a person's name,
     * so a sync does not fetch a list nobody refers to. Agrees with [SourceSpec.withNames]:
     * a list this leaves out could not have changed anything.
     */
    fun needed(cycles: List<SavedCycle>): Needed {
        var albums = false
        var people = false
        for (cycle in cycles) {
            when (val spec = cycle.spec) {
                is SourceSpec.Album -> albums = albums || spec.albumId.isNotBlank()
                is SourceSpec.People -> people = people || namesPeople(spec.ids, spec.names)
                is SourceSpec.SmartQuery -> people = people || namesPeople(spec.personIds, spec.personNames)
                is SourceSpec.Custom -> {
                    albums = albums || spec.albumId.isNotBlank()
                    people = people || namesPeople(spec.personIds, spec.personNames)
                }
                is SourceSpec.Location, SourceSpec.Favorites, is SourceSpec.Memories, SourceSpec.EverythingRandom -> Unit
            }
        }
        return Needed(albums, people)
    }

    private fun namesPeople(ids: List<String>, names: List<String>): Boolean = ids.isNotEmpty() && names.isNotEmpty()

    /**
     * What became of one of the two lists in [fetch], for the caller to log. It holds a
     * status code or a class name and nothing the server sent: an exception's message can
     * quote a response body, and those bodies are album and people names.
     */
    sealed interface ListOutcome {
        /** No saved cycle stores a name from this list; it was not requested. */
        data object NotNeeded : ListOutcome

        /** Read to its end. */
        data object Complete : ListOutcome

        /** People only: page [pages] still said there was more. The names read are returned. */
        data class Capped(val pages: Int) : ListOutcome

        /** People only: the run was stopped between two pages. The names read are returned. */
        data object Stopped : ListOutcome

        /** The server answered with HTTP status [code]. No names from this list. */
        data class HttpError(val code: Int) : ListOutcome

        /**
         * The request failed in some other way (no answer, a body that does not parse or is
         * over the size limit); [exception] is the exception's class name. No names from
         * this list.
         */
        data class Failed(val exception: String) : ListOutcome

        /** People only: not requested, because the album list had just ended as [Failed]. */
        data object Skipped : ListOutcome
    }

    /**
     * The result of [fetch]: id to current name for each list, and how each list ended.
     * Not a data class on purpose: its `toString` would print the names.
     */
    class Fetched(
        val albumNames: Map<String, String>,
        val personNames: Map<String, String>,
        val albums: ListOutcome,
        val people: ListOutcome,
    )

    /**
     * Reads from the server the lists [needed] asks for: the album list in one request, the
     * people list page by page (at most [maxPeoplePages]; [isStopped] is asked between two
     * pages).
     *
     * This never fails for a reason the server or the network can cause, because names are
     * not worth a failed sync and a sync asked for neither list before names were refreshed.
     * A list whose request ends in an [ApiException] or any [IOException] (in this client
     * that covers a timeout, a body that does not parse, an empty one and one over the size
     * limit) comes back EMPTY, with the reason in its [ListOutcome]: [SourceSpec.withNames]
     * keeps the stored name for an id it is not given, so an empty list changes nothing,
     * and a people list that broke off on a later page is not applied in part. Any other
     * exception is a bug and is not caught.
     *
     * After the album list failed without an HTTP answer the people list is not requested:
     * the link is most likely dead, and a second timeout would only delay the failure the
     * sync's next request reports anyway. After an HTTP error it still is.
     */
    fun fetch(client: ImmichApiClient, needed: Needed, maxPeoplePages: Int, isStopped: () -> Boolean): Fetched {
        val (albumNames, albums) =
            if (!needed.albums) NONE to ListOutcome.NotNeeded
            else listOrNone { names ->
                client.getAlbums().forEach { names[it.id] = it.albumName }
                ListOutcome.Complete
            }
        val (personNames, people) = when {
            !needed.people -> NONE to ListOutcome.NotNeeded
            albums is ListOutcome.Failed -> NONE to ListOutcome.Skipped
            else -> listOrNone { names ->
                var outcome: ListOutcome = ListOutcome.Capped(maxPeoplePages)
                for (page in 1..maxPeoplePages) {
                    val response = client.getPeople(page)
                    response.people.forEach { names[it.id] = it.name }
                    if (!response.hasNextPage) {
                        outcome = ListOutcome.Complete
                        break
                    }
                    if (page < maxPeoplePages && isStopped()) {
                        outcome = ListOutcome.Stopped
                        break
                    }
                }
                outcome
            }
        }
        return Fetched(albumNames, personNames, albums, people)
    }

    private val NONE: Map<String, String> = emptyMap()

    /** What [read] collected and how it ended, or no names at all when it failed part-way. */
    private inline fun listOrNone(read: (MutableMap<String, String>) -> ListOutcome): Pair<Map<String, String>, ListOutcome> {
        val names = LinkedHashMap<String, String>()
        return try {
            val outcome = read(names)
            names to outcome
        } catch (e: ApiException) {
            NONE to ListOutcome.HttpError(e.code)
        } catch (e: IOException) {
            NONE to ListOutcome.Failed(e.javaClass.name)
        }
    }

    /**
     * [cycles] with the server's current names ([SourceSpec.withNames]), or null when that
     * changes nothing, so the caller can skip the write. A cycle whose spec changes:
     *
     * - is named after its new spec, but only if its name was the old spec's
     *   [SourceSpec.summaryLabel], as every screen that saves a cycle makes it. A name that
     *   came from elsewhere (a hand-edited backup) is not the app's to rewrite.
     * - gets, unless it has one, the key it had until now as its [SavedCycle.frozenKey], so
     *   [SavedCycle.keyBase] answers the same before and after and its cached photos stay.
     *
     * Every other cycle is returned as the same object.
     *
     * @param today `YYYY-MM-DD`; as for [SourceSpec.stableKey].
     */
    fun refreshed(
        cycles: List<SavedCycle>,
        albumNames: Map<String, String>,
        personNames: Map<String, String>,
        today: String,
    ): List<SavedCycle>? {
        if (albumNames.isEmpty() && personNames.isEmpty()) return null
        var changed = false
        val refreshed = cycles.map { cycle ->
            val spec = cycle.spec.withNames(albumNames, personNames)
            if (spec == cycle.spec) return@map cycle
            changed = true
            cycle.copy(
                name = if (cycle.name == cycle.spec.summaryLabel()) spec.summaryLabel() else cycle.name,
                spec = spec,
                frozenKey = cycle.usableFrozenKey() ?: cycle.spec.stableKey(today),
            )
        }
        return refreshed.takeIf { changed }
    }
}
