package dev.immichwall.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * [CycleSave.of]: what the wizard's save stores. The key a cycle's photos are cached under
 * must come through an edit that still selects the same photos; the hashes here are v1.1.0's
 * for these very specs ([StableKeyPinTest]).
 */
class CycleSaveTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val otherAlbum = "00000000-0000-4000-8000-0000000000a2"
    private val today = "2026-10-07"

    private val tripsKey = "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94"
    private val coupleKey = "dc6b7b36f06a273903fd68b05e69554569d230b9571855627e8913806255df0e"
    private val beachKey = "804fe3cca84c3195ebccc9538769bf9fd21f0467680c4789780fb2ee50f7dc72"
    private val customKey = "f110b722d6e97471ced050960a95255ddd166d971edd4edf3d68068e073ede0c"

    private val trips = SourceSpec.Album(album, "Trips")
    private val journeys = SourceSpec.Album(album, "Journeys")
    private val couple = SourceSpec.People(ids = listOf(ann, bob), names = listOf("Ann", "Bob"), requireAll = true)
    private val beach = SourceSpec.SmartQuery(query = "beach", personIds = listOf(ann), personNames = listOf("Ann"))
    private val custom = SourceSpec.Custom(
        personIds = listOf(ann), personNames = listOf("Ann"), albumId = album, albumName = "Trips",
        query = "dog", city = "Springfield", takenAfter = "2020-01-01",
    )

    private fun cycle(id: String, spec: SourceSpec, frozenKey: String = "", preference: String = "prefer") =
        SavedCycle(id = id, name = spec.summaryLabel(), spec = spec, peoplePreference = preference, frozenKey = frozenKey)

    private val first = cycle("c1", SourceSpec.Favorites)
    private val last = cycle("c3", SourceSpec.Location("Springfield"))

    /** Saves [spec] over the cycle "c2" in a list of three and checks that only "c2" was touched. */
    private fun edit(previous: SavedCycle, spec: SourceSpec, preference: String = previous.peoplePreference): SavedCycle {
        val before = listOf(first, previous, last)
        val save = CycleSave.of(before, editingId = "c2", newId = "new", spec = spec, peoplePreference = preference, today = today)
        assertEquals(3, save.cycles.size)
        assertSame(first, save.cycles[0])
        assertSame(save.cycle, save.cycles[1])
        assertSame(last, save.cycles[2])
        assertEquals("c2", save.cycle.id)
        assertEquals(spec, save.cycle.spec)
        assertEquals(spec.summaryLabel(), save.cycle.name)
        assertEquals(preference, save.cycle.peoplePreference)
        return save.cycle
    }

    // --- a new cycle ---

    @Test fun `a new cycle is appended with no frozen key`() {
        val before = listOf(first, last)
        val save = CycleSave.of(before, editingId = null, newId = "new", spec = trips, peoplePreference = "require", today = today)
        assertEquals(SavedCycle(id = "new", name = "Album: Trips", spec = trips, peoplePreference = "require", frozenKey = ""), save.cycle)
        assertEquals(listOf(first, last, save.cycle), save.cycles)
        assertSame(first, save.cycles[0])
        assertSame(last, save.cycles[1])
        assertEquals(tripsKey, save.cycle.keyBase(today))
    }

    @Test fun `the first cycle of all is a list of one`() {
        val save = CycleSave.of(emptyList(), editingId = null, newId = "new", spec = trips, peoplePreference = "prefer", today = today)
        assertEquals(listOf(save.cycle), save.cycles)
        assertEquals("new", save.cycle.id)
        assertEquals("", save.cycle.frozenKey)
    }

    @Test fun `a new cycle for an album another cycle already has takes nothing from that cycle`() {
        val renamed = cycle("c2", journeys, frozenKey = tripsKey)
        val save = CycleSave.of(listOf(first, renamed), editingId = null, newId = "new", spec = journeys, peoplePreference = "prefer", today = today)
        assertEquals("", save.cycle.frozenKey)
        assertEquals(listOf(first, renamed, save.cycle), save.cycles)
        assertSame(renamed, save.cycles[1])
    }

    // --- an edit that selects the same photos: the key must not move ---

    @Test fun `an edit that changes nothing keeps its place and the key v1_1_0 gave the cycle`() {
        val previous = cycle("c2", trips)
        val saved = edit(previous, trips)
        assertEquals("", saved.frozenKey)
        assertEquals(tripsKey, previous.keyBase(today))
        assertEquals(tripsKey, saved.keyBase(today))
    }

    @Test fun `an edit of a cycle with a frozen key keeps its place and its key base`() {
        val previous = cycle("c2", journeys, frozenKey = tripsKey)
        assertEquals(tripsKey, previous.keyBase(today))
        // Saved as it is, and saved with a name that is newer still.
        for (spec in listOf(journeys, SourceSpec.Album(album, "Voyages"))) {
            val saved = edit(previous, spec)
            assertEquals(previous.keyBase(today), saved.keyBase(today), spec.toString())
            assertEquals(tripsKey, saved.keyBase(today), spec.toString())
            assertEquals(tripsKey, saved.frozenKey, spec.toString())
        }
    }

    @Test fun `an edit of a cycle without a frozen key whose names changed keeps its place and its key base`() {
        val previous = cycle("c2", trips)
        val saved = edit(previous, journeys)
        assertEquals(previous.keyBase(today), saved.keyBase(today))
        assertEquals(tripsKey, saved.keyBase(today))
        // The frozen key is what holds it: the saved spec hashes to something else.
        assertNotEquals(tripsKey, saved.spec.stableKey(today))
        assertEquals("Album: Journeys", saved.name)
    }

    @Test fun `every kind of cycle with names keeps the key v1_1_0 gave it when an edit only brings other names`() {
        val edits = listOf(
            Triple(trips, journeys, tripsKey),
            Triple(couple, couple.copy(names = listOf("Anna", "Robert")), coupleKey),
            Triple(beach, beach.copy(personNames = listOf("Anna")), beachKey),
            Triple(custom, custom.copy(personNames = listOf("Anna"), albumName = "Journeys"), customKey),
        )
        for ((old, new, key) in edits) {
            val previous = cycle("c2", old)
            assertEquals(key, previous.keyBase(today), old.toString())
            val saved = edit(previous, new)
            assertEquals(key, saved.keyBase(today), old.toString())
            // And again from the saved cycle, back to the names it started with.
            assertEquals(key, edit(saved, old).keyBase(today), old.toString())
        }
    }

    @Test fun `changing only the people preference leaves the key base where it was`() {
        for (previous in listOf(cycle("c2", trips), cycle("c2", journeys, frozenKey = tripsKey))) {
            val saved = edit(previous, previous.spec, preference = "require")
            assertEquals("require", saved.peoplePreference)
            assertEquals(tripsKey, saved.keyBase(today))
            assertEquals(previous.frozenKey, saved.frozenKey)
        }
    }

    @Test fun `the key comes from the cycle in the list, whichever cycles stand around it`() {
        val other = cycle("c9", SourceSpec.Album(otherAlbum, "Trips"), frozenKey = "b".repeat(64))
        val previous = cycle("c2", journeys, frozenKey = tripsKey)
        val save = CycleSave.of(listOf(other, previous), editingId = "c2", newId = "new", spec = journeys, peoplePreference = "prefer", today = today)
        assertEquals(tripsKey, save.cycle.frozenKey)
        assertSame(other, save.cycles[0])
        assertSame(save.cycle, save.cycles[1])
    }

    // --- an edit that selects other photos ---

    @Test fun `an edit to other photos drops the frozen key`() {
        val previous = cycle("c2", journeys, frozenKey = tripsKey)
        val elsewhere = SourceSpec.Album(otherAlbum, "Journeys")
        val saved = edit(previous, elsewhere)
        assertEquals("", saved.frozenKey)
        assertEquals(elsewhere.stableKey(today), saved.keyBase(today))
        assertNotEquals(tripsKey, saved.keyBase(today))

        val favorites = edit(previous, SourceSpec.Favorites)
        assertEquals("", favorites.frozenKey)
        assertEquals("5bc5bb1730b49f623712861e29b3759d317a3cf0fed431a131050c501a7795c9", favorites.keyBase(today))
    }

    @Test fun `an edit of an unfrozen cycle to other photos is keyed by its new spec`() {
        val saved = edit(cycle("c2", trips), couple)
        assertEquals("", saved.frozenKey)
        assertEquals(coupleKey, saved.keyBase(today))
    }

    // --- an id that is gone ---

    @Test fun `an editing id that is no longer in the list is saved as a new cycle under that id`() {
        val before = listOf(first, last)
        val save = CycleSave.of(before, editingId = "gone", newId = "new", spec = journeys, peoplePreference = "off", today = today)
        assertEquals(SavedCycle(id = "gone", name = "Album: Journeys", spec = journeys, peoplePreference = "off", frozenKey = ""), save.cycle)
        assertEquals(listOf(first, last, save.cycle), save.cycles)
    }

    @Test fun `the list given is not changed`() {
        val before = listOf(first, cycle("c2", trips), last)
        val copy = before.toList()
        CycleSave.of(before, editingId = "c2", newId = "new", spec = journeys, peoplePreference = "prefer", today = today)
        CycleSave.of(before, editingId = null, newId = "new", spec = journeys, peoplePreference = "prefer", today = today)
        assertEquals(copy, before)
    }
}
