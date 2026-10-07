package dev.immichwall.cache

import android.content.Context
import dev.immichwall.api.ApiJson
import dev.immichwall.crop.BitmapPipeline
import dev.immichwall.util.Logg
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Process-wide owner of `filesDir/wallpaper-cache/`:
 *
 * ```
 * manifest.json        atomic tmp+rename+fsync; entries + sync metadata
 * cursor.txt           single int rotation index; the ONLY file touched per screen-off advance
 * staging/             downloads + crops in progress
 * ready/<cycle>/<assetId>.jpg  finished, verified wallpapers, one directory per cycle key
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
     * Wake-path sidecar bridging shown-marks to the next manifest write: [commitShown] and
     * [jumpToNewest] mark entries shown in memory and append `assetId@sourceKey timestamp`
     * here (manifest.json is never rewritten per screen-off); any successful manifest
     * persist folds the marks in and deletes the log; load-time replay covers process death
     * in between, and still accepts the bare `assetId timestamp` lines of older installs.
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

    /** Bytes used by finished wallpapers, across every cycle's subdirectory. */
    fun readyBytes(): Long = readyRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Entry at the rotation cursor, or null when the cache is empty. */
    fun currentEntry(): CacheEntry? = synchronized(lock) {
        manifestState.entries.getOrNull(cursor)
    }

    /**
     * The entry the next advance SHOULD show: least-recently-shown among the active
     * cycle's photos, excluding the one on screen (see [CachePolicy.nextToShow]).
     * Pure read: nothing moves until [commitShown]. Peek-then-commit lets the caller
     * decode and re-check screen state first, so an aborted advance leaves no trace.
     */
    fun peekNextShown(activeKey: String): CacheEntry? = synchronized(lock) {
        CachePolicy.nextToShow(manifestState.entries, manifestState.entries.getOrNull(cursor), activeKey)
    }

    /**
     * Marks [entry] as shown NOW and points the cursor at it. Persists only
     * `cursor.txt` plus an append to `shown.log` — the manifest is never rewritten on
     * the wake path; the next manifest persist folds the marks in.
     */
    fun commitShown(entry: CacheEntry) {
        synchronized(lock) {
            val idx = indexOfLocked(entry)
            if (idx < 0) return
            val now = System.currentTimeMillis()
            markShownLocked(entry, now)
            cursor = idx
            persistCursorBestEffortLocked()
            appendShownLogLocked(entry, now)
        }
    }

    /**
     * Moves the cursor to the active cycle's most recently added entry — used after a
     * cycle change so the visible wallpaper can jump (crossfade) to the new set's
     * freshest photo. Null when the active cycle has nothing cached.
     */
    fun jumpToNewest(activeKey: String): CacheEntry? = synchronized(lock) {
        val target = CachePolicy.newest(manifestState.entries, activeKey) ?: return null
        val idx = indexOfLocked(target)
        if (idx != cursor) {
            // The jump target is about to be displayed — mark it shown so the LRU
            // rotation doesn't immediately swap away from it at the next screen-off.
            val now = System.currentTimeMillis()
            markShownLocked(target, now)
            cursor = idx
            persistCursorBestEffortLocked()
            appendShownLogLocked(target, now)
        }
        target
    }

    /**
     * Ingest gate: verification re-decode of [staged] → fsync → atomic rename into
     * `ready/<cycle>/<assetId>.jpg` → manifest append. Corrupt files die here, not at wake.
     * On failure the staged file is deleted and false is returned. An entry is identified
     * by cycle key AND asset id, so the same photo can be cached for two cycles.
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
            val destName = CachePolicy.readyFileName(entry.sourceKey, entry.assetId)
            val dest = File(readyRoot, destName)
            dest.parentFile?.mkdirs()
            val onScreen = manifestState.entries.getOrNull(cursor)
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
            // A pre-partition entry of the same identity lives under a flat file name.
            manifestState.entries.firstOrNull { CachePolicy.sameEntry(it, normalized) }
                ?.takeIf { it.fileName != destName }
                ?.let { File(readyRoot, it.fileName).delete() }
            manifestState = manifestState.copy(
                entries = manifestState.entries.filter { !CachePolicy.sameEntry(it, normalized) } + normalized
            )
            try {
                persistManifestLocked()
            } catch (e: IOException) {
                // In-memory state is authoritative; the next successful manifest write
                // (or startup salvage) reconciles disk.
                Logg.e(TAG, "promote: manifest persist failed for ${entry.assetId}", e)
            }
            // Re-promoting an existing entry moves it to the end of the list; keep the
            // cursor pointing at the entry it was on so the on-screen photo never shifts.
            val idx = if (onScreen == null) -1 else indexOfLocked(onScreen)
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

    /** Removes [entry] (if present) and deletes its ready file. Never throws: it runs on the wake path. */
    fun removeEntry(entry: CacheEntry) {
        synchronized(lock) { removeEntriesLocked(listOf(entry)) }
    }

    /**
     * Evicts cycle [sourceKey] down to `max(HARD_FLOOR, target)` photos, most-shown first
     * (then oldest): a photo everyone has seen ten times yields its slot before one that
     * never got its turn. Other cycles are untouched.
     */
    fun evictToTarget(sourceKey: String, target: Int) {
        synchronized(lock) {
            val victims = CachePolicy.evictable(manifestState.entries, sourceKey, target, HARD_FLOOR)
            if (victims.isEmpty()) return
            Logg.d(TAG, "evictToTarget($target): evicting ${victims.size}")
            removeEntriesLocked(victims)
        }
    }

    /**
     * Deletes the photos of every cycle outside [retainedKeys] — but only once the active
     * cycle has at least one photo (see [CachePolicy.purgeable]). Returns how many went.
     */
    fun purgeOutside(retainedKeys: Set<String>, activeKey: String): Int {
        synchronized(lock) {
            val victims = CachePolicy.purgeable(manifestState.entries, retainedKeys, activeKey)
            if (victims.isNotEmpty()) {
                Logg.d(TAG, "purging ${victims.size} photos of cycles no longer needed")
                removeEntriesLocked(victims)
            }
            return victims.size
        }
    }

    /** Deletes every cached photo. The wallpaper shows its placeholder until a sync refills. */
    fun clearAll() {
        synchronized(lock) {
            removeEntriesLocked(manifestState.entries)
            readyRoot.walkBottomUp().forEach { if (it != readyRoot) it.delete() }
        }
    }

    fun containsEntry(sourceKey: String, assetId: String): Boolean = entryFor(sourceKey, assetId) != null

    /** Entry for [assetId] within cycle [sourceKey], or null when not cached. */
    fun entryFor(sourceKey: String, assetId: String): CacheEntry? = synchronized(lock) {
        manifestState.entries.firstOrNull { it.sourceKey == sourceKey && it.assetId == assetId }
    }

    /** How many photos are cached for cycle [sourceKey]. */
    fun countFor(sourceKey: String): Int = synchronized(lock) {
        manifestState.entries.count { it.sourceKey == sourceKey }
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
    private fun markShownLocked(entry: CacheEntry, now: Long) {
        manifestState = manifestState.copy(
            entries = manifestState.entries.map {
                if (CachePolicy.sameEntry(it, entry)) it.copy(lastShownAt = now, shownCount = it.shownCount + 1) else it
            }
        )
    }

    private fun indexOfLocked(entry: CacheEntry): Int =
        manifestState.entries.indexOfFirst { CachePolicy.sameEntry(it, entry) }

    /**
     * Drops [victims] from the manifest and deletes their files (and any cycle directory
     * left empty), keeping the cursor on the entry it pointed at when that entry survives.
     * Never throws: a failed manifest write is logged and the in-memory state stays
     * authoritative until the next successful write or startup salvage.
     */
    private fun removeEntriesLocked(victims: List<CacheEntry>) {
        if (victims.isEmpty()) return
        val entries = manifestState.entries
        val onScreen = entries.getOrNull(cursor)
        val remaining = entries.filter { e -> victims.none { CachePolicy.sameEntry(it, e) } }
        // Never delete a file a surviving entry still points at (two entries can only share
        // one through a bug elsewhere, and losing the photo would be the worse outcome).
        val stillReferenced = remaining.mapTo(HashSet()) { it.fileName }
        for (victim in victims) {
            if (victim.fileName in stillReferenced) continue
            val file = File(readyRoot, victim.fileName)
            file.delete()
            file.parentFile?.takeIf { it != readyRoot && it.list()?.isEmpty() == true }?.delete()
        }
        manifestState = manifestState.copy(entries = remaining)
        try {
            persistManifestLocked()
        } catch (e: IOException) {
            Logg.e(TAG, "manifest persist failed after removing ${victims.size} entries", e)
        }
        val stillThere = if (onScreen == null) -1 else remaining.indexOfFirst { CachePolicy.sameEntry(it, onScreen) }
        cursor = when {
            remaining.isEmpty() -> 0
            stillThere >= 0 -> stillThere
            else -> cursor.coerceIn(0, remaining.size - 1)
        }
        persistCursorBestEffortLocked()
    }

    /** One line per mark: `assetId@sourceKey timestamp`. Neither part can contain a space. */
    private fun appendShownLogLocked(entry: CacheEntry, now: Long) {
        try {
            shownLogFile.appendText("${entry.assetId}@${entry.sourceKey} $now\n", Charsets.US_ASCII)
        } catch (e: IOException) {
            // Best effort: losing a mark only means one photo may repeat a bit sooner.
            Logg.w(TAG, "shown.log append failed: ${e.message}")
        }
    }

    /** Replays wake-path shown-marks that a process death left unfolded into the manifest. */
    private fun replayShownLog(m: CacheManifest): CacheManifest {
        if (!shownLogFile.isFile) return m
        return try {
            // Keyed by the text before the last space: `assetId@sourceKey`, or a bare
            // assetId on a line from an older install -> (latest ts, count).
            val marks = HashMap<String, Pair<Long, Int>>()
            shownLogFile.readLines(Charsets.US_ASCII).forEach { line ->
                val sep = line.lastIndexOf(' ')
                if (sep <= 0) return@forEach
                val id = line.substring(0, sep)
                val ts = line.substring(sep + 1).toLongOrNull() ?: return@forEach
                val prev = marks[id]
                marks[id] = Pair(maxOf(ts, prev?.first ?: 0L), (prev?.second ?: 0) + 1)
            }
            if (marks.isEmpty()) return m
            // Lines written before the cache was partitioned carry the asset id alone.
            m.copy(entries = m.entries.map { e ->
                (marks["${e.assetId}@${e.sourceKey}"] ?: marks[e.assetId])?.let { (ts, n) ->
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
        // Bottom-up so a cycle directory emptied of orphans is removed in the same pass.
        readyRoot.walkBottomUp().forEach { file ->
            if (file == readyRoot) return@forEach
            if (file.isFile) {
                val relative = file.relativeTo(readyRoot).invariantSeparatorsPath
                if (relative !in known) {
                    Logg.w(TAG, "salvage: deleting orphan ready file $relative")
                    file.delete()
                }
            } else if (file.list()?.isEmpty() == true) {
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
