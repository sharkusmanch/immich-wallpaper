package dev.immichwall.source

/**
 * Keeps the display names stored in saved cycles in step with the server. A [SourceSpec]
 * holds the album and people names it was built with; nothing else asks for them again,
 * so after a rename in Immich the app would go on showing the old name for good. Each sync
 * fetches the current names ([needed] says which lists) and applies them with [refreshed].
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
