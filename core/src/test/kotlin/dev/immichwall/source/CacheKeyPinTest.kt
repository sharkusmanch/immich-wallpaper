package dev.immichwall.source

import dev.immichwall.api.ApiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The WHOLE cache partition key ([SavedCycle.cacheKey]) as v1.1.0 composed it: the spec's
 * hash, then `|q:` and the cycle's people preference, or `|q:off` with the quality filter
 * off. Every cached photo carries this string and is shown, kept or purged by comparing it,
 * so each expected value is written out in full: a change to the separator, the tag or the
 * order fails here instead of emptying a cache. The hashes are those of [StableKeyPinTest].
 */
class CacheKeyPinTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val today = "2026-10-07"

    private val trips = SourceSpec.Album(albumId = album, albumName = "Trips")
    private val journeys = SourceSpec.Album(albumId = album, albumName = "Journeys")

    /** What `Album(album, "Trips")` hashed to in v1.1.0. */
    private val tripsKey = "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94"

    private fun cycle(spec: SourceSpec, preference: String, frozenKey: String = "") =
        SavedCycle(id = "c1", name = spec.summaryLabel(), spec = spec, peoplePreference = preference, frozenKey = frozenKey)

    // --- a cycle without a frozen key: exactly v1.1.0's key ---

    @Test fun `with the quality filter on the key ends in the cycle's people preference`() {
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:off",
            cycle(trips, "off").cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:prefer",
            cycle(trips, "prefer").cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:require",
            cycle(trips, "require").cacheKey(qualityFilterEnabled = true, today = today),
        )
    }

    @Test fun `with the quality filter off the key ends in q-off whatever the preference`() {
        for (preference in listOf("off", "prefer", "require")) {
            assertEquals(
                "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:off",
                cycle(trips, preference).cacheKey(qualityFilterEnabled = false, today = today),
                preference,
            )
        }
    }

    @Test fun `every source variant has the whole key v1_1_0 gave it`() {
        val pinned: List<Pair<SourceSpec, String>> = listOf(
            SourceSpec.People(ids = listOf(ann, bob), names = listOf("Ann", "Bob"), requireAll = true) to
                "dc6b7b36f06a273903fd68b05e69554569d230b9571855627e8913806255df0e|q:prefer",
            trips to
                "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:prefer",
            SourceSpec.SmartQuery(query = "beach", personIds = listOf(ann), personNames = listOf("Ann")) to
                "804fe3cca84c3195ebccc9538769bf9fd21f0467680c4789780fb2ee50f7dc72|q:prefer",
            SourceSpec.Location(city = "Springfield") to
                "cf9be215559d6390ca1b48f79e75e6ffef812a89214630b986da06f15f8bd91c|q:prefer",
            SourceSpec.Favorites to
                "5bc5bb1730b49f623712861e29b3759d317a3cf0fed431a131050c501a7795c9|q:prefer",
            SourceSpec.Memories(windowDays = 5, yearsAgoMin = 2, yearsAgoMax = 0) to
                "9c205495249e9478b6e723f8db6274cf011d6d0c71d9b32446220540b7012c94|q:prefer",
            SourceSpec.EverythingRandom to
                "92fe3e4fe3101b6f38323a974731551e8d2c892c9c01e10381dc89a2438dc806|q:prefer",
            SourceSpec.Custom(
                personIds = listOf(ann), personNames = listOf("Ann"), albumId = album, albumName = "Trips",
                query = "dog", city = "Springfield", takenAfter = "2020-01-01",
            ) to "f110b722d6e97471ced050960a95255ddd166d971edd4edf3d68068e073ede0c|q:prefer",
        )
        for ((spec, key) in pinned) {
            assertEquals(key, cycle(spec, "prefer").cacheKey(qualityFilterEnabled = true, today = today), spec.toString())
        }
    }

    @Test fun `a cycle as v1_1_0 stored it has the key v1_1_0 gave it`() {
        // No frozenKey field; the second has no peoplePreference either, as a cycle saved before that existed.
        val stored = """[
            {"id":"c1","name":"Album: Trips","spec":{"mode":"album","albumId":"$album","albumName":"Trips"},"peoplePreference":"require"},
            {"id":"c2","name":"Album: Trips","spec":{"mode":"album","albumId":"$album","albumName":"Trips"}}
        ]"""
        val (withPreference, without) = ApiJson.json.decodeFromString(ListSerializer(SavedCycle.serializer()), stored)
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:require",
            withPreference.cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:prefer",
            without.cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:off",
            withPreference.cacheKey(qualityFilterEnabled = false, today = today),
        )
    }

    // --- a cycle with a frozen key: still the key it had under v1.1.0 ---

    @Test fun `a renamed cycle with a frozen key has the same whole keys as before its rename`() {
        fun renamed(preference: String) = cycle(journeys, preference, frozenKey = tripsKey)
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:off",
            renamed("off").cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:prefer",
            renamed("prefer").cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:require",
            renamed("require").cacheKey(qualityFilterEnabled = true, today = today),
        )
        for (preference in listOf("off", "prefer", "require")) {
            assertEquals(
                "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:off",
                renamed(preference).cacheKey(qualityFilterEnabled = false, today = today),
                preference,
            )
        }
    }

    @Test fun `the whole key is the same before and after a sync refreshes the names`() {
        for (preference in listOf("off", "prefer", "require")) {
            val before = cycle(trips, preference)
            val after = assertNotNull(CycleNames.refreshed(listOf(before), mapOf(album to "Journeys"), emptyMap(), today)).single()
            assertEquals(journeys, after.spec)
            for (filter in listOf(true, false)) {
                assertEquals(before.cacheKey(filter, today), after.cacheKey(filter, today), "$preference $filter")
            }
            assertEquals(
                "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:$preference",
                after.cacheKey(qualityFilterEnabled = true, today = today),
            )
        }
    }

    @Test fun `the whole key is the same before and after the wizard saves newer names`() {
        for (previous in listOf(cycle(trips, "require"), cycle(journeys, "require", frozenKey = tripsKey))) {
            val saved = CycleSave.of(listOf(previous), editingId = "c1", newId = "new", spec = SourceSpec.Album(album, "Voyages"), peoplePreference = "require", today = today).cycle
            assertEquals(
                "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94|q:require",
                saved.cacheKey(qualityFilterEnabled = true, today = today),
            )
            assertEquals(previous.cacheKey(false, today), saved.cacheKey(false, today))
        }
    }

    // --- the parts ---

    @Test fun `the key is the key base, a bar, and the quality tag`() {
        val frozen = cycle(journeys, "require", frozenKey = "a".repeat(64))
        assertEquals("a".repeat(64) + "|q:require", frozen.cacheKey(qualityFilterEnabled = true, today = today))
        assertEquals(frozen.keyBase(today) + "|q:off", frozen.cacheKey(qualityFilterEnabled = false, today = today))
    }

    @Test fun `a memories key follows the date and nothing else does`() {
        val memories = cycle(SourceSpec.Memories(windowDays = 5, yearsAgoMin = 2, yearsAgoMax = 0), "prefer")
        assertEquals(
            "9c205495249e9478b6e723f8db6274cf011d6d0c71d9b32446220540b7012c94|q:prefer",
            memories.cacheKey(qualityFilterEnabled = true, today = today),
        )
        assertEquals(false, memories.cacheKey(true, "2026-10-08") == memories.cacheKey(true, today))
        assertEquals(cycle(trips, "prefer").cacheKey(true, today), cycle(trips, "prefer").cacheKey(true, "2026-10-08"))
    }
}
