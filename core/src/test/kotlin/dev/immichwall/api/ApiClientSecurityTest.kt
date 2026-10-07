package dev.immichwall.api

import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientSecurityTest {
    private val assetId = "3f2b8c1e-5d4a-4b7e-9c0f-1a2b3c4d5e6f"
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var dir: File

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
        dir = Files.createTempDirectory("apitest").toFile()
    }

    @AfterTest fun tearDown() {
        server.shutdown()
        other.shutdown()
        dir.deleteRecursively()
    }

    private fun client(limits: ImmichApiClient.Limits = ImmichApiClient.Limits()) =
        ImmichApiClient({ server.url("/").toString() }, { "secret-key" }, limits)

    @Test fun `key is sent to the configured origin`() {
        server.enqueue(MockResponse().setBody("[]"))
        client().getAlbums()
        assertEquals("secret-key", server.takeRequest().getHeader("x-api-key"))
    }

    @Test fun `same-origin redirect is followed and keeps the key`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10))))
        val dest = File(dir, "a.raw")
        val result = client().downloadAssetImage(assetId, dest)
        assertEquals("original", result.tier)
        assertEquals(10L, dest.length())
        server.takeRequest()
        val followed = server.takeRequest()
        assertEquals("/elsewhere", followed.path)
        assertEquals("secret-key", followed.getHeader("x-api-key"))
    }

    @Test fun `cross-origin redirect is refused and the other host never sees the key`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal").toString()))
        other.enqueue(MockResponse().setBody("[]"))
        assertFailsWith<IOException> { client().getAlbums() }
        assertEquals(0, other.requestCount)
    }

    @Test fun `redirected POST is not followed`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal").toString()))
        other.enqueue(MockResponse().setBody("[]"))
        val e = assertFailsWith<ApiException> { client().searchRandom(JsonObject(emptyMap()), 5) }
        assertEquals(307, e.code)
        assertEquals(0, other.requestCount)
    }

    @Test fun `redirect loop on one origin stops`() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertFailsWith<IOException> { client().getAlbums() }
        assertTrue(server.requestCount <= 4)
    }

    @Test fun `oversized download fails as 413 on every rung and leaves no file`() {
        repeat(3) { server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048)))) }
        val dest = File(dir, "big.raw")
        val e = assertFailsWith<ApiException> {
            client(ImmichApiClient.Limits(maxDownloadBytes = 1024)).downloadAssetImage(assetId, dest)
        }
        assertEquals(413, e.code)
        assertEquals(3, server.requestCount)
        assertFalse(dest.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `oversized download with no declared length is cut off mid-stream`() {
        repeat(3) { server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(2048)), 512)) }
        val dest = File(dir, "chunked.raw")
        val e = assertFailsWith<ApiException> {
            client(ImmichApiClient.Limits(maxDownloadBytes = 1024)).downloadAssetImage(assetId, dest)
        }
        assertEquals(413, e.code)
        assertEquals(3, server.requestCount)
        assertFalse(dest.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `oversized original falls back to a smaller rung`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100))))
        val dest = File(dir, "ok.raw")
        val result = client(ImmichApiClient.Limits(maxDownloadBytes = 1024)).downloadAssetImage(assetId, dest)
        assertEquals("fullsize", result.tier)
        assertEquals(100L, dest.length())
    }

    @Test fun `a download that outlives the call deadline falls back to a smaller rung`() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(64 * 1024)))
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100))))
        val dest = File(dir, "slow.raw")
        val result = client(ImmichApiClient.Limits(callTimeoutMillis = 500)).downloadAssetImage(assetId, dest)
        assertEquals("fullsize", result.tier)
        assertEquals(100L, dest.length())
    }

    @Test fun `a rung that redirects to another origin is skipped, not fatal`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/cdn").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100))))
        val dest = File(dir, "moved.raw")
        val result = client().downloadAssetImage(assetId, dest)
        assertEquals("fullsize", result.tier)
        assertEquals(0, other.requestCount)
    }

    @Test fun `oversized json is rejected`() {
        server.enqueue(MockResponse().setBody("[" + " ".repeat(500) + "]"))
        assertFailsWith<IOException> { client(ImmichApiClient.Limits(maxJsonBytes = 64)).getAlbums() }
    }

    @Test fun `asset id that is not a uuid is refused before any request`() {
        val dest = File(dir, "x.raw")
        assertFailsWith<ApiException> { client().downloadAssetImage("../../../etc/passwd", dest) }
        assertFailsWith<ApiException> { client().downloadAssetThumbnail("a/b", dest) }
        assertFailsWith<ApiException> { client().getFaces("") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `error body is only sampled`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(100_000)))
        val e = assertFailsWith<ApiException> { client().getAlbums() }
        assertEquals(500, e.code)
        assertTrue(e.message!!.length < 400)
    }
}
