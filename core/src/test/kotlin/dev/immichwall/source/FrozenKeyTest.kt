package dev.immichwall.source

import dev.immichwall.api.ApiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrozenKeyTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val otherAlbum = "00000000-0000-4000-8000-0000000000a2"
    private val today = "2026-10-07"

    /** What `Album(album, "Trips")` hashed to in v1.1.0; see [StableKeyPinTest]. */
    private val tripsKey = "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94"
    private val someKey = "a".repeat(64)

    private val trips = SourceSpec.Album(album, "Trips")
    private fun cycle(spec: SourceSpec, frozenKey: String = "") =
        SavedCycle(id = "c1", name = spec.summaryLabel(), spec = spec, frozenKey = frozenKey)

    // --- identity ---

    @Test fun `specs that differ only in names have the same identity`() {
        assertEquals(trips.identity(), SourceSpec.Album(album, "Journeys").identity())
        assertEquals(
            SourceSpec.People(listOf(ann, bob), listOf("Ann", "Bob"), requireAll = true).identity(),
            SourceSpec.People(listOf(ann, bob), listOf("Anna"), requireAll = true).identity(),
        )
        assertEquals(
            SourceSpec.SmartQuery("beach", listOf(ann), listOf("Ann")).identity(),
            SourceSpec.SmartQuery("beach", listOf(ann), emptyList()).identity(),
        )
        assertEquals(
            SourceSpec.Custom(personIds = listOf(ann), personNames = listOf("Ann"), albumId = album, albumName = "Trips", city = "Springfield").identity(),
            SourceSpec.Custom(personIds = listOf(ann), personNames = listOf("Anna"), albumId = album, albumName = "Journeys", city = "Springfield").identity(),
        )
    }

    @Test fun `specs that select other photos have other identities`() {
        assertNotEquals(trips.identity(), SourceSpec.Album(otherAlbum, "Trips").identity())
        assertNotEquals(
            SourceSpec.People(listOf(ann), listOf("Ann")).identity(),
            SourceSpec.People(listOf(bob), listOf("Ann")).identity(),
        )
        assertNotEquals(
            SourceSpec.People(listOf(ann, bob), listOf("Ann", "Bob"), requireAll = true).identity(),
            SourceSpec.People(listOf(ann, bob), listOf("Ann", "Bob"), requireAll = false).identity(),
        )
        assertNotEquals(
            SourceSpec.SmartQuery("beach", listOf(ann), listOf("Ann")).identity(),
            SourceSpec.SmartQuery("forest", listOf(ann), listOf("Ann")).identity(),
        )
        assertNotEquals(
            SourceSpec.Custom(albumId = album, albumName = "Trips").identity(),
            SourceSpec.Custom(albumId = album, albumName = "Trips", favoritesOnly = true).identity(),
        )
    }

    @Test fun `a spec without names is its own identity`() {
        for (spec in listOf(SourceSpec.Location("Springfield"), SourceSpec.Favorites, SourceSpec.Memories(5, 2, 0), SourceSpec.EverythingRandom)) {
            assertEquals(spec, spec.identity())
        }
    }

    // --- the base of the cache key ---

    @Test fun `a cycle without a frozen key keeps the key v1_1_0 gave it`() {
        assertEquals(tripsKey, cycle(trips).keyBase(today))
    }

    @Test fun `a cycle stored by v1_1_0 decodes without a frozen key and keeps its key`() {
        val stored = """[{"id":"c1","name":"Album: Trips","spec":{"mode":"album","albumId":"$album","albumName":"Trips"},"peoplePreference":"prefer"}]"""
        val decoded = ApiJson.json.decodeFromString(ListSerializer(SavedCycle.serializer()), stored).single()
        assertEquals("", decoded.frozenKey)
        assertEquals(tripsKey, decoded.keyBase(today))
    }

    @Test fun `a frozen key is the key base whatever the names now say`() {
        val renamed = cycle(SourceSpec.Album(album, "Journeys"), frozenKey = tripsKey)
        assertEquals(tripsKey, renamed.keyBase(today))
        assertNotEquals(tripsKey, renamed.spec.stableKey(today))
    }

    @Test fun `a memories cycle always follows the date`() {
        val memories = SourceSpec.Memories(5, 2, 0)
        val frozen = cycle(memories, frozenKey = someKey)
        assertEquals(memories.stableKey(today), frozen.keyBase(today))
        assertNotEquals(frozen.keyBase(today), frozen.keyBase("2026-10-08"))
    }

    /** The field arrives from backup files; a key goes into file names' digests, log lines and preference names. */
    @Test fun `a frozen key that is not a key this app writes is ignored`() {
        for (bad in listOf("abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), "a".repeat(63) + " ", "a".repeat(63) + "\n", "../" + "a".repeat(61))) {
            assertEquals(tripsKey, cycle(trips, frozenKey = bad).keyBase(today), bad)
        }
    }

    // --- saving an edited cycle ---

    @Test fun `an edit that selects the same photos keeps the frozen key`() {
        val previous = cycle(SourceSpec.Album(album, "Journeys"), frozenKey = tripsKey)
        assertEquals(tripsKey, frozenKeyAfterEdit(previous, SourceSpec.Album(album, "Journeys"), today))
        assertEquals(tripsKey, frozenKeyAfterEdit(previous, SourceSpec.Album(album, "Voyages"), today))
    }

    @Test fun `an edit to another album drops the frozen key`() {
        val previous = cycle(SourceSpec.Album(album, "Journeys"), frozenKey = tripsKey)
        assertEquals("", frozenKeyAfterEdit(previous, SourceSpec.Album(otherAlbum, "Journeys"), today))
        assertEquals("", frozenKeyAfterEdit(previous, SourceSpec.Favorites, today))
    }

    @Test fun `a new cycle has no frozen key`() {
        assertEquals("", frozenKeyAfterEdit(null, trips, today))
    }

    @Test fun `an edit that changes nothing leaves an unfrozen cycle unfrozen`() {
        assertEquals("", frozenKeyAfterEdit(cycle(trips), trips, today))
        val memories = SourceSpec.Memories(5, 2, 0)
        assertEquals("", frozenKeyAfterEdit(cycle(memories), memories, today))
    }

    /** The picker hands the wizard the album's current name, which may be ahead of the last sync. */
    @Test fun `an edit that only brings newer names freezes the key the cycle had`() {
        val previous = cycle(trips)
        val edited = SourceSpec.Album(album, "Journeys")
        val saved = previous.copy(spec = edited, frozenKey = frozenKeyAfterEdit(previous, edited, today))
        assertEquals(tripsKey, saved.frozenKey)
        assertEquals(previous.keyBase(today), saved.keyBase(today))
    }

    @Test fun `an unusable frozen key is not carried into an edit`() {
        val previous = cycle(trips, frozenKey = "not a key")
        assertEquals("", frozenKeyAfterEdit(previous, trips, today))
    }
}
