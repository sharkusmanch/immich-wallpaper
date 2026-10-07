package dev.immichwall.cache

import dev.immichwall.api.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheManifestCompatTest {
    /** A manifest written by upstream v1.0.0 must still load: old installs and old entries carry flat file names. */
    @Test fun `v1 manifest decodes with flat file names intact`() {
        val json = """{"schemaVersion":1,"sourceKey":"abc|q:prefer","cropWidth":1080,"cropHeight":2400,
            "entries":[{"assetId":"3f2b8c1e-5d4a-4b7e-9c0f-1a2b3c4d5e6f","fileName":"3f2b8c1e-5d4a-4b7e-9c0f-1a2b3c4d5e6f.jpg",
            "addedAt":5,"width":1080,"height":2400,"sourceSize":"original","sourceKey":"abc|q:prefer","someFutureField":true}],
            "lastSyncAt":9,"lastSyncResult":"ok"}"""
        val m = ApiJson.json.decodeFromString(CacheManifest.serializer(), json)
        assertEquals("3f2b8c1e-5d4a-4b7e-9c0f-1a2b3c4d5e6f.jpg", m.entries.single().fileName)
        assertEquals(0, m.entries.single().shownCount)
        assertEquals(m, ApiJson.json.decodeFromString(CacheManifest.serializer(), ApiJson.json.encodeToString(CacheManifest.serializer(), m)))
    }
}
