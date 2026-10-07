package dev.immichwall.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Synchronous Immich v3 API client.
 *
 * - Non-2xx responses throw [ApiException] (with [ApiException.scopeHint] set on 403).
 * - Transport failures (DNS, connect, timeout, TLS) surface as [IOException] from OkHttp.
 * - Every request carries the `x-api-key` header EXCEPT [getServerVersion], which the
 *   server exposes unauthenticated (used as a pure reachability probe).
 *
 * Callers are responsible for dispatching off the main thread.
 */
class ImmichApiClient(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val limits: Limits = Limits(),
) {

    /** Hard caps on what a response may make this app read; a server (or anything posing as one) cannot exceed them. */
    data class Limits(
        val maxDownloadBytes: Long = 100L * 1024 * 1024,
        val maxJsonBytes: Long = 16L * 1024 * 1024,
        val maxThumbnailBytes: Long = 5L * 1024 * 1024,
        /** Deadline for one whole call, body included. */
        val callTimeoutMillis: Long = 180_000,
    )

    companion object {
        // Exact strings from the Immich v3.0.3 OpenAPI `Permission` enum
        // (per-endpoint `x-immich-permission` annotations).
        const val SCOPE_PERSON_READ = "person.read"
        const val SCOPE_ALBUM_READ = "album.read"
        const val SCOPE_ASSET_READ = "asset.read"
        const val SCOPE_ASSET_VIEW = "asset.view"
        const val SCOPE_ASSET_DOWNLOAD = "asset.download"
        const val SCOPE_FACE_READ = "face.read"
        const val SCOPE_MEMORY_READ = "memory.read"
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // Bounds a body that keeps trickling: read timeouts only bound silence.
        .callTimeout(limits.callTimeoutMillis, TimeUnit.MILLISECONDS)
        // OkHttp would re-send x-api-key to any host a redirect names; see the interceptor.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(SameOriginRedirectInterceptor())
        .build()

    // ---------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------

    /** GET /api/server/version — NO auth header; usable as a reachability probe. */
    fun getServerVersion(): ServerVersionDto {
        val request = Request.Builder().url(urlFor("/api/server/version")).get().build()
        return executeJson(request, "GET /api/server/version", scope = null)
    }

    fun getPeople(page: Int, size: Int = 500, withHidden: Boolean = false): PeopleResponseDto {
        val url = urlFor(
            "/api/people",
            "page" to page.toString(),
            "size" to size.toString(),
            "withHidden" to withHidden.toString(),
        )
        return executeJson(authed(url).get().build(), "GET /api/people", SCOPE_PERSON_READ)
    }

    fun getPersonThumbnail(personId: String): ByteArray {
        AssetIds.require(personId)
        val url = urlFor("/api/people/$personId/thumbnail")
        val endpoint = "GET /api/people/{id}/thumbnail"
        client.newCall(authed(url).get().build()).execute().use { response ->
            ensureSuccess(response, endpoint, SCOPE_PERSON_READ)
            val body = response.body ?: throw IOException("$endpoint returned an empty body")
            return readBounded(body, limits.maxThumbnailBytes, endpoint)
        }
    }

    fun getAlbums(): List<AlbumDto> {
        val url = urlFor("/api/albums")
        return executeJson(authed(url).get().build(), "GET /api/albums", SCOPE_ALBUM_READ)
    }

    /** GET /api/search/cities — one representative asset per city (city in exifInfo.city). */
    fun getCities(): List<AssetDto> {
        val url = urlFor("/api/search/cities")
        return executeJson(authed(url).get().build(), "GET /api/search/cities", SCOPE_ASSET_READ)
    }

    /** GET /api/memories?for=YYYY-MM-DD — the server rejects full ISO datetimes with 400. */
    fun getMemories(forDate: String): List<MemoryDto> {
        val url = urlFor("/api/memories", "for" to forDate)
        return executeJson(authed(url).get().build(), "GET /api/memories", SCOPE_MEMORY_READ)
    }

    /**
     * GET /api/assets/{id} — full asset detail incl. complete exifInfo (search responses
     * omit it). Feeds the quality scorer: camera make/model, aperture, ISO, lens, rating.
     */
    fun getAsset(assetId: String): AssetDto {
        AssetIds.require(assetId)
        val url = urlFor("/api/assets/$assetId")
        return executeJson(authed(url).get().build(), "GET /api/assets/{id}", SCOPE_ASSET_READ)
    }

    /**
     * POST /api/search/random → BARE JSON array of assets.
     * Caller-supplied [filters] are passed through verbatim (e.g. personIds, albumIds,
     * city, isFavorite, type, takenAfter/Before, visibility — all present on
     * RandomSearchDto in v3.0.3); [size] is merged in last.
     */
    fun searchRandom(filters: JsonObject, size: Int): List<AssetDto> {
        val body = buildJsonObject {
            filters.forEach { (key, value) -> put(key, value) }
            put("size", size)
        }
        val request = authed(urlFor("/api/search/random"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJson(request, "POST /api/search/random", SCOPE_ASSET_READ)
    }

    /**
     * POST /api/search/metadata. Caller-supplied [filters] pass through verbatim
     * (MetadataSearchDto in v3.0.3 supports personIds, albumIds, city, isFavorite,
     * type, takenAfter/Before, visibility, withPeople, withExif, …); [page] and
     * [size] are merged in last.
     */
    fun searchMetadata(filters: JsonObject, page: Int, size: Int): SearchResponseDto {
        val body = buildJsonObject {
            filters.forEach { (key, value) -> put(key, value) }
            put("page", page)
            put("size", size)
        }
        val request = authed(urlFor("/api/search/metadata"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJson(request, "POST /api/search/metadata", SCOPE_ASSET_READ)
    }

    /**
     * POST /api/search/smart — CLIP semantic search, composable with personIds
     * (AND-semantics across people). Results are relevance-ranked; callers shuffle.
     */
    fun searchSmart(query: String, personIds: List<String>, size: Int): SearchResponseDto {
        val body = buildJsonObject {
            put("query", query)
            if (personIds.isNotEmpty()) {
                put("personIds", JsonArray(personIds.map { JsonPrimitive(it) }))
            }
            put("size", size)
        }
        val request = authed(urlFor("/api/search/smart"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJson(request, "POST /api/search/smart", SCOPE_ASSET_READ)
    }

    /** GET /api/faces?id=<assetId> — box coords are pixels in imageWidth×imageHeight space. */
    /**
     * POST /api/search/smart with arbitrary extra filters merged into the body (personIds,
     * city, isFavorite, takenAfter/Before — fields SmartSearchDto shares with metadata
     * search). Used by the custom-filter source.
     */
    fun searchSmartFiltered(query: String, extraFilters: JsonObject, size: Int): SearchResponseDto {
        val body = buildJsonObject {
            extraFilters.forEach { (key, value) -> put(key, value) }
            put("query", query)
            put("size", size)
        }
        val request = authed(urlFor("/api/search/smart"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJson(request, "POST /api/search/smart", SCOPE_ASSET_READ)
    }

    fun getFaces(assetId: String): List<AssetFaceDto> {
        AssetIds.require(assetId)
        val url = urlFor("/api/faces", "id" to assetId)
        return executeJson(authed(url).get().build(), "GET /api/faces", SCOPE_FACE_READ)
    }

    /**
     * Downloads the best available wallpaper-sized image for [assetId] into [dest], walking a
     * quality ladder: `thumbnail?size=fullsize` → `/original` → `thumbnail?size=preview`.
     * A rung answering 400/403/404 (fullsize generation disabled, missing `asset.download`
     * scope, endpoint variance) falls through to the next; other errors throw [ApiException].
     * Android decodes HEIC/JPEG/WebP originals natively (API 28+), so originals are safe.
     * Streams to `<dest>.tmp`, fsyncs, then atomically renames.
     *
     * @return the [DownloadedImage] describing the Content-Type and the quality tier
     *   (`original`/`fullsize`/`preview`) that actually served the bytes.
     */
    fun downloadAssetImage(assetId: String, dest: File): DownloadedImage {
        AssetIds.require(assetId)
        // /original is the sharpest, deterministic source (full-res HEIC/JPEG); fullsize commonly
        // 302-redirects to a lower tier and preview caps at ~1920px, so both are only fallbacks.
        val ladder = listOf(
            DownloadRung("original", urlFor("/api/assets/$assetId/original"),
                "GET /api/assets/{id}/original", SCOPE_ASSET_DOWNLOAD),
            DownloadRung("fullsize", urlFor("/api/assets/$assetId/thumbnail", "size" to "fullsize"),
                "GET /api/assets/{id}/thumbnail?size=fullsize", SCOPE_ASSET_VIEW),
            DownloadRung("preview", urlFor("/api/assets/$assetId/thumbnail", "size" to "preview"),
                "GET /api/assets/{id}/thumbnail?size=preview", SCOPE_ASSET_VIEW),
        )
        var lastError: ApiException? = null
        for (rung in ladder) {
            try {
                val contentType = downloadToFile(rung.url, rung.endpoint, rung.scope, dest)
                return DownloadedImage(contentType, rung.tier)
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 403 || e.code == 404 || rungUnusable(e)) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: ApiException(0, "no download rung succeeded for $assetId")
    }

    /** Result of [downloadAssetImage]: what arrived and which quality tier served it. */
    data class DownloadedImage(val contentType: String, val tier: String)

    private data class DownloadRung(
        val tier: String,
        val url: okhttp3.HttpUrl,
        val endpoint: String,
        val scope: String,
    )

    /**
     * Downloads the small grid-sized rendition of [assetId] into [dest]:
     * `thumbnail?size=thumbnail` (tens of KB) with a single fallback to
     * `thumbnail?size=preview` on 400/404 (endpoint/size variance across server
     * versions). Same streaming/atomic-rename behavior as [downloadAssetImage];
     * meant for preview grids, never for wallpaper-quality downloads.
     *
     * @return the response Content-Type (e.g. `image/webp`, `image/jpeg`).
     */
    fun downloadAssetThumbnail(assetId: String, dest: File): String {
        AssetIds.require(assetId)
        val ladder = listOf(
            Triple(urlFor("/api/assets/$assetId/thumbnail", "size" to "thumbnail"),
                "GET /api/assets/{id}/thumbnail?size=thumbnail", SCOPE_ASSET_VIEW),
            Triple(urlFor("/api/assets/$assetId/thumbnail", "size" to "preview"),
                "GET /api/assets/{id}/thumbnail?size=preview", SCOPE_ASSET_VIEW),
        )
        var lastError: ApiException? = null
        for ((url, endpoint, scope) in ladder) {
            try {
                return downloadToFile(url, endpoint, scope, dest)
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 404 || rungUnusable(e)) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: ApiException(0, "no thumbnail rung succeeded for $assetId")
    }

    /**
     * One rung of a download ladder. Failures that concern this rung only — too big, too
     * slow for the call deadline, or redirected to another origin — become [ApiException]s
     * the ladder falls through on, so one awkward asset never aborts the whole sync the way
     * a dead link (any other [IOException]) rightly does.
     */
    private fun downloadToFile(url: HttpUrl, endpoint: String, scope: String, dest: File): String =
        try {
            fetchToFile(url, endpoint, scope, dest)
        } catch (e: RedirectRefusedException) {
            throw ApiException(421, "$endpoint: ${e.message}")
        } catch (e: java.io.InterruptedIOException) {
            // A socket read timeout means the link is dead; only the whole-call deadline is per-rung.
            if (e is java.net.SocketTimeoutException) throw e
            throw ApiException(408, "$endpoint did not finish within ${limits.callTimeoutMillis} ms")
        }

    /** Codes [downloadToFile] and [copyBounded] raise for a rung that cannot be used: too big, too slow, elsewhere. */
    private fun rungUnusable(e: ApiException): Boolean = e.code == 413 || e.code == 408 || e.code == 421

    private fun fetchToFile(url: HttpUrl, endpoint: String, scope: String, dest: File): String {
        client.newCall(authed(url).get().build()).execute().use { response ->
            ensureSuccess(response, endpoint, scope)
            val body = response.body ?: throw IOException("$endpoint returned an empty body")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    copyBounded(body, out, limits.maxDownloadBytes, endpoint)
                    out.flush()
                    out.fd.sync()
                }
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        throw IOException("Failed to move ${tmp.name} into place at ${dest.path}")
                    }
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            return response.header("Content-Type") ?: "application/octet-stream"
        }
    }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    private fun urlFor(path: String, vararg query: Pair<String, String>): HttpUrl {
        val base = baseUrl().trim().trimEnd('/')
        val url = "$base$path".toHttpUrlOrNull()
            ?: throw IOException("Invalid Immich server URL: '$base'")
        if (query.isEmpty()) return url
        val builder = url.newBuilder()
        for ((name, value) in query) builder.addQueryParameter(name, value)
        return builder.build()
    }

    private fun authed(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("x-api-key", apiKey())

    private inline fun <reified T> executeJson(
        request: Request,
        endpoint: String,
        scope: String?,
    ): T {
        client.newCall(request).execute().use { response ->
            ensureSuccess(response, endpoint, scope)
            val body = response.body ?: throw IOException("$endpoint returned an empty body")
            val text = readBounded(body, limits.maxJsonBytes, endpoint).toString(Charsets.UTF_8)
            try {
                return ApiJson.json.decodeFromString<T>(text)
            } catch (e: Exception) {
                throw IOException("$endpoint returned unparseable JSON: ${e.message}", e)
            }
        }
    }

    /** Whole body as bytes, or [IOException] once it exceeds [maxBytes]. Never buffers more than that. */
    private fun readBounded(body: okhttp3.ResponseBody, maxBytes: Long, what: String): ByteArray {
        val source = body.source()
        if (source.request(maxBytes + 1)) throw IOException("$what exceeds $maxBytes bytes")
        return source.readByteArray()
    }

    /**
     * Streams [body] to [out], failing with an HTTP-413-style [ApiException] once it exceeds
     * [maxBytes] — an ApiException so callers skip this one asset (or fall down the quality
     * ladder) instead of treating it as a transport failure that aborts the whole sync.
     */
    private fun copyBounded(body: okhttp3.ResponseBody, out: java.io.OutputStream, maxBytes: Long, what: String) {
        val declared = body.contentLength()
        if (declared > maxBytes) throw ApiException(413, "$what is $declared bytes; cap is $maxBytes")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw ApiException(413, "$what exceeds $maxBytes bytes")
                out.write(buffer, 0, n)
            }
        }
    }

    /** Throws [ApiException] for non-2xx; sets [ApiException.scopeHint] on 403. */
    private fun ensureSuccess(response: Response, endpoint: String, scope: String?) {
        if (response.isSuccessful) return
        val snippet = try {
            response.peekBody(4096).string().take(200)
        } catch (_: Exception) {
            null
        }
        val hint = if (response.code == 403) scope else null
        val message = buildString {
            append(endpoint).append(" failed: HTTP ").append(response.code)
            if (hint != null) append(" (API key likely missing '").append(hint).append("' permission)")
            if (!snippet.isNullOrBlank()) append(" — ").append(snippet)
        }
        throw ApiException(response.code, message, hint)
    }
}
