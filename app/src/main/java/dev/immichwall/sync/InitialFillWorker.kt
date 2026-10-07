package dev.immichwall.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.immichwall.api.ApiException
import dev.immichwall.util.Logg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Expedited first fill after onboarding: grabs the first [INITIAL_PHOTO_COUNT] photos so the
 * wallpaper works within seconds (enqueued expedited with
 * [androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] by
 * [SyncScheduler.kickInitialFill]), then chains a regular one-shot [CacheRefreshWorker] to top
 * the cache up toward the configured target.
 *
 * minSdk is 34, so no [getForegroundInfo] override is needed — expedited work only falls back
 * to a foreground service on pre-Android 12 devices.
 */
class InitialFillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val summary = RefreshEngine(applicationContext).refresh(INITIAL_PHOTO_COUNT) { isStopped }
            Logg.d(TAG, "initial fill: $summary")
            when (summary) {
                RefreshEngine.SUMMARY_OFFLINE -> Result.retry()
                // A stopped run must not chain a top-up; whatever replaced it will.
                RefreshEngine.SUMMARY_NO_SOURCE, RefreshEngine.SUMMARY_STOPPED -> Result.success()
                else -> {
                    SyncScheduler.enqueueTopUp(applicationContext)
                    Result.success()
                }
            }
        } catch (e: IOException) {
            Logg.w(TAG, "initial fill transport failure (${e.message}); retrying with backoff")
            Result.retry()
        } catch (e: ApiException) {
            Logg.e(TAG, "initial fill API failure: HTTP ${e.code}", e)
            Result.retry()
        } catch (e: Exception) {
            Logg.e(TAG, "initial fill failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "InitialFillWorker"
        const val INITIAL_PHOTO_COUNT = 15
    }
}
