package dev.immichwall.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.immichwall.api.ApiException
import dev.immichwall.api.AssetDto
import dev.immichwall.api.AssetFaceDto
import dev.immichwall.api.BaseUrlSelector
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.cache.CacheEntry
import dev.immichwall.cache.CachePolicy
import dev.immichwall.cache.PhotoCacheManager
import dev.immichwall.crop.BitmapPipeline
import dev.immichwall.schedule.ScheduleApplier
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.AssetSourceResolver
import dev.immichwall.source.CycleKeys
import dev.immichwall.source.PhotoScorer
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import dev.immichwall.util.HealthChecker
import dev.immichwall.util.Logg
import dev.immichwall.wallpaper.RotationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Periodic (and one-shot top-up / manual) cache refresh. Runs [RefreshEngine.refresh] for up to
 * [PHOTOS_PER_RUN] new photos per run. Transport failures never empty the cache — the worker
 * retries with exponential backoff while the wallpaper keeps serving what it has.
 */
class CacheRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val summary = RefreshEngine(applicationContext).refresh(PHOTOS_PER_RUN) { isStopped }
            Logg.d(TAG, "refresh done: $summary")
            if (summary == RefreshEngine.SUMMARY_OFFLINE) Result.retry() else Result.success()
        } catch (e: IOException) {
            Logg.w(TAG, "refresh transport failure (${e.message}); retrying with backoff")
            Result.retry()
        } catch (e: ApiException) {
            Logg.e(TAG, "refresh API failure: HTTP ${e.code}", e)
            Result.retry()
        } catch (e: Exception) {
            Logg.e(TAG, "refresh failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "CacheRefreshWorker"
        const val PHOTOS_PER_RUN = 30
    }
}

/**
 * Shared refresh logic used by [InitialFillWorker] and [CacheRefreshWorker].
 *
 * One run: apply the schedule → probe base URL → work out which cycles need photos (the
 * active one, plus those the schedule calls for in the days ahead) → fill each from its
 * own source, splitting the run's budget → evict each to target → purge cycles the
 * schedule no longer needs → persist bookkeeping → health check → move the visible
 * wallpaper onto the active cycle if it is not showing it yet. The purge and the move
 * only happen if the cycle that was active when the run began still is. An API failure on
 * the active cycle fails the run (the workers retry); one on a prefetch cycle does not.
 *
 * Per-asset API errors are skipped; transport [IOException] propagates so callers can retry —
 * already-promoted photos are kept (dedup makes the retry cheap).
 */
class RefreshEngine(private val ctx: Context) {

    private data class FillResult(
        val added: Int = 0,
        val failed: Int = 0,
        val drained: Int = 0,
        val skippedByQuality: Int = 0,
    ) {
        operator fun plus(o: FillResult) = FillResult(
            added + o.added, failed + o.failed, drained + o.drained, skippedByQuality + o.skippedByQuality,
        )
    }

    /**
     * Runs are serialized process-wide. WorkManager starts manual, initial-fill, top-up and
     * periodic work under different names, and a replaced run keeps going until it next
     * looks at [isStopped]; two runs interleaving would each purge on its own stale idea of
     * which cycle is active.
     *
     * @param isStopped polled between photos, so a run WorkManager has replaced or cancelled
     *   ends promptly instead of downloading on in front of its successor.
     */
    fun refresh(maxNew: Int, isStopped: () -> Boolean = { false }): String = runLock.withLock {
        try {
            refreshLocked(maxNew, isStopped)
        } finally {
            // However the run ended — finished, stopped by WorkManager, or thrown out by a
            // dead link — photos it cached must not sit behind a blank wallpaper: nothing
            // else loads the first bitmap until the next screen-off.
            if (RotationController.currentBitmap() == null && PhotoCacheManager.get(ctx).readyCount() > 0) {
                RotationController.refreshFromCacheHead(ctx)
            }
        }
    }

    private fun refreshLocked(maxNew: Int, isStopped: () -> Boolean): String {
        // A run that was stopped while it waited for the lock has nothing to do.
        if (isStopped()) return SUMMARY_STOPPED

        val settings = SettingsRepository.get(ctx)
        val cache = PhotoCacheManager.get(ctx)

        // Before anything is fetched, so a sync on a boundary day fills the right cycle.
        ScheduleApplier.applyIfDue(ctx)

        if (settings.sourceSpec == null) return SUMMARY_NO_SOURCE
        val cycles = settings.cyclesConsistentWithActiveSpec()
        val active = cycles.firstOrNull { it.id == settings.activeCycleId } ?: return SUMMARY_NO_SOURCE

        val selector = BaseUrlSelector(settings)
        if (selector.probeAndSelect() == null) {
            Logg.w(TAG, "no reachable server; serving cache")
            cache.updateManifest { it.copy(lastSyncResult = SUMMARY_OFFLINE) }
            reportHealth()
            return SUMMARY_OFFLINE
        }
        val client = ImmichApiClient({ selector.currentBaseUrl() }, { settings.apiKey })
        val resolver = AssetSourceResolver(client)

        // The active cycle first, then the ones the schedule calls for in the days ahead.
        val today = LocalDate.now()
        val byId = cycles.associateBy { it.id }
        val targets = (listOf(active) + ScheduleApplier.plan(settings).retainedCycleIds.mapNotNull(byId::get))
            .distinctBy { it.id }
        val keys = targets.associate { it.id to CycleKeys.keyFor(it, settings.qualityFilterEnabled, today) }
        val activeKey = keys.getValue(active.id)

        var cropW = settings.cropWidth
        var cropH = settings.cropHeight
        if (cropW <= 0 || cropH <= 0) {
            cropW = FALLBACK_CROP_W
            cropH = FALLBACK_CROP_H
        }

        cleanStaleStaging(cache.stagingDir())

        // A cycle that is not active and already full needs nothing. The rest share this
        // run's budget: every cycle but the last gets half of what is left, so prefetching
        // is never starved by the active cycle's own churn.
        val needy = targets.filter { cycle ->
            when {
                cycle.id == active.id -> true
                // A Memories key is per-day: what is fetched today cannot be shown tomorrow.
                cycle.spec is SourceSpec.Memories -> false
                else -> cache.countFor(keys.getValue(cycle.id)) < settings.targetCacheCount
            }
        }
        var remaining = maxNew
        var total = FillResult()
        var activeFailure: ApiException? = null
        needy.forEachIndexed { index, cycle ->
            if (remaining <= 0 || isStopped()) return@forEachIndexed
            val key = keys.getValue(cycle.id)
            val share = if (index == needy.lastIndex) remaining else maxOf(1, remaining / 2)
            val result = try {
                fillCycle(
                    settings, client, resolver, cache, cycle, key, share, cropW, cropH,
                    settings.qualityFilterEnabled, isStopped,
                )
            } catch (e: ApiException) {
                // One prefetch cycle's source being unusable (its album was deleted, say)
                // must not stop the others from filling. The ACTIVE cycle failing is a
                // failed sync, and is rethrown below once the others have had their turn.
                Logg.w(TAG, "'${cycle.name}' could not be fetched: HTTP ${e.code} ${e.message}")
                if (cycle.id == active.id) activeFailure = e
                FillResult(failed = 1)
            }
            remaining -= result.added
            total += result
            cache.evictToTarget(key, settings.targetCacheCount)
        }

        if (isStopped()) {
            Logg.d(TAG, "run stopped; purge and bookkeeping are its successor's")
            return SUMMARY_STOPPED
        }

        // An API failure on the active cycle (a revoked key, a 5xx from search) is a failed
        // sync: no "last synced" stamp, and the workers retry it with backoff as before.
        // The reason is recorded so the status screen can say why syncs are failing; the
        // time of the last good sync stays, since the stale-cache warning counts from it.
        activeFailure?.let { failure ->
            cache.updateManifest { it.copy(lastSyncResult = "failed: ${active.name}: HTTP ${failure.code}") }
            throw failure
        }

        // The user or the schedule may have switched cycles while this run was downloading.
        // Purging or jumping on this run's idea of "active" would then delete the new
        // cycle's photos and flip the wallpaper back, so both wait for the next run.
        val stillActive = settings.activeCycleId == active.id &&
            CycleKeys.activeKey(settings, today) == activeKey
        // Likewise the set to keep is read again now, not taken from the start of the run.
        val retainedKeys = ScheduleApplier.plan(settings).retainedCycleIds.mapNotNull(byId::get)
            .mapTo(HashSet()) { CycleKeys.keyFor(it, settings.qualityFilterEnabled, today) }
        retainedKeys += activeKey
        settings.retainSourceSizes(retainedKeys)

        val onScreen = cache.currentEntry()
        val purged = if (stillActive) cache.purgeOutside(retainedKeys, activeKey) else 0

        val parts = mutableListOf("added ${total.added}")
        if (purged > 0) parts += "purged $purged"
        if (total.drained > 0) parts += "drained ${total.drained} stale"
        if (total.skippedByQuality > 0) parts += "${total.skippedByQuality} quality-skipped"
        if (total.failed > 0) parts += "${total.failed} failed"
        val summary = "ok: ${parts.joinToString(", ")}, cache=${cache.readyCount()}"

        val now = System.currentTimeMillis()
        cache.updateManifest {
            it.copy(
                sourceKey = activeKey,
                cropWidth = cropW,
                cropHeight = cropH,
                lastSyncAt = now,
                lastSyncResult = summary,
            )
        }

        reportHealth()

        if (stillActive && onScreen?.sourceKey != activeKey && cache.countFor(activeKey) > 0) {
            // The photo on screen belongs to another cycle and the active one now has
            // something: jump to its freshest photo (a crossfade when the screen is on).
            cache.jumpToNewest(activeKey)
            RotationController.refreshFromCacheHead(ctx)
        }

        return summary
    }

    /**
     * Fetches up to [maxNew] new photos for one cycle and stamps them with [sourceKey].
     * Within a cycle only a panel-size change makes a photo stale now (other cycles'
     * photos are purged wholesale by [refresh]); stale photos are re-prepared in place
     * when the server offers them again and otherwise drained one-for-one as net-new
     * photos arrive, so the set never shrinks mid-change.
     */
    private fun fillCycle(
        settings: SettingsRepository,
        client: ImmichApiClient,
        resolver: AssetSourceResolver,
        cache: PhotoCacheManager,
        cycle: SavedCycle,
        sourceKey: String,
        maxNew: Int,
        cropW: Int,
        cropH: Int,
        qualityEnabled: Boolean,
        isStopped: () -> Boolean,
    ): FillResult {
        val spec = cycle.spec
        val peoplePreference = cycle.peoplePreference
        val priorityPersonIds = spec.priorityPersonIds()

        val staleQueue = ArrayDeque(
            cache.manifest().entries
                .filter { it.sourceKey == sourceKey && (it.width != cropW || it.height != cropH) }
                .sortedBy { it.addedAt }
        )
        if (staleQueue.isNotEmpty()) {
            Logg.d(TAG, "'${cycle.name}': ${staleQueue.size} entries prepared for another panel size (now ${cropW}x$cropH)")
        }

        val requested = maxNew * 2
        val candidates = resolver.candidates(spec, requested)
        // A multi-person "any of" search asks each person for a share of the request, so a
        // short answer from one of them says nothing about the others.
        val sourceSize = if (spec.mergesPerPersonSearches()) null
        else CachePolicy.sourceSizeIfExhausted(candidates.size, requested)
        settings.setSourceSize(sourceKey, sourceSize)
        // A candidate counts as cached only if this cycle holds it at the current panel
        // size — stale entries stay eligible so they get re-prepared (promote replaces the
        // ready file in place, so the asset never has a no-file window).
        val fresh = candidates.filter { c ->
            val existing = cache.entryFor(sourceKey, c.id)
            existing == null || existing.width != cropW || existing.height != cropH
        }
        Logg.d(TAG, "'${cycle.name}': ${candidates.size} candidates, ${fresh.size} new/stale; want up to $maxNew")

        var added = 0
        var failed = 0
        var drained = 0
        var skippedByQuality = 0
        var scored = 0
        var scoreSum = 0f

        // Each run stages into its own subdirectory so overlapping runs (manual + periodic
        // use distinct unique-work names and may coexist) never clobber each other's
        // in-flight files.
        val runDir = File(cache.stagingDir(), "run-${UUID.randomUUID()}")
        runDir.mkdirs()

        /** Prefetching can double the cache near a boundary; stop adding rather than fill the disk. */
        fun outOfRoom(): Boolean {
            if (CachePolicy.hasRoom(ctx.filesDir.usableSpace)) return false
            Logg.w(TAG, "storage nearly full; not adding more photos this run")
            return true
        }

        /** Download → prepare → (optional bitmap gate) → promote. True if a photo landed. */
        fun ingest(asset: AssetDto, faces: List<AssetFaceDto>, gateBitmap: Boolean): Boolean {
            val raw = File(runDir, "${asset.id}.raw")
            val prepared = File(runDir, "${asset.id}.jpg")
            try {
                val download = client.downloadAssetImage(asset.id, raw)

                val focus = floatArrayOf(0.5f, 0.5f)
                if (!BitmapPipeline.prepareWallpaper(raw, faces, priorityPersonIds, cropW, cropH, prepared, focus)) {
                    Logg.w(TAG, "prepareWallpaper failed for ${asset.id}; skipping")
                    failed++
                    return false
                }

                if (gateBitmap) {
                    val bmp = BitmapPipeline.decodeReady(prepared)
                    val reason = bmp?.let { PhotoScorer.bitmapReject(it) }
                    if (reason != null) {
                        Logg.d(TAG, "quality: rejected ${asset.id} post-decode — $reason")
                        skippedByQuality++
                        return false
                    }
                }

                val entry = CacheEntry(
                    assetId = asset.id,
                    fileName = CachePolicy.readyFileName(sourceKey, asset.id),
                    addedAt = System.currentTimeMillis(),
                    width = cropW,
                    height = cropH,
                    sourceKey = sourceKey,
                    sourceSize = download.tier,
                    focusX = focus[0],
                    focusY = focus[1],
                )
                val isReplacement = cache.containsEntry(sourceKey, asset.id)
                if (cache.promote(entry, prepared)) {
                    added++
                    // A re-prepared entry is no longer stale — never drain it below.
                    staleQueue.removeAll { it.assetId == asset.id }
                    if (!isReplacement) {
                        // Drain one stale entry per NET-new photo that arrives; in-place
                        // re-preparations must not shrink the cache.
                        while (staleQueue.isNotEmpty()) {
                            val stale = staleQueue.removeFirst()
                            if (cache.containsEntry(sourceKey, stale.assetId)) {
                                cache.removeEntry(stale)
                                drained++
                                break
                            }
                        }
                    }
                    return true
                }
                Logg.w(TAG, "promote rejected ${asset.id} (verification failed)")
                failed++
                return false
            } catch (e: ApiException) {
                Logg.w(TAG, "asset ${asset.id} failed: HTTP ${e.code} ${e.message}; skipping")
                failed++
                return false
            } catch (oom: OutOfMemoryError) {
                // A transient memory spike must not kill the whole run — skip this asset.
                Logg.w(TAG, "asset ${asset.id} failed: OutOfMemoryError; skipping")
                failed++
                return false
            } finally {
                // promote() renames the staged file away on success, so these are no-ops then.
                raw.delete()
                prepared.delete()
            }
        }

        // Deferred-not-disqualified candidates for the relaxation pass, best first.
        data class Deferred(val asset: AssetDto, val faces: List<AssetFaceDto>, val score: Float)
        val deferred = mutableListOf<Deferred>()

        try {
            for (asset in fresh) {
                if (added >= maxNew || isStopped() || outOfRoom()) break
                // Faces come first: they feed both the quality score and the crop.
                val faces = try {
                    client.getFaces(asset.id)
                } catch (e: ApiException) {
                    Logg.w(TAG, "faces unavailable for ${asset.id} (HTTP ${e.code}); using fallback crop")
                    emptyList()
                }

                if (qualityEnabled) {
                    val detail = try {
                        client.getAsset(asset.id)
                    } catch (e: ApiException) {
                        Logg.w(TAG, "asset detail unavailable for ${asset.id} (HTTP ${e.code}); scoring without EXIF")
                        null
                    }
                    val verdict = PhotoScorer.preDownloadVerdict(
                        asset, detail, faces, priorityPersonIds, cropW, cropH,
                        peoplePreference = peoplePreference,
                    )
                    scored++
                    scoreSum += verdict.score
                    if (!verdict.passes) {
                        Logg.d(TAG, "quality: deferred ${asset.id} (score %.2f%s)".format(
                            verdict.score, verdict.reject?.let { " — $it" } ?: ""))
                        skippedByQuality++
                        deferred += Deferred(asset, faces, verdict.score)
                        continue
                    }
                }

                ingest(asset, faces, gateBitmap = qualityEnabled)
            }

            // Relaxation: small pools (Favorites, Memories, a short album) must never starve
            // the cache — fill the remainder from the least-bad deferred candidates, bitmap
            // gate off.
            if (added < maxNew && deferred.isNotEmpty() && added < fresh.size) {
                for (d in deferred.sortedByDescending { it.score }) {
                    if (added >= maxNew || isStopped() || outOfRoom()) break
                    Logg.d(TAG, "quality: relaxed ingest of ${d.asset.id} (score %.2f)".format(d.score))
                    ingest(d.asset, d.faces, gateBitmap = false)
                }
            }
        } finally {
            runDir.deleteRecursively()
        }

        if (scored > 0) {
            Logg.d(TAG, "quality: scored $scored candidates, avg %.2f, deferred $skippedByQuality".format(scoreSum / scored))
        }

        return FillResult(added, failed, drained, skippedByQuality)
    }

    private fun SourceSpec.mergesPerPersonSearches(): Boolean = when (this) {
        is SourceSpec.People -> ids.size > 1 && !requireAll
        is SourceSpec.Custom -> query.isBlank() && personIds.size > 1 && !requireAll
        else -> false
    }

    private fun reportHealth() {
        val issues = HealthChecker.check(ctx)
        for (issue in issues) {
            Logg.w(TAG, "health [${issue.id} sev=${issue.severity}] ${issue.message}")
        }
    }

    /**
     * Removes staging leftovers from crashed runs — loose files (legacy layout) and per-run
     * subdirectories alike. Age-gated so an overlapping live run (manual + periodic use
     * distinct unique-work names and may coexist) is never clobbered.
     */
    private fun cleanStaleStaging(stagingDir: File) {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        stagingDir.listFiles()?.forEach { file ->
            if (file.lastModified() >= cutoff) return@forEach
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    companion object {
        private const val TAG = "RefreshEngine"
        const val SUMMARY_OFFLINE = "offline: serving cache"
        const val SUMMARY_NO_SOURCE = "no source configured"
        const val SUMMARY_STOPPED = "stopped"
        private const val FALLBACK_CROP_W = 1280
        private const val FALLBACK_CROP_H = 2856
        private const val STAGING_MAX_AGE_MS = 6L * 60 * 60 * 1000

        /** Serializes [refresh] across every worker in the process. */
        private val runLock = ReentrantLock()
    }
}
