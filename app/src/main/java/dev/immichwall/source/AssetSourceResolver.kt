package dev.immichwall.source

import dev.immichwall.api.ApiException
import dev.immichwall.api.AssetDto
import dev.immichwall.api.AssetIds
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.cache.CachePolicy
import dev.immichwall.util.Logg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/**
 * How many photos [spec] has, when a [AssetSourceResolver.candidates] answer of [found] for
 * [requested] proves it, else null. A short answer proves the whole source only when the
 * server's own search ran dry, so this lives beside the resolver that knows which answers
 * are post-filtered or split:
 * - "any of" several people (People, or Custom without a query) is one search per person,
 *   each given a share of the request: one person running dry says nothing about the others;
 * - SmartQuery and Custom with a query use smart search, which is sent without an asset-type
 *   filter: the pool it fills can hold videos that [AssetSourceResolver.candidates] then
 *   drops, so a short answer may just be a full pool minus its videos. With an album, the
 *   top matches library-wide are also intersected with the album, which can leave a handful.
 * Every other branch asks the server for [requested] and takes what it returns (Memories
 * returns its whole pool), so fewer than asked means no more exist.
 */
fun knownSourceSize(spec: SourceSpec, found: Int, requested: Int): Int? {
    val partial = when (spec) {
        is SourceSpec.People -> spec.ids.size > 1 && !spec.requireAll
        is SourceSpec.SmartQuery -> true
        is SourceSpec.Custom ->
            spec.query.isNotBlank() || (spec.personIds.size > 1 && !spec.requireAll)
        else -> false
    }
    return if (partial) null else CachePolicy.sourceSizeIfExhausted(found, requested)
}

/**
 * Maps a [SourceSpec] to Immich v3 endpoints and returns shuffled, IMAGE-only, deduplicated
 * candidate assets. Mode mapping per DESIGN.md "Source layer":
 *
 * - People ANY: one `search/random` call **per person** (v3 `personIds` is AND-semantics),
 *   merged + deduped. People ALL: a single call with all ids.
 * - Album / Location: `search/random` with `albumIds` / `city`; if the server's RandomSearchDto
 *   rejects the filter (HTTP 400), falls back to `search/metadata` paging + client shuffle.
 * - SmartQuery: `search/smart` top pool (~200, relevance-ranked) then client shuffle.
 * - Favorites: `search/random {isFavorite:true}`. Everything: `search/random {}`.
 * - Memories: `GET /memories?for=<date>` over the spec's ±window of days, filtered to the
 *   spec's years-ago bounds, flattening the matching years' assets.
 *
 * Transport failures ([java.io.IOException]) propagate to the caller (workers retry with backoff).
 */
class AssetSourceResolver(private val client: ImmichApiClient) {

    /**
     * Returns up to [n] candidate assets for [spec]; IMAGE-only, deduped by id, shuffled.
     * Ids come from the server and end up in file names (the cache, the preview grid's
     * temp files): anything that is not a UUID is dropped here, so no caller ever sees one.
     */
    fun candidates(spec: SourceSpec, n: Int): List<AssetDto> {
        if (n <= 0) return emptyList()
        val size = n.coerceAtMost(MAX_REQUEST_SIZE)
        val raw: List<AssetDto> = when (spec) {
            is SourceSpec.People -> peopleCandidates(spec, size)

            is SourceSpec.Album -> randomWithMetadataFallback(
                buildJsonObject {
                    putJsonArray("albumIds") { add(spec.albumId) }
                    put("type", "IMAGE")
                },
                size,
            )

            is SourceSpec.SmartQuery -> smartCandidates(spec, size)

            is SourceSpec.Location -> randomWithMetadataFallback(
                buildJsonObject {
                    put("city", spec.city)
                    put("type", "IMAGE")
                },
                size,
            )

            SourceSpec.Favorites -> client.searchRandom(
                buildJsonObject {
                    put("isFavorite", true)
                    put("type", "IMAGE")
                },
                size,
            )

            is SourceSpec.Memories -> memoriesCandidates(spec)

            SourceSpec.EverythingRandom -> client.searchRandom(
                buildJsonObject { put("type", "IMAGE") },
                size,
            )

            is SourceSpec.Custom -> customCandidates(spec, size)
        }
        return raw.asSequence()
            .filter { it.type == TYPE_IMAGE && AssetIds.isUuid(it.id) }
            .distinctBy { it.id }
            .toList()
            .shuffled()
            .take(n)
    }

    /**
     * ANY (requireAll=false): one search/random per person then merge — passing several
     * `personIds` in one call would AND them (photos containing *everyone*), which the sibling
     * project learned the hard way. ALL (requireAll=true): that AND behavior is exactly what
     * we want, so a single call with all ids.
     */
    private fun peopleCandidates(spec: SourceSpec.People, n: Int): List<AssetDto> {
        if (spec.ids.isEmpty()) return emptyList()
        if (spec.requireAll || spec.ids.size == 1) {
            return client.searchRandom(personFilter(spec.ids), n)
        }
        val perPerson = ((n + spec.ids.size - 1) / spec.ids.size).coerceAtLeast(1)
        return spec.ids.flatMap { id ->
            client.searchRandom(personFilter(listOf(id)), perPerson)
        }
    }

    private fun personFilter(ids: List<String>): JsonObject = buildJsonObject {
        putJsonArray("personIds") { ids.forEach { add(it) } }
        put("type", "IMAGE")
    }

    /** Relevance-ranked results: fetch a large pool and let [candidates] shuffle for variety. */
    private fun smartCandidates(spec: SourceSpec.SmartQuery, n: Int): List<AssetDto> {
        val poolSize = maxOf(n, SHUFFLE_POOL_SIZE).coerceAtMost(MAX_REQUEST_SIZE)
        return client.searchSmart(spec.query, spec.personIds, poolSize).assets.items
    }

    /**
     * Tries `search/random` with [filters]; if the server rejects a filter field with HTTP 400
     * (RandomSearchDto support for `albumIds`/`city` varies by server version), falls back to
     * paged `search/metadata` with the same filters, collecting a pool for client-side shuffle.
     */
    private fun randomWithMetadataFallback(filters: JsonObject, n: Int): List<AssetDto> {
        return try {
            client.searchRandom(filters, n)
        } catch (e: ApiException) {
            if (e.code != 400) throw e
            Logg.w(TAG, "search/random rejected filters (${e.message}); falling back to search/metadata")
            metadataPool(filters, n)
        }
    }

    private fun metadataPool(filters: JsonObject, n: Int): List<AssetDto> {
        val poolTarget = maxOf(n, SHUFFLE_POOL_SIZE)
        val pool = LinkedHashMap<String, AssetDto>()
        var page = 1
        while (pool.size < poolTarget && page <= MAX_METADATA_PAGES) {
            val response = client.searchMetadata(filters, page, METADATA_PAGE_SIZE)
            val items = response.assets.items
            if (items.isEmpty()) break
            for (asset in items) {
                if (asset.type == TYPE_IMAGE) pool.putIfAbsent(asset.id, asset)
            }
            val next = response.assets.nextPage?.toIntOrNull() ?: break
            if (next <= page) break // server must strictly advance pagination; guards cycling nextPage
            page = next
        }
        return pool.values.toList()
    }

    /**
     * On-this-day memories across the spec's ±window of days, keeping only memories whose
     * year falls inside the spec's years-ago bounds (0 = unbounded).
     */
    private fun memoriesCandidates(spec: SourceSpec.Memories): List<AssetDto> {
        val today = LocalDate.now()
        val pool = LinkedHashMap<String, AssetDto>()

        fun collect(date: LocalDate) {
            val memories = try {
                client.getMemories(date.toString()) // bare YYYY-MM-DD; ISO datetime is rejected
            } catch (e: ApiException) {
                Logg.w(TAG, "memories for $date failed: HTTP ${e.code} ${e.message}")
                emptyList()
            }
            for (memory in memories) {
                // Year filter: how far back this memory's photos come from. Memories
                // without a year payload only pass when no bound is set.
                val year = memory.data?.year ?: 0
                if (spec.yearsAgoMin > 0 || spec.yearsAgoMax > 0) {
                    if (year <= 0) continue
                    val yearsAgo = today.year - year
                    if (spec.yearsAgoMin > 0 && yearsAgo < spec.yearsAgoMin) continue
                    if (spec.yearsAgoMax > 0 && yearsAgo > spec.yearsAgoMax) continue
                }
                for (asset in memory.assets) {
                    if (asset.type == TYPE_IMAGE) pool.putIfAbsent(asset.id, asset)
                }
            }
        }

        collect(today)
        for (offset in 1..spec.windowDays.coerceIn(0, MEMORIES_MAX_WINDOW_DAYS)) {
            collect(today.minusDays(offset.toLong()))
            collect(today.plusDays(offset.toLong()))
        }
        return pool.values.toList()
    }

    /**
     * Combined filters. Without a text query: `search/random` (metadata fallback) with all
     * filters — multiple people use per-person calls merged for ANY, one AND call for ALL.
     * With a query: `search/smart` carries every filter it supports; an album constraint
     * (not in SmartSearchDto) is applied by intersecting with the album's asset ids.
     */
    private fun customCandidates(spec: SourceSpec.Custom, n: Int): List<AssetDto> {
        fun baseFilters(includePeople: List<String>) = buildJsonObject {
            if (includePeople.isNotEmpty()) putJsonArray("personIds") { includePeople.forEach { add(it) } }
            if (spec.albumId.isNotBlank()) putJsonArray("albumIds") { add(spec.albumId) }
            if (spec.city.isNotBlank()) put("city", spec.city)
            if (spec.favoritesOnly) put("isFavorite", true)
            if (spec.takenAfter.isNotBlank()) put("takenAfter", "${spec.takenAfter}T00:00:00.000Z")
            if (spec.takenBefore.isNotBlank()) put("takenBefore", "${spec.takenBefore}T23:59:59.999Z")
            put("type", "IMAGE")
        }

        if (spec.query.isBlank()) {
            val anyOfPeople = spec.personIds.size >= 2 && !spec.requireAll
            return if (anyOfPeople) {
                val perPerson = ((n + spec.personIds.size - 1) / spec.personIds.size).coerceAtLeast(1)
                spec.personIds.flatMap { id ->
                    randomWithMetadataFallback(baseFilters(listOf(id)), perPerson)
                }
            } else {
                randomWithMetadataFallback(baseFilters(spec.personIds), n)
            }
        }

        // Query path: smart search with everything except albumIds (unsupported there).
        val smartFilters = buildJsonObject {
            baseFilters(spec.personIds).forEach { (key, value) ->
                if (key != "albumIds" && key != "type") put(key, value)
            }
        }
        val poolSize = maxOf(n, SHUFFLE_POOL_SIZE).coerceAtMost(MAX_REQUEST_SIZE)
        var results = client.searchSmartFiltered(spec.query, smartFilters, poolSize).assets.items
        if (spec.albumId.isNotBlank()) {
            val albumIds = metadataPool(
                buildJsonObject {
                    putJsonArray("albumIds") { add(spec.albumId) }
                    put("type", "IMAGE")
                },
                MAX_REQUEST_SIZE,
            ).mapTo(HashSet()) { it.id }
            results = results.filter { it.id in albumIds }
        }
        return results
    }

    private companion object {
        const val TAG = "AssetSourceResolver"
        const val TYPE_IMAGE = "IMAGE"
        const val SHUFFLE_POOL_SIZE = 200
        const val METADATA_PAGE_SIZE = 200
        const val MAX_METADATA_PAGES = 50
        const val MAX_REQUEST_SIZE = 1000
        const val MEMORIES_MAX_WINDOW_DAYS = 14
    }
}
