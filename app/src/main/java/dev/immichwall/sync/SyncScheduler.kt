package dev.immichwall.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.util.Logg
import java.util.concurrent.TimeUnit

/**
 * All WorkManager scheduling for the cache-refresh pipeline (platform JobScheduler backed —
 * no GMS required). Unique work names keep every flavor idempotent:
 *
 * - [ensurePeriodic]: the steady-state periodic refresh (KEEP, so calling it from app start /
 *   health checks is free); pass `forceReplace = true` after the interval setting changed to
 *   re-register the schedule.
 * - [kickInitialFill]: expedited first 15 photos right after onboarding; the worker chains a
 *   one-shot top-up ([enqueueTopUp]) toward the full target.
 * - [kickManualRefresh]: user-initiated "refresh now" from the status screen.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    const val PERIODIC_WORK_NAME = "cache-refresh"
    const val INITIAL_FILL_WORK_NAME = "initial-fill"
    const val TOP_UP_WORK_NAME = "cache-top-up"
    const val MANUAL_WORK_NAME = "manual-refresh"

    /**
     * Ensures the unique periodic refresh is scheduled at the configured interval with
     * CONNECTED + battery-not-low constraints. Default ([ExistingPeriodicWorkPolicy.KEEP])
     * never disturbs an existing schedule; [forceReplace] cancels and re-enqueues so a changed
     * `refreshIntervalHours` setting takes effect immediately.
     */
    @JvmOverloads
    fun ensurePeriodic(ctx: Context, forceReplace: Boolean = false) {
        val hours = SettingsRepository.get(ctx).refreshIntervalHours.coerceIn(1, 48).toLong()
        val request = PeriodicWorkRequestBuilder<CacheRefreshWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (SettingsRepository.get(ctx).syncOverCellular) NetworkType.CONNECTED
                        else NetworkType.UNMETERED
                    )
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            // UPDATE changes the interval in place without resetting the period phase or
            // dropping backoff state (CANCEL_AND_REENQUEUE did both).
            if (forceReplace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Logg.d(TAG, "periodic refresh ensured: every ${hours}h (forceReplace=$forceReplace)")
    }

    /**
     * Expedited one-shot initial fill (first 15 photos). Expedited work only allows network /
     * storage constraints, so battery-not-low is intentionally absent here. Also ensures the
     * periodic schedule exists so onboarding leaves the app fully armed.
     */
    fun kickInitialFill(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<InitialFillWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(connectedConstraints(ctx))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            INITIAL_FILL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        ensurePeriodic(ctx)
        Logg.d(TAG, "initial fill kicked")
    }

    /** User-initiated refresh from the status screen; expedited for responsiveness. */
    fun kickManualRefresh(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<CacheRefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(connectedConstraints(ctx))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Logg.d(TAG, "manual refresh kicked")
    }

    /**
     * Regular (non-expedited) one-shot [CacheRefreshWorker] chained by [InitialFillWorker] to
     * top the cache up from the initial 15 toward the configured target.
     */
    internal fun enqueueTopUp(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<CacheRefreshWorker>()
            .setConstraints(connectedConstraints(ctx))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            TOP_UP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Logg.d(TAG, "top-up enqueued")
    }

    /** Wi-Fi (unmetered) only by default; any connection when the user opts into cellular. */
    private fun connectedConstraints(ctx: Context): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (SettingsRepository.get(ctx).syncOverCellular) NetworkType.CONNECTED
                else NetworkType.UNMETERED
            )
            .build()
}
