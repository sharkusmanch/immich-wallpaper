package dev.immichwall.source

import dev.immichwall.cache.CachePolicy

/**
 * How many photos [spec] has, when a `AssetSourceResolver.candidates` answer of [found] for
 * [requested] proves it, else null. A short answer proves the whole source only when the
 * server's own search ran dry, which depends on which resolver answers are post-filtered or split:
 * - "any of" several people (People, or Custom without a query) is one search per person,
 *   each given a share of the request: one person running dry says nothing about the others;
 * - SmartQuery and Custom with a query use smart search, which is sent without an asset-type
 *   filter: the pool it fills can hold videos that `AssetSourceResolver.candidates` then
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
