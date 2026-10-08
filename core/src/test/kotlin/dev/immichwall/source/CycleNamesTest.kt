package dev.immichwall.source

import dev.immichwall.api.ApiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CycleNamesTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val cat = "00000000-0000-4000-8000-000000000003"
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val otherAlbum = "00000000-0000-4000-8000-0000000000a2"
    private val today = "2026-10-07"

    /** What `Album(album, "Trips")` hashed to in v1.1.0; see [StableKeyPinTest]. */
    private val tripsKey = "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94"

    private val trips = SourceSpec.Album(album, "Trips")
    private val couple = SourceSpec.People(listOf(ann, bob), listOf("Ann", "Bob"), requireAll = true)
    private val beach = SourceSpec.SmartQuery("beach", listOf(ann), listOf("Ann"))
    private val custom = SourceSpec.Custom(
        personIds = listOf(ann, bob), personNames = listOf("Ann", "Bob"), albumId = album, albumName = "Trips", city = "Springfield",
    )
    private val none = emptyMap<String, String>()

    private fun cycle(id: String, spec: SourceSpec, name: String = spec.summaryLabel(), frozenKey: String = "") =
        SavedCycle(id = id, name = name, spec = spec, peoplePreference = "require", frozenKey = frozenKey)

    // --- SourceSpec.withNames ---

    @Test fun `an album spec takes the album's new name`() {
        assertEquals(SourceSpec.Album(album, "Journeys"), trips.withNames(mapOf(album to "Journeys"), none))
    }

    @Test fun `a people spec takes new names by id and keeps the others`() {
        assertEquals(
            SourceSpec.People(listOf(ann, bob), listOf("Ann", "Robert"), requireAll = true),
            couple.withNames(none, mapOf(bob to "Robert", cat to "Catherine")),
        )
    }

    @Test fun `a smart search spec takes its people's new names`() {
        assertEquals(SourceSpec.SmartQuery("beach", listOf(ann), listOf("Anna")), beach.withNames(none, mapOf(ann to "Anna")))
    }

    @Test fun `a custom spec takes new names for its album and its people`() {
        assertEquals(
            custom.copy(personNames = listOf("Anna", "Bob"), albumName = "Journeys"),
            custom.withNames(mapOf(album to "Journeys"), mapOf(ann to "Anna")),
        )
    }

    @Test fun `an album id is never looked up among people nor a person among albums`() {
        assertEquals(trips, trips.withNames(none, mapOf(album to "Journeys")))
        assertEquals(couple, couple.withNames(mapOf(ann to "Anna"), none))
    }

    @Test fun `an unknown id, a blank server name and empty maps change nothing`() {
        for (spec in listOf(trips, couple, beach, custom)) {
            assertEquals(spec, spec.withNames(none, none), spec.toString())
            assertEquals(spec, spec.withNames(mapOf(otherAlbum to "Journeys"), mapOf(cat to "Catherine")), spec.toString())
            assertEquals(spec, spec.withNames(mapOf(album to ""), mapOf(ann to "  ", bob to "")), spec.toString())
        }
    }

    @Test fun `the server repeating the stored names changes nothing`() {
        assertEquals(custom, custom.withNames(mapOf(album to "Trips"), mapOf(ann to "Ann", bob to "Bob")))
    }

    @Test fun `names fewer than ids are refreshed as far as they go, without throwing`() {
        val short = SourceSpec.People(listOf(ann, bob, cat), listOf("Ann"))
        assertEquals(
            SourceSpec.People(listOf(ann, bob, cat), listOf("Anna")),
            short.withNames(none, mapOf(ann to "Anna", bob to "Robert", cat to "Catherine")),
        )
        val nameless = SourceSpec.SmartQuery("beach", listOf(ann), emptyList())
        assertEquals(nameless, nameless.withNames(none, mapOf(ann to "Anna")))
        val customShort = SourceSpec.Custom(personIds = listOf(ann, bob), personNames = listOf("Ann"))
        assertEquals(customShort.copy(personNames = listOf("Anna")), customShort.withNames(none, mapOf(ann to "Anna", bob to "Robert")))
    }

    @Test fun `names beyond the ids are left as they are`() {
        val long = SourceSpec.People(listOf(ann), listOf("Ann", "Stray"))
        assertEquals(SourceSpec.People(listOf(ann), listOf("Anna", "Stray")), long.withNames(none, mapOf(ann to "Anna")))
    }

    @Test fun `a custom spec without an album takes no album name`() {
        val noAlbum = SourceSpec.Custom(city = "Springfield")
        assertEquals(noAlbum, noAlbum.withNames(mapOf("" to "Journeys"), none))
    }

    @Test fun `specs without names are returned as they are`() {
        val all = mapOf(album to "Journeys")
        for (spec in listOf(SourceSpec.Location("Springfield"), SourceSpec.Favorites, SourceSpec.Memories(5, 2, 0), SourceSpec.EverythingRandom)) {
            assertSame(spec, spec.withNames(all, mapOf(ann to "Anna")))
        }
    }

    @Test fun `new names never change what a spec selects`() {
        for (spec in listOf(trips, couple, beach, custom)) {
            val renamed = spec.withNames(mapOf(album to "Journeys"), mapOf(ann to "Anna", bob to "Robert"))
            assertNotEquals(spec, renamed, spec.toString())
            assertEquals(spec.identity(), renamed.identity(), spec.toString())
        }
    }

    // --- the cache key across a refresh: what the whole fix rests on ---

    @Test fun `a refresh that renames an album leaves the cycle's key where it was`() {
        val before = listOf(cycle("c1", trips))
        assertEquals(tripsKey, before.single().keyBase(today))

        val after = assertNotNull(CycleNames.refreshed(before, mapOf(album to "Journeys"), none, today)).single()
        assertEquals("Journeys", (after.spec as SourceSpec.Album).albumName)
        assertEquals(tripsKey, after.keyBase(today))
        assertEquals(tripsKey, after.frozenKey)
        // Without the frozen key the cycle would have moved to another, empty partition.
        assertNotEquals(tripsKey, after.spec.stableKey(today))
    }

    @Test fun `every kind of cycle with names keeps its key across a refresh`() {
        val before = listOf(cycle("c1", trips), cycle("c2", couple), cycle("c3", beach), cycle("c4", custom))
        val after = assertNotNull(
            CycleNames.refreshed(before, mapOf(album to "Journeys"), mapOf(ann to "Anna", bob to "Robert"), today),
        )
        assertEquals(before.map { it.id }, after.map { it.id })
        for ((old, new) in before.zip(after)) {
            assertNotEquals(old.spec, new.spec, old.id)
            assertEquals(old.keyBase(today), new.keyBase(today), old.id)
            assertEquals(old.spec.stableKey(today), new.frozenKey, old.id)
        }
    }

    @Test fun `a second rename keeps the key again`() {
        val first = assertNotNull(CycleNames.refreshed(listOf(cycle("c1", trips)), mapOf(album to "Journeys"), none, today))
        val second = assertNotNull(CycleNames.refreshed(first, mapOf(album to "Voyages"), none, "2027-01-01")).single()
        assertEquals("Voyages", (second.spec as SourceSpec.Album).albumName)
        assertEquals(tripsKey, second.frozenKey)
        assertEquals(tripsKey, second.keyBase("2027-01-01"))
    }

    @Test fun `the key survives being stored and read back`() {
        val after = assertNotNull(CycleNames.refreshed(listOf(cycle("c1", trips)), mapOf(album to "Journeys"), none, today))
        val serializer = ListSerializer(SavedCycle.serializer())
        val stored = ApiJson.json.decodeFromString(serializer, ApiJson.json.encodeToString(serializer, after))
        assertEquals(after, stored)
        assertEquals(tripsKey, stored.single().keyBase(today))
    }

    @Test fun `a cycle the refresh does not touch gets no frozen key`() {
        val untouched = cycle("c2", couple)
        val favorites = cycle("c3", SourceSpec.Favorites)
        val after = assertNotNull(
            CycleNames.refreshed(listOf(cycle("c1", trips), untouched, favorites), mapOf(album to "Journeys"), mapOf(ann to "Ann"), today),
        )
        assertSame(untouched, after[1])
        assertSame(favorites, after[2])
        assertEquals("", after[1].frozenKey)
        assertEquals(couple.stableKey(today), after[1].keyBase(today))
    }

    @Test fun `a frozen key no build could have written is replaced by the key the cycle really had`() {
        val damaged = cycle("c1", trips, frozenKey = "not a key")
        assertEquals(tripsKey, damaged.keyBase(today))
        val after = assertNotNull(CycleNames.refreshed(listOf(damaged), mapOf(album to "Journeys"), none, today)).single()
        assertEquals(tripsKey, after.frozenKey)
        assertEquals(tripsKey, after.keyBase(today))
    }

    // --- CycleNames.refreshed: names and the "nothing changed" answer ---

    @Test fun `an auto-generated cycle name follows the album's new name`() {
        val after = assertNotNull(CycleNames.refreshed(listOf(cycle("c1", trips)), mapOf(album to "Journeys"), none, today)).single()
        assertEquals("Album: Journeys", after.name)
        assertEquals("c1", after.id)
        assertEquals("require", after.peoplePreference)
    }

    @Test fun `an auto-generated cycle name follows a person's new name`() {
        val after = assertNotNull(CycleNames.refreshed(listOf(cycle("c1", couple)), none, mapOf(bob to "Robert"), today)).single()
        assertEquals("People (all together): Ann, Robert", after.name)
    }

    @Test fun `a cycle name that was not the old label is left alone`() {
        val named = cycle("c1", trips, name = "Holidays")
        val after = assertNotNull(CycleNames.refreshed(listOf(named), mapOf(album to "Journeys"), none, today)).single()
        assertEquals("Holidays", after.name)
        assertEquals(SourceSpec.Album(album, "Journeys"), after.spec)
    }

    @Test fun `nothing changed is answered with null`() {
        val cycles = listOf(cycle("c1", trips), cycle("c2", couple), cycle("c3", SourceSpec.Memories(5, 2, 0)), cycle("c4", SourceSpec.Favorites))
        assertNull(CycleNames.refreshed(cycles, none, none, today))
        assertNull(CycleNames.refreshed(cycles, mapOf(album to "Trips"), mapOf(ann to "Ann", bob to "Bob"), today))
        assertNull(CycleNames.refreshed(cycles, mapOf(otherAlbum to "Journeys", album to " "), mapOf(cat to "Catherine"), today))
        assertNull(CycleNames.refreshed(emptyList(), mapOf(album to "Journeys"), none, today))
    }

    // --- CycleNames.needed ---

    @Test fun `album cycles need the album list and not the people`() {
        assertEquals(CycleNames.Needed(albums = true, people = false), CycleNames.needed(listOf(cycle("c1", trips), cycle("c2", SourceSpec.Favorites))))
        assertEquals(
            CycleNames.Needed(albums = true, people = false),
            CycleNames.needed(listOf(cycle("c1", SourceSpec.Custom(albumId = album, albumName = "Trips")))),
        )
    }

    @Test fun `a cycle that names people needs the people`() {
        assertEquals(CycleNames.Needed(albums = false, people = true), CycleNames.needed(listOf(cycle("c1", couple))))
        assertEquals(CycleNames.Needed(albums = false, people = true), CycleNames.needed(listOf(cycle("c1", beach))))
        assertEquals(CycleNames.Needed(albums = true, people = true), CycleNames.needed(listOf(cycle("c1", custom))))
        assertEquals(CycleNames.Needed(albums = true, people = true), CycleNames.needed(listOf(cycle("c1", trips), cycle("c2", couple))))
    }

    @Test fun `cycles without names need neither`() {
        val nameless = listOf(
            SourceSpec.Location("Springfield"), SourceSpec.Favorites, SourceSpec.Memories(5, 2, 0), SourceSpec.EverythingRandom,
            SourceSpec.SmartQuery("beach"), SourceSpec.Custom(city = "Springfield"),
            // Ids with no stored names: there is nothing for a people list to bring up to date.
            SourceSpec.People(listOf(ann), emptyList()),
        ).mapIndexed { i, spec -> cycle("c$i", spec) }
        assertEquals(CycleNames.Needed(albums = false, people = false), CycleNames.needed(nameless))
        assertEquals(CycleNames.Needed(albums = false, people = false), CycleNames.needed(emptyList()))
    }

    @Test fun `what is said to be needed is all that a refresh can use`() {
        for (spec in listOf(trips, couple, beach, custom, SourceSpec.Favorites, SourceSpec.People(listOf(ann), emptyList()))) {
            val cycles = listOf(cycle("c1", spec))
            val need = CycleNames.needed(cycles)
            val albums = mapOf(album to "Journeys")
            val people = mapOf(ann to "Anna", bob to "Robert")
            assertEquals(
                CycleNames.refreshed(cycles, albums, people, today),
                CycleNames.refreshed(cycles, if (need.albums) albums else none, if (need.people) people else none, today),
                spec.toString(),
            )
        }
    }
}
