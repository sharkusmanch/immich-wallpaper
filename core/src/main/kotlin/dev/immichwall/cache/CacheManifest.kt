package dev.immichwall.cache

import kotlinx.serialization.Serializable

/**
 * One finished wallpaper in `ready/`. [fileName] is its path relative to that directory,
 * `<cycle directory>/<assetId>.jpg`, with one directory per cycle key (see
 * [CachePolicy.readyFileName]). [addedAt] (epoch millis) picks the freshest photo to jump
 * to after a cycle switch and breaks ties in eviction, which goes most-shown first.
 * [width]/[height] are the exact pixel dimensions of the prepared JPEG; an entry whose size
 * is no longer the crop box is stale and is prepared again.
 *
 * [sourceKey] is the cache partition key of the cycle the photo was prepared for (its
 * source, its quality tag and, for Memories, the day). An entry is identified by
 * [sourceKey] AND [assetId], so one photo can be cached for two cycles. Rotation draws from
 * the active cycle's key, and whole keys are purged once the schedule no longer needs them.
 * The empty default is an entry written before the cache was partitioned: it matches no
 * cycle and goes with the next purge.
 */
@Serializable
data class CacheEntry(
    val assetId: String,
    val fileName: String,
    val addedAt: Long,
    val width: Int,
    val height: Int,
    val sourceSize: String = "fullsize",
    val sourceKey: String = "",
    /** Millis of the most recent display; 0 = never shown. Drives LRU rotation. */
    val lastShownAt: Long = 0,
    /** Times displayed; drives most-shown-first eviction. */
    val shownCount: Int = 0,
    /**
     * Where the faces sit in the prepared file, as fractions of its width and height.
     * The engine keeps this point in view on a surface narrower or shorter than the file.
     */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
)

/**
 * Persisted as `wallpaper-cache/manifest.json` (atomic tmp+rename) by the app's cache
 * manager whenever the set of entries or the sync bookkeeping changes: a photo promoted,
 * evicted, purged or found corrupt, the cache cleared, a sync finished. A plain screen-off
 * advance never rewrites it: the cursor and the shown-marks go to the `cursor.txt` and
 * `shown.log` sidecars and are folded in at the next write.
 *
 * [sourceKey] is the active cycle's key and [cropWidth]/[cropHeight] the crop box, both as
 * of the last completed sync. They are bookkeeping only: which cycle an entry belongs to
 * and whether it is stale are read from the entry itself ([CacheEntry.sourceKey],
 * [CacheEntry.width]/[CacheEntry.height]), so an interrupted switch resumes correctly.
 * [lastSyncAt] is the last sync that completed; [lastSyncResult] is the outcome of the last
 * attempt, which may be a failure.
 */
@Serializable
data class CacheManifest(
    val schemaVersion: Int = 1,
    val sourceKey: String = "",
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
    val entries: List<CacheEntry> = emptyList(),
    val lastSyncAt: Long = 0,
    val lastSyncResult: String = "",
)
