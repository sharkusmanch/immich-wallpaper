package dev.immichwall.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceSizeTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val ben = "00000000-0000-4000-8000-000000000002"
    private val album = "00000000-0000-4000-8000-0000000000a1"

    // Unknown: a short answer proves nothing.

    @Test fun `smart query is unknown even when short`() {
        assertNull(knownSourceSize(SourceSpec.SmartQuery(query = "beach"), found = 7, requested = 50))
    }

    @Test fun `smart query with people is unknown even when short`() {
        val spec = SourceSpec.SmartQuery(query = "beach", personIds = listOf(ann))
        assertNull(knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `custom with a query is unknown`() {
        assertNull(knownSourceSize(SourceSpec.Custom(query = "beach"), found = 7, requested = 50))
    }

    @Test fun `custom with a query and an album is unknown`() {
        val spec = SourceSpec.Custom(query = "beach", albumId = album, albumName = "Trip")
        assertNull(knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `people any of several is unknown`() {
        val spec = SourceSpec.People(ids = listOf(ann, ben), names = listOf("Ann", "Ben"))
        assertNull(knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `custom any of several people without a query is unknown`() {
        val spec = SourceSpec.Custom(personIds = listOf(ann, ben), requireAll = false)
        assertNull(knownSourceSize(spec, found = 7, requested = 50))
    }

    // Known: the server ran dry, so what it returned is everything.

    @Test fun `album records its size`() {
        val spec = SourceSpec.Album(albumId = album, albumName = "Trip")
        assertEquals(7, knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `a single person records its size`() {
        val spec = SourceSpec.People(ids = listOf(ann), names = listOf("Ann"))
        assertEquals(7, knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `people require all records its size`() {
        val spec = SourceSpec.People(ids = listOf(ann, ben), names = listOf("Ann", "Ben"), requireAll = true)
        assertEquals(7, knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `custom without a query and one person records its size`() {
        val spec = SourceSpec.Custom(personIds = listOf(ann))
        assertEquals(7, knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `custom require all several people without a query records its size`() {
        val spec = SourceSpec.Custom(personIds = listOf(ann, ben), requireAll = true)
        assertEquals(7, knownSourceSize(spec, found = 7, requested = 50))
    }

    @Test fun `an answer that fills the request is unknown for any source`() {
        val spec = SourceSpec.Album(albumId = album, albumName = "Trip")
        assertNull(knownSourceSize(spec, found = 50, requested = 50))
        assertNull(knownSourceSize(spec, found = 60, requested = 50))
    }
}
