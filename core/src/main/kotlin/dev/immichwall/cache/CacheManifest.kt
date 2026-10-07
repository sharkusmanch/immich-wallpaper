package dev.immichwall.cache

import kotlinx.serialization.Serializable

/**
 * One finished wallpaper in `ready/`. [fileName] is the file's name inside the ready
 * directory (normally `<assetId>.jpg`); [addedAt] (epoch millis) drives oldest-first eviction.
 * [width]/[height] are the exact pixel dimensions of the prepared JPEG.
 *
 * [sourceKey] is the [CacheManifest.sourceKey] that was active when the entry was prepared;
 * staleness is derived per entry from it. The empty default makes pre-migration entries read
 * as stale, which is the safe direction (they are drained 1:1 as replacements arrive).
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
 * Persisted as `wallpaper-cache/manifest.json` (atomic tmp+rename, written by the refresh
 * worker only — the per-wake cursor lives in the `cursor.txt` sidecar, never here).
 *
 * [sourceKey] is the stable hash of the active [dev.immichwall.source.SourceSpec];
 * [cropWidth]/[cropHeight] are the panel dimensions of the most recent refresh. Both are
 * bookkeeping only — staleness is derived per entry from [CacheEntry.sourceKey] and
 * [CacheEntry.width]/[CacheEntry.height] so a partially drained switch resumes correctly.
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
