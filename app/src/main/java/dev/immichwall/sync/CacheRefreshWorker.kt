package dev.immichwall.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.immichwall.api.ApiException
import dev.immichwall.api.BaseUrlSelector
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.cache.CacheEntry
import dev.immichwall.cache.PhotoCacheManager
import dev.immichwall.crop.BitmapPipeline
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.AssetSourceResolver
import dev.immichwall.source.PhotoScorer
import dev.immichwall.util.HealthChecker
import dev.immichwall.util.Logg
import dev.immichwall.wallpaper.RotationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/**
 * Periodic (and one-shot top-up / manual) cache refresh. Runs [RefreshEngine.refresh] for up to
 * [PHOTOS_PER_RUN] new photos per run. Transport failures never empty the cache — the worker
 * retries with exponential backoff while the wallpaper keeps serving what it has.
 */
class CacheRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val summary = RefreshEngine(applicationContext).refresh(PHOTOS_PER_RUN)
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
 * One run: probe base URL → derive per-entry staleness (`sourceKey` + crop dims) → resolve
 * candidates → download / face-crop / promote up to [refresh]`maxNew` photos → evict to
 * target → persist manifest bookkeeping → health check → wake the rotation controller if
 * the cache was previously empty.
 *
 * Per-asset API errors are skipped; transport [IOException] propagates so callers can retry —
 * already-promoted photos are kept (dedup makes the retry cheap).
 */
class RefreshEngine(private val ctx: Context) {

    fun refresh(maxNew: Int): String {
        val settings = SettingsRepository.get(ctx)
        val cache = PhotoCacheManager.get(ctx)
        val spec = settings.sourceSpec ?: return SUMMARY_NO_SOURCE

        val selector = BaseUrlSelector(settings)
        if (selector.probeAndSelect() == null) {
            Logg.w(TAG, "no reachable server; serving cache")
            cache.updateManifest { it.copy(lastSyncResult = SUMMARY_OFFLINE) }
            reportHealth()
            return SUMMARY_OFFLINE
        }
        val client = ImmichApiClient({ selector.currentBaseUrl() }, { settings.apiKey })
        val resolver = AssetSourceResolver(client)

        val today = LocalDate.now().toString()
        // People preference is per-cycle; the active cycle's choice governs this refresh.
        val peoplePreference = settings.cyclesConsistentWithActiveSpec()
            .firstOrNull { it.id == settings.activeCycleId }
            ?.peoplePreference ?: settings.peoplePreference
        // Quality settings are part of the cache's identity: photos ingested under old
        // rules (e.g. before "prefer people" was enabled) mismatch and drain gradually,
        // so a settings change re-rolls the cache instead of leaving stale junk behind.
        val qualityTag = if (settings.qualityFilterEnabled) "q:$peoplePreference" else "q:off"
        val sourceKey = "${spec.stableKey(today)}|$qualityTag"
        val manifestBefore = cache.manifest()
        val wasEmpty = cache.readyCount() == 0

        var cropW = settings.cropWidth
        var cropH = settings.cropHeight
        if (cropW <= 0 || cropH <= 0) {
            cropW = FALLBACK_CROP_W
            cropH = FALLBACK_CROP_H
        }

        // Staleness is derived per entry (not from the manifest-level key, which a partially
        // drained run has already overwritten): a different sourceKey (source switch or
        // Memories date rollover) or crop dims that no longer match the panel. Stale entries
        // are drained only 1:1 as net-new replacements arrive so the wallpaper never goes
        // blank mid-switch; leftovers are re-detected on every run until gone.
        val staleQueue = ArrayDeque(
            manifestBefore.entries
                .filter { it.sourceKey != sourceKey || it.width != cropW || it.height != cropH }
                .sortedBy { it.addedAt }
                .map { it.assetId }
        )
        if (staleQueue.isNotEmpty()) {
            Logg.d(TAG, "${staleQueue.size}/${manifestBefore.entries.size} entries stale (sourceKey ${manifestBefore.sourceKey.take(8)} -> ${sourceKey.take(8)}, crop ${cropW}x$cropH)")
        }

        cleanStaleStaging(cache.stagingDir())

        val candidates = resolver.candidates(spec, maxNew * 2)
        // A candidate counts as cached only if its entry matches the current source and panel
        // size — stale entries stay eligible so they get re-prepared (promote replaces the
        // ready file in place, so the asset never has a no-file window).
        val fresh = candidates.filter { c ->
            val existing = cache.entryFor(c.id)
            existing == null || existing.sourceKey != sourceKey ||
                existing.width != cropW || existing.height != cropH
        }
        Logg.d(TAG, "${candidates.size} candidates, ${fresh.size} new/stale; want up to $maxNew")

        var added = 0
        var failed = 0
        var drained = 0
        var skippedByQuality = 0
        var scored = 0
        var scoreSum = 0f
        val priorityPersonIds = spec.priorityPersonIds()
        val qualityEnabled = settings.qualityFilterEnabled

        // Each run stages into its own subdirectory so overlapping runs (manual + periodic
        // use distinct unique-work names and may coexist) never clobber each other's
        // in-flight files.
        val runDir = File(cache.stagingDir(), "run-${UUID.randomUUID()}")
        runDir.mkdirs()

        /** Download → prepare → (optional bitmap gate) → promote. True if a photo landed. */
        fun ingest(asset: dev.immichwall.api.AssetDto, faces: List<dev.immichwall.api.AssetFaceDto>, gateBitmap: Boolean): Boolean {
            val raw = File(runDir, "${asset.id}.raw")
            val prepared = File(runDir, "${asset.id}.jpg")
            try {
                val download = client.downloadAssetImage(asset.id, raw)

                if (!BitmapPipeline.prepareWallpaper(raw, faces, priorityPersonIds, cropW, cropH, prepared)) {
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
                    fileName = "${asset.id}.jpg",
                    addedAt = System.currentTimeMillis(),
                    width = cropW,
                    height = cropH,
                    sourceKey = sourceKey,
                    sourceSize = download.tier,
                )
                val isReplacement = cache.containsAsset(asset.id)
                if (cache.promote(entry, prepared)) {
                    added++
                    // A re-prepared entry is no longer stale — never drain it below.
                    staleQueue.remove(asset.id)
                    if (!isReplacement) {
                        // Drain one stale entry per NET-new photo that arrives; in-place
                        // re-preparations must not shrink the cache.
                        while (staleQueue.isNotEmpty()) {
                            val staleId = staleQueue.removeFirst()
                            if (cache.containsAsset(staleId)) {
                                cache.removeEntry(staleId)
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
        data class Deferred(val asset: dev.immichwall.api.AssetDto, val faces: List<dev.immichwall.api.AssetFaceDto>, val score: Float)
        val deferred = mutableListOf<Deferred>()

        try {
            for (asset in fresh) {
                if (added >= maxNew) break
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

            // Relaxation: small pools (Favorites, Memories) must never starve the cache —
            // fill the remainder from the least-bad deferred candidates, bitmap gate off.
            if (added < maxNew && deferred.isNotEmpty() && added < fresh.size) {
                for (d in deferred.sortedByDescending { it.score }) {
                    if (added >= maxNew) break
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

        cache.evictToTarget(settings.targetCacheCount)

        val parts = mutableListOf("added $added")
        if (drained > 0) parts += "drained $drained stale"
        if (skippedByQuality > 0) parts += "$skippedByQuality quality-skipped"
        if (failed > 0) parts += "$failed failed"
        val summary = "ok: ${parts.joinToString(", ")}, cache=${cache.readyCount()}"

        val now = System.currentTimeMillis()
        cache.updateManifest {
            it.copy(
                sourceKey = sourceKey,
                cropWidth = cropW,
                cropHeight = cropH,
                lastSyncAt = now,
                lastSyncResult = summary,
            )
        }

        reportHealth()

        val sourceChangedThisRun = manifestBefore.entries.isNotEmpty() &&
            manifestBefore.sourceKey.isNotEmpty() && manifestBefore.sourceKey != sourceKey
        if (wasEmpty && cache.readyCount() > 0) {
            RotationController.refreshFromCacheHead(ctx)
        } else if (sourceChangedThisRun && added > 0) {
            // The user just switched sources; crossfade the visible wallpaper to the new
            // source's freshest photo instead of waiting for the next screen-off.
            cache.jumpToNewest()
            RotationController.refreshFromCacheHead(ctx)
        }

        return summary
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
        private const val FALLBACK_CROP_W = 1280
        private const val FALLBACK_CROP_H = 2856
        private const val STAGING_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
