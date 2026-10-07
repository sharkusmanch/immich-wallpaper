package dev.immichwall.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachePolicyTest {
    private val fall = "aaaaaaaaaaaa1111|q:prefer"
    private val xmas = "bbbbbbbbbbbb2222|q:prefer"

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun e(n: Int, key: String, added: Long = n.toLong(), lastShown: Long = 0, shown: Int = 0) =
        CacheEntry(
            assetId = id(n), fileName = CachePolicy.readyFileName(key, id(n)), addedAt = added,
            width = 100, height = 200, sourceKey = key, lastShownAt = lastShown, shownCount = shown,
        )

    @Test fun `rotation only draws from the active cycle`() {
        val entries = listOf(e(1, fall), e(2, fall), e(3, xmas, lastShown = 50, shown = 1))
        assertEquals(id(3), CachePolicy.nextToShow(entries, current = entries[0], activeKey = xmas)?.assetId)
    }

    @Test fun `never-shown entries win, oldest first`() {
        val entries = listOf(e(1, fall, lastShown = 10, shown = 1), e(2, fall, added = 5), e(3, fall, added = 4))
        assertEquals(id(3), CachePolicy.nextToShow(entries, current = entries[0], activeKey = fall)?.assetId)
    }

    @Test fun `least-recently-shown wins among shown photos, and never-shown beats them all`() {
        val current = e(1, fall, lastShown = 500, shown = 5)
        val justShown = e(2, fall, lastShown = 400, shown = 1)
        val longAgo = e(3, fall, lastShown = 100, shown = 1)
        val never = e(4, fall)
        assertEquals(id(4), CachePolicy.nextToShow(listOf(current, justShown, longAgo, never), current, fall)?.assetId)
        assertEquals(id(3), CachePolicy.nextToShow(listOf(current, justShown, longAgo), current, fall)?.assetId)
        assertEquals(id(2), CachePolicy.nextToShow(listOf(current, justShown), current, fall)?.assetId)
    }

    @Test fun `the photo on screen is not picked again`() {
        val entries = listOf(e(1, fall), e(2, fall, lastShown = 99, shown = 3))
        assertEquals(id(2), CachePolicy.nextToShow(entries, current = entries[0], activeKey = fall)?.assetId)
    }

    @Test fun `a lone active photo already on screen yields nothing`() {
        val entries = listOf(e(1, xmas), e(2, fall))
        assertNull(CachePolicy.nextToShow(entries, current = entries[0], activeKey = xmas))
    }

    @Test fun `with nothing cached for the active cycle the old set keeps rotating`() {
        val entries = listOf(e(1, fall, lastShown = 10, shown = 1), e(2, fall))
        assertEquals(id(2), CachePolicy.nextToShow(entries, current = entries[0], activeKey = xmas)?.assetId)
    }

    @Test fun `a prefetched cycle is never shown before it is active`() {
        val newYear = "cccccccccccc3333|q:prefer"
        val onScreen = e(1, fall, lastShown = 500, shown = 3)
        val seen = e(2, fall, lastShown = 400, shown = 2)
        // Never shown, so it would sort ahead of everything the previous set has left.
        val prefetched = listOf(e(3, newYear), e(4, newYear))
        // xmas is active and has nothing cached yet: the previous set keeps rotating.
        val next = CachePolicy.nextToShow(listOf(onScreen, seen) + prefetched, current = onScreen, activeKey = xmas)
        assertEquals(id(2), next?.assetId)
        assertEquals(fall, next?.sourceKey)
        // With only the photo on screen left in the previous set, that photo stays up.
        assertNull(CachePolicy.nextToShow(listOf(onScreen) + prefetched, current = onScreen, activeKey = xmas))
    }

    @Test fun `with nothing on screen and nothing active any cached photo will do`() {
        val entries = listOf(e(1, fall, lastShown = 10, shown = 1), e(2, fall))
        assertEquals(id(2), CachePolicy.nextToShow(entries, current = null, activeKey = xmas)?.assetId)
        // The photo on screen is of a cycle the cache no longer holds at all.
        val gone = e(9, "cccccccccccc3333|q:prefer")
        assertEquals(id(2), CachePolicy.nextToShow(entries, current = gone, activeKey = xmas)?.assetId)
    }

    @Test fun `same asset cached for two cycles is two entries`() {
        val a = e(1, fall)
        val b = e(1, xmas)
        assertEquals(id(1), CachePolicy.nextToShow(listOf(a, b), current = a, activeKey = xmas)?.assetId)
        assertEquals(xmas, CachePolicy.nextToShow(listOf(a, b), current = a, activeKey = xmas)?.sourceKey)
    }

    @Test fun `empty cache yields nothing`() {
        assertNull(CachePolicy.nextToShow(emptyList(), null, fall))
    }

    @Test fun `nothing is purged until the active cycle has a photo`() {
        val entries = listOf(e(1, fall), e(2, fall))
        assertEquals(emptyList(), CachePolicy.purgeable(entries, retainedKeys = setOf(xmas), activeKey = xmas))
    }

    @Test fun `once the active cycle has a photo everything outside the retained set goes`() {
        val entries = listOf(e(1, fall), e(2, fall), e(3, xmas))
        assertEquals(listOf(id(1), id(2)), CachePolicy.purgeable(entries, setOf(xmas), xmas).map { it.assetId })
    }

    @Test fun `retained non-active cycle is kept`() {
        val entries = listOf(e(1, fall), e(3, xmas))
        assertEquals(emptyList(), CachePolicy.purgeable(entries, setOf(fall, xmas), fall))
    }

    @Test fun `eviction is per cycle, most-shown first, and respects the floor`() {
        val entries = listOf(e(1, fall, shown = 5), e(2, fall, shown = 1), e(3, fall), e(4, xmas, shown = 9))
        assertEquals(listOf(id(1)), CachePolicy.evictable(entries, fall, target = 2, floor = 0).map { it.assetId })
        assertEquals(emptyList(), CachePolicy.evictable(entries, fall, target = 2, floor = 20))
        assertEquals(emptyList(), CachePolicy.evictable(entries, xmas, target = 2, floor = 0))
    }

    @Test fun `newest picks the freshest entry of the active cycle`() {
        val entries = listOf(e(1, fall, added = 100), e(2, xmas, added = 5), e(3, xmas, added = 7))
        assertEquals(id(3), CachePolicy.newest(entries, xmas)?.assetId)
        assertNull(CachePolicy.newest(entries, "cccccccccccc"))
    }

    @Test fun `ready files live in one directory per cycle key`() {
        val name = CachePolicy.readyFileName(fall, id(1))
        assertEquals(true, Regex("[0-9a-f]{16}/${id(1)}\\.jpg").matches(name), name)
        assertEquals(name, CachePolicy.readyFileName(fall, id(1)))
        assertEquals("legacy/${id(1)}.jpg", CachePolicy.readyFileName("", id(1)))
    }

    @Test fun `two cycles never share a file for the same photo`() {
        assertEquals(false, CachePolicy.readyFileName(fall, id(1)) == CachePolicy.readyFileName(xmas, id(1)))
    }

    @Test fun `keys that differ only in the quality tag do not share files`() {
        val prefer = "aaaaaaaaaaaa1111|q:prefer"
        val require = "aaaaaaaaaaaa1111|q:require"
        assertEquals(false, CachePolicy.readyFileName(prefer, id(1)) == CachePolicy.readyFileName(require, id(1)))
    }

    @Test fun `ready file name refuses ids that are not uuids`() {
        assertFailsWith<IllegalArgumentException> { CachePolicy.readyFileName(fall, "../../escape") }
    }

    @Test fun `syncs stop adding photos when storage is nearly full`() {
        assertEquals(false, CachePolicy.hasRoom(CachePolicy.MIN_FREE_BYTES - 1))
        assertEquals(true, CachePolicy.hasRoom(CachePolicy.MIN_FREE_BYTES))
    }

    @Test fun `low-cache warning fires below the floor when the source size is unknown`() {
        assertTrue(CachePolicy.warnsLowCache(readyCount = 13, floor = 20, knownSourceSize = null))
    }

    @Test fun `low-cache warning stays quiet when the source is known to be smaller than the floor`() {
        assertFalse(CachePolicy.warnsLowCache(readyCount = 13, floor = 20, knownSourceSize = 17))
    }

    @Test fun `low-cache warning fires when the source is known to reach the floor`() {
        assertTrue(CachePolicy.warnsLowCache(readyCount = 13, floor = 20, knownSourceSize = 20))
        assertTrue(CachePolicy.warnsLowCache(readyCount = 13, floor = 20, knownSourceSize = 400))
    }

    @Test fun `low-cache warning never fires at or above the floor`() {
        assertFalse(CachePolicy.warnsLowCache(readyCount = 20, floor = 20, knownSourceSize = null))
        assertFalse(CachePolicy.warnsLowCache(readyCount = 150, floor = 20, knownSourceSize = 400))
    }

    @Test fun `low-cache warning leaves the empty cache to its own check`() {
        assertFalse(CachePolicy.warnsLowCache(readyCount = 0, floor = 20, knownSourceSize = null))
        assertFalse(CachePolicy.warnsLowCache(readyCount = 0, floor = 20, knownSourceSize = 17))
    }

    @Test fun `a search that returned fewer photos than asked for has found the whole source`() {
        assertEquals(17, CachePolicy.sourceSizeIfExhausted(found = 17, requested = 60))
        assertNull(CachePolicy.sourceSizeIfExhausted(found = 60, requested = 60))
    }
}
