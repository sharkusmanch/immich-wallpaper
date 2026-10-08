package dev.immichwall.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SourceSpec.stableKey] as v1.1.0 computed it, for every source variant. These values are
 * the first part of cache keys that are on devices now: a photo cached under one of them is
 * only recognised while the same spec still hashes to it. Each literal is the SHA-256 of the
 * spec's persisted JSON (Memories: plus `|date`), worked out outside this code base, so a
 * change to the hashing or to how a spec serializes fails here instead of emptying a cache.
 */
class StableKeyPinTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val today = "2026-10-07"

    private val pinned: List<Pair<SourceSpec, String>> = listOf(
        SourceSpec.People(ids = listOf(ann, bob), names = listOf("Ann", "Bob"), requireAll = true) to
            "dc6b7b36f06a273903fd68b05e69554569d230b9571855627e8913806255df0e",
        SourceSpec.Album(albumId = album, albumName = "Trips") to
            "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94",
        SourceSpec.SmartQuery(query = "beach", personIds = listOf(ann), personNames = listOf("Ann")) to
            "804fe3cca84c3195ebccc9538769bf9fd21f0467680c4789780fb2ee50f7dc72",
        SourceSpec.Location(city = "Springfield") to
            "cf9be215559d6390ca1b48f79e75e6ffef812a89214630b986da06f15f8bd91c",
        SourceSpec.Favorites to
            "5bc5bb1730b49f623712861e29b3759d317a3cf0fed431a131050c501a7795c9",
        SourceSpec.Memories(windowDays = 5, yearsAgoMin = 2, yearsAgoMax = 0) to
            "9c205495249e9478b6e723f8db6274cf011d6d0c71d9b32446220540b7012c94",
        SourceSpec.EverythingRandom to
            "92fe3e4fe3101b6f38323a974731551e8d2c892c9c01e10381dc89a2438dc806",
        SourceSpec.Custom(
            personIds = listOf(ann), personNames = listOf("Ann"), albumId = album, albumName = "Trips",
            query = "dog", city = "Springfield", takenAfter = "2020-01-01",
        ) to "f110b722d6e97471ced050960a95255ddd166d971edd4edf3d68068e073ede0c",
    )

    @Test fun `every source variant hashes to the key v1_1_0 gave it`() {
        for ((spec, key) in pinned) assertEquals(key, spec.stableKey(today), spec.toString())
    }

    @Test fun `only a memories key follows the date`() {
        for ((spec, key) in pinned) {
            val tomorrow = spec.stableKey("2026-10-08")
            if (spec is SourceSpec.Memories) assertEquals(false, tomorrow == key) else assertEquals(key, tomorrow, spec.toString())
        }
    }
}
