package dev.immichwall.cache

import android.content.Context
import dev.immichwall.api.ApiJson
import dev.immichwall.crop.BitmapPipeline
import dev.immichwall.util.Logg
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * Process-wide owner of `filesDir/wallpaper-cache/`:
 *
 * ```
 * manifest.json        atomic tmp+rename+fsync; entries + sync metadata
 * cursor.txt           single int rotation index; the ONLY file touched per screen-off advance
 * staging/             downloads + crops in progress
 * ready/<assetId>.jpg  finished, verified, panel-sized wallpapers
 * ```
 *
 * Every public method is safe to call from any thread: all state (manifest + cursor) is
 * guarded by one internal lock. Startup salvage reconciles manifest and disk in both
 * directions (entries without a ready file are dropped; ready files without an entry are
 * deleted; stale staging leftovers are cleared).
 */
class PhotoCacheManager private constructor(private val ctx: Context) {

    companion object {
        /** Eviction never drops the ready set below this many photos. */
        const val HARD_FLOOR = 20

        private const val TAG = "PhotoCacheManager"
        private const val ROOT_DIR = "wallpaper-cache"
        private const val MANIFEST_NAME = "manifest.json"
        private const val CURSOR_NAME = "cursor.txt"
        private const val SHOWN_LOG_NAME = "shown.log"

        @Volatile
        private var instance: PhotoCacheManager? = null

        fun get(ctx: Context): PhotoCacheManager =
            instance ?: synchronized(this) {
                instance ?: PhotoCacheManager(ctx.applicationContext).also { instance = it }
            }
    }

    private val lock = Any()

    private val root = File(ctx.filesDir, ROOT_DIR)
    private val stagingRoot = File(root, "staging")
    private val readyRoot = File(root, "ready")
    private val manifestFile = File(root, MANIFEST_NAME)
    private val cursorFile = File(root, CURSOR_NAME)

    /**
     * Wake-path sidecar bridging shown-marks to the next manifest write: `advance` marks
     * entries shown in memory and appends "assetId ts" here (manifest.json is never
     * rewritten per screen-off); any successful manifest persist folds the marks in and
     * truncates the log; load-time replay covers process death in between.
     */
    private val shownLogFile = File(root, SHOWN_LOG_NAME)

    /** Guarded by [lock]. */
    private var manifestState: CacheManifest

    /** Rotation index into [CacheManifest.entries]; guarded by [lock]. */
    private var cursor: Int

    init {
        root.mkdirs()
        stagingRoot.mkdirs()
        readyRoot.mkdirs()
        manifestState = replayShownLog(loadManifest())
        cursor = loadCursor()
        synchronized(lock) { salvageLocked() }
    }

    // ---------------------------------------------------------------- public API

    fun manifest(): CacheManifest = synchronized(lock) { manifestState }

    /** Applies [mutator] and persists the result atomically (tmp + fsync + rename). */
    fun updateManifest(mutator: (CacheManifest) -> CacheManifest) {
        synchronized(lock) {
            manifestState = mutator(manifestState)
            persistManifestLocked()
            clampCursorLocked()
        }
    }

    fun stagingDir(): File {
        stagingRoot.mkdirs()
        return stagingRoot
    }

    fun readyDir(): File {
        readyRoot.mkdirs()
        return readyRoot
    }

    fun readyFile(entry: CacheEntry): File = File(readyRoot, entry.fileName)

    /** Entry at the rotation cursor, or null when the cache is empty. */
    fun currentEntry(): CacheEntry? = synchronized(lock) {
        manifestState.entries.getOrNull(cursor)
    }

    /**
     * The entry the next advance SHOULD show — least-recently-shown first (never-shown
     * entries win, oldest-added among them), excluding the entry currently on screen.
     * Pure read: nothing moves until [commitShown]. Peek-then-commit lets the caller
     * decode and re-check screen state first, so an aborted advance leaves no trace.
     */
    fun peekNextShown(): CacheEntry? = synchronized(lock) {
        val entries = manifestState.entries
        if (entries.isEmpty()) return null
        if (entries.size == 1) return entries[0].takeIf { cursor != 0 } // sole entry already showing → nothing new
        val currentId = entries.getOrNull(cursor)?.assetId
        entries.asSequence()
            .filter { it.assetId != currentId }
            .minWithOrNull(compareBy({ it.lastShownAt }, { it.shownCount }, { it.addedAt }))
    }

    /**
     * Marks [assetId] as shown NOW and points the cursor at it. Persists only
     * `cursor.txt` plus an append to `shown.log` — the manifest is never rewritten on
     * the wake path; the next manifest persist folds the marks in.
     */
    fun commitShown(assetId: String) {
        synchronized(lock) {
            val idx = manifestState.entries.indexOfFirst { it.assetId == assetId }
            if (idx < 0) return
            val now = System.currentTimeMillis()
            markShownLocked(assetId, now)
            cursor = idx
            persistCursorBestEffortLocked()
            appendShownLogLocked(assetId, now)
        }
    }

    /**
     * Moves the cursor to the most recently added entry — used after a source change so
     * the visible wallpaper can jump (crossfade) to the new source's freshest photo
     * instead of waiting out the rotation through leftover old-source entries.
     */
    fun jumpToNewest(): CacheEntry? = synchronized(lock) {
        val entries = manifestState.entries
        if (entries.isEmpty()) return null
        var newest = 0
        entries.forEachIndexed { i, e -> if (e.addedAt > entries[newest].addedAt) newest = i }
        val target = entries[newest]
        if (newest != cursor) {
            // The jump target is about to be displayed — mark it shown so the LRU
            // rotation doesn't immediately swap away from it at the next screen-off.
            val now = System.currentTimeMillis()
            markShownLocked(target.assetId, now)
            cursor = newest
            persistCursorBestEffortLocked()
            appendShownLogLocked(target.assetId, now)
        }
        target
    }

    /**
     * Ingest gate: verification re-decode of [staged] → fsync → atomic rename into
     * `ready/<assetId>.jpg` → manifest append. Corrupt files die here, not at wake.
     * On failure the staged file is deleted and false is returned.
     */
    fun promote(entry: CacheEntry, staged: File): Boolean {
        if (!staged.isFile) {
            Logg.w(TAG, "promote: staged file missing for ${entry.assetId}")
            return false
        }
        // Verification decode is slow — keep it outside the lock so the wake path never
        // blocks behind an ingest.
        if (!BitmapPipeline.verifyDecodable(staged)) {
            Logg.w(TAG, "promote: staged file failed verification for ${entry.assetId}")
            staged.delete()
            return false
        }
        try {
            RandomAccessFile(staged, "rw").use { it.fd.sync() }
        } catch (e: IOException) {
            Logg.e(TAG, "promote: fsync failed for ${entry.assetId}", e)
            staged.delete()
            return false
        }
        synchronized(lock) {
            readyRoot.mkdirs()
            val destName = "${entry.assetId}.jpg"
            val dest = File(readyRoot, destName)
            val currentAssetId = manifestState.entries.getOrNull(cursor)?.assetId
            // rename() replaces an existing target atomically, so a re-promoted asset never
            // has a moment without a ready file; the delete+retry mirrors writeAtomicLocked.
            if (!staged.renameTo(dest)) {
                dest.delete()
                if (!staged.renameTo(dest)) {
                    Logg.e(TAG, "promote: rename ${staged.name} -> $destName failed")
                    staged.delete()
                    return false
                }
            }
            val normalized = if (entry.fileName == destName) entry else entry.copy(fileName = destName)
            manifestState = manifestState.copy(
                entries = manifestState.entries.filter { it.assetId != normalized.assetId } + normalized
            )
            try {
                persistManifestLocked()
            } catch (e: IOException) {
                // In-memory state is authoritative; the next successful manifest write
                // (or startup salvage) reconciles disk.
                Logg.e(TAG, "promote: manifest persist failed for ${entry.assetId}", e)
            }
            // Re-promoting an existing asset moves it to the end of the list; keep the
            // cursor pointing at the entry it was on so the on-screen photo never shifts.
            val idx = manifestState.entries.indexOfFirst { it.assetId == currentAssetId }
            if (idx >= 0) {
                if (idx != cursor) {
                    cursor = idx
                    persistCursorBestEffortLocked()
                }
            } else {
                clampCursorLocked()
            }
            return true
        }
    }

    /** Removes the entry (if present), deletes its ready file, and clamps the cursor. */
    fun removeEntry(assetId: String) {
        synchronized(lock) {
            val entries = manifestState.entries
            val idx = entries.indexOfFirst { it.assetId == assetId }
            if (idx < 0) return
            File(readyRoot, entries[idx].fileName).delete()
            val remaining = entries.toMutableList().also { it.removeAt(idx) }
            manifestState = manifestState.copy(entries = remaining)
            try {
                persistManifestLocked()
            } catch (e: IOException) {
                // In-memory state is authoritative; the next successful manifest write
                // (or startup salvage) reconciles disk. removeEntry runs on the WAKE
                // path — it must never throw (a full disk would kill the process).
                Logg.e(TAG, "removeEntry: manifest persist failed for $assetId", e)
            }
            cursor = when {
                remaining.isEmpty() -> 0
                idx < cursor -> cursor - 1          // keep pointing at the same entry
                else -> cursor
            }
            if (cursor >= remaining.size || cursor < 0) cursor = 0
            persistCursorBestEffortLocked()
        }
    }

    /**
     * Evicts oldest-first (by [CacheEntry.addedAt]) down to `max(HARD_FLOOR, target)`.
     * Deletes the evicted ready files and clamps the cursor, preferring to keep it on
     * the entry it was pointing at.
     */
    fun evictToTarget(target: Int) {
        synchronized(lock) {
            val keep = max(HARD_FLOOR, target)
            val entries = manifestState.entries
            if (entries.size <= keep) return
            val currentAssetId = entries.getOrNull(cursor)?.assetId
            // Most-shown-first (then oldest) keeps variety: a photo everyone has seen
            // ten times yields its slot before one that never got its turn.
            val evict = entries
                .sortedWith(compareByDescending<CacheEntry> { it.shownCount }.thenBy { it.addedAt })
                .take(entries.size - keep)
                .mapTo(HashSet()) { it.assetId }
            val remaining = ArrayList<CacheEntry>(keep)
            for (entry in entries) {
                if (entry.assetId in evict) {
                    File(readyRoot, entry.fileName).delete()
                } else {
                    remaining.add(entry)
                }
            }
            Logg.d(TAG, "evictToTarget($target): evicted ${evict.size}, ${remaining.size} remain")
            manifestState = manifestState.copy(entries = remaining)
            persistManifestLocked()
            val stillThere = remaining.indexOfFirst { it.assetId == currentAssetId }
            cursor = when {
                remaining.isEmpty() -> 0
                stillThere >= 0 -> stillThere
                else -> cursor.coerceIn(0, remaining.size - 1)
            }
            persistCursorBestEffortLocked()
        }
    }

    fun containsAsset(assetId: String): Boolean = synchronized(lock) {
        manifestState.entries.any { it.assetId == assetId }
    }

    /** Entry for [assetId], or null when not cached — used for per-entry staleness checks. */
    fun entryFor(assetId: String): CacheEntry? = synchronized(lock) {
        manifestState.entries.firstOrNull { it.assetId == assetId }
    }

    fun readyCount(): Int = synchronized(lock) { manifestState.entries.size }

    // ---------------------------------------------------------------- persistence

    private fun loadManifest(): CacheManifest {
        if (!manifestFile.isFile) return CacheManifest()
        return try {
            val decoded = ApiJson.json.decodeFromString(
                CacheManifest.serializer(),
                manifestFile.readText(Charsets.UTF_8)
            )
            migrateEntrySourceKeys(decoded)
        } catch (t: Throwable) {
            Logg.w(TAG, "manifest.json unreadable (${t.javaClass.simpleName}), starting fresh")
            CacheManifest()
        }
    }

    /**
     * Pre-sourceKey manifests carry the key only at manifest level; entries decode with
     * sourceKey == "". Stamp entries whose crop dims prove they belong to the recorded
     * source — otherwise the first refresh after the schema change would mark the whole
     * healthy cache stale and re-download it. Dim-mismatched entries stay unstamped
     * (genuinely unknown provenance reads as stale, the safe direction).
     */
    private fun migrateEntrySourceKeys(m: CacheManifest): CacheManifest {
        if (m.sourceKey.isEmpty()) return m
        var stamped = 0
        val entries = m.entries.map { e ->
            if (e.sourceKey.isEmpty() && e.width == m.cropWidth && e.height == m.cropHeight) {
                stamped++
                e.copy(sourceKey = m.sourceKey)
            } else e
        }
        if (stamped == 0) return m
        Logg.d(TAG, "manifest migration: stamped sourceKey onto $stamped legacy entries")
        return m.copy(entries = entries)
    }

    private fun loadCursor(): Int = try {
        if (cursorFile.isFile) cursorFile.readText(Charsets.UTF_8).trim().toIntOrNull() ?: 0 else 0
    } catch (t: Throwable) {
        0
    }

    private fun persistManifestLocked() {
        val json = ApiJson.json.encodeToString(CacheManifest.serializer(), manifestState)
        writeAtomicLocked(manifestFile, json.toByteArray(Charsets.UTF_8))
        // The in-memory state (incl. any shown-marks) is now durable — the wake-path
        // sidecar has served its purpose until the next advance.
        shownLogFile.delete()
    }

    /** In-memory shown-mark; callers persist via cursor+log (wake) or manifest (worker). */
    private fun markShownLocked(assetId: String, now: Long) {
        manifestState = manifestState.copy(
            entries = manifestState.entries.map {
                if (it.assetId == assetId) it.copy(lastShownAt = now, shownCount = it.shownCount + 1) else it
            }
        )
    }

    private fun appendShownLogLocked(assetId: String, now: Long) {
        try {
            shownLogFile.appendText("$assetId $now\n", Charsets.US_ASCII)
        } catch (e: IOException) {
            // Best effort: losing a mark only means one photo may repeat a bit sooner.
            Logg.w(TAG, "shown.log append failed: ${e.message}")
        }
    }

    /** Replays wake-path shown-marks that a process death left unfolded into the manifest. */
    private fun replayShownLog(m: CacheManifest): CacheManifest {
        if (!shownLogFile.isFile) return m
        return try {
            val marks = HashMap<String, Pair<Long, Int>>() // assetId -> (latest ts, count)
            shownLogFile.readLines(Charsets.US_ASCII).forEach { line ->
                val sep = line.lastIndexOf(' ')
                if (sep <= 0) return@forEach
                val id = line.substring(0, sep)
                val ts = line.substring(sep + 1).toLongOrNull() ?: return@forEach
                val prev = marks[id]
                marks[id] = Pair(maxOf(ts, prev?.first ?: 0L), (prev?.second ?: 0) + 1)
            }
            if (marks.isEmpty()) return m
            m.copy(entries = m.entries.map { e ->
                marks[e.assetId]?.let { (ts, n) ->
                    e.copy(lastShownAt = maxOf(e.lastShownAt, ts), shownCount = e.shownCount + n)
                } ?: e
            })
        } catch (t: Throwable) {
            Logg.w(TAG, "shown.log replay failed (${t.javaClass.simpleName}); ignoring")
            m
        }
    }

    private fun persistCursorLocked() {
        writeAtomicLocked(cursorFile, cursor.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun persistCursorBestEffortLocked() {
        try {
            persistCursorLocked()
        } catch (t: Throwable) {
            // A cursor that fails to persist must not break the wake path; worst case the
            // rotation resumes one photo behind after a process restart.
            Logg.e(TAG, "cursor.txt persist failed", t)
        }
    }

    /** tmp write → flush → fd.sync() → rename over the target. */
    private fun writeAtomicLocked(target: File, bytes: ByteArray) {
        root.mkdirs()
        val tmp = File(root, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IOException("rename ${tmp.name} -> ${target.name} failed")
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // ---------------------------------------------------------------- salvage

    /**
     * Reconciles manifest and disk at startup: manifest entries whose ready file vanished
     * are dropped; ready files not referenced by the manifest are deleted; interrupted
     * staging leftovers are cleared.
     */
    private fun salvageLocked() {
        val kept = manifestState.entries.filter { File(readyRoot, it.fileName).isFile }
        if (kept.size != manifestState.entries.size) {
            Logg.w(TAG, "salvage: dropped ${manifestState.entries.size - kept.size} entries with missing ready files")
            manifestState = manifestState.copy(entries = kept)
            try {
                persistManifestLocked()
            } catch (e: IOException) {
                Logg.e(TAG, "salvage: manifest persist failed", e)
            }
        }
        val known = kept.mapTo(HashSet()) { it.fileName }
        readyRoot.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in known) {
                Logg.w(TAG, "salvage: deleting orphan ready file ${file.name}")
                file.delete()
            }
        }
        stagingRoot.listFiles()?.forEach { file ->
            // Loose files (legacy layout) and per-run subdirectories alike are leftovers here.
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        clampCursorLocked()
    }

    private fun clampCursorLocked() {
        val size = manifestState.entries.size
        val clamped = when {
            size == 0 -> 0
            cursor in 0 until size -> cursor
            else -> ((cursor % size) + size) % size
        }
        if (clamped != cursor) {
            cursor = clamped
            persistCursorBestEffortLocked()
        }
    }
}
