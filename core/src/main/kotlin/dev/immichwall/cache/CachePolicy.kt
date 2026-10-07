package dev.immichwall.cache

import dev.immichwall.api.AssetIds
import java.security.MessageDigest
import kotlin.math.max

/**
 * Pure selection rules over the cache manifest. The cache is partitioned by
 * [CacheEntry.sourceKey] (one key per cycle): rotation only draws from the active cycle
 * (or, until that has a photo, from the cycle already on screen: never from one that was
 * only prefetched), and photos of cycles the schedule no longer needs are purged.
 */
object CachePolicy {

    /** Syncs stop adding photos below this much free space; prefetching doubles the cache near a boundary. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    fun hasRoom(usableBytes: Long): Boolean = usableBytes >= MIN_FREE_BYTES

    fun sameEntry(a: CacheEntry, b: CacheEntry): Boolean =
        a.assetId == b.assetId && a.sourceKey == b.sourceKey

    /**
     * Ready-file name relative to `ready/`: one subdirectory per cycle key, so the same
     * asset can be cached for two cycles. The directory is a digest of the WHOLE key: two
     * keys that differ only in their quality tag (same source, changed setting) must not
     * share files, or purging the old key would delete the new key's photos. Rejects ids
     * that are not UUIDs — the id comes from the server and must never steer a path.
     */
    fun readyFileName(sourceKey: String, assetId: String): String {
        require(AssetIds.isUuid(assetId)) { "asset id is not a UUID" }
        return "${directoryFor(sourceKey)}/$assetId.jpg"
    }

    private fun directoryFor(sourceKey: String): String {
        if (sourceKey.isEmpty()) return "legacy"
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceKey.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * The photo the next advance should show: least-recently-shown (never-shown first,
     * oldest-added among those) among the ACTIVE cycle's entries, excluding the one on
     * screen.
     *
     * While the active cycle has nothing cached, the wallpaper keeps rotating within the
     * cycle of the photo on screen ([current]), so the previous set stays up. The cache also
     * holds cycles prefetched for the days ahead, and their never-shown photos would
     * otherwise sort first: a birthday album must not appear a day early. When that cycle
     * has nothing but the photo on screen the answer is null (keep it). Every entry is
     * eligible only when nothing is on screen or the cache holds nothing of the on-screen
     * cycle, where the alternative is a blank wallpaper.
     */
    fun nextToShow(entries: List<CacheEntry>, current: CacheEntry?, activeKey: String): CacheEntry? {
        val ofActive = entries.filter { it.sourceKey == activeKey }
        val pool = when {
            ofActive.isNotEmpty() -> ofActive
            current == null -> entries
            else -> entries.filter { it.sourceKey == current.sourceKey }.ifEmpty { entries }
        }
        return pool.asSequence()
            .filter { current == null || !sameEntry(it, current) }
            .minWithOrNull(compareBy({ it.lastShownAt }, { it.shownCount }, { it.addedAt }))
    }

    /** Freshest entry of the active cycle — where the wallpaper jumps after a switch. */
    fun newest(entries: List<CacheEntry>, activeKey: String): CacheEntry? =
        entries.filter { it.sourceKey == activeKey }.maxByOrNull { it.addedAt }

    /**
     * Entries to delete because their cycle is outside [retainedKeys]. Nothing is purged
     * until the active cycle has at least one photo — the old set is all there is to show.
     */
    fun purgeable(entries: List<CacheEntry>, retainedKeys: Set<String>, activeKey: String): List<CacheEntry> {
        if (entries.none { it.sourceKey == activeKey }) return emptyList()
        return entries.filter { it.sourceKey !in retainedKeys }
    }

    /** Entries of cycle [key] to evict to get down to `max(floor, target)`: most-shown first, then oldest. */
    fun evictable(entries: List<CacheEntry>, key: String, target: Int, floor: Int): List<CacheEntry> {
        val mine = entries.filter { it.sourceKey == key }
        val keep = max(floor, target)
        if (mine.size <= keep) return emptyList()
        return mine
            .sortedWith(compareByDescending<CacheEntry> { it.shownCount }.thenBy { it.addedAt })
            .take(mine.size - keep)
    }
}
